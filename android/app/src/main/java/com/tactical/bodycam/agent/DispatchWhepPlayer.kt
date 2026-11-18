package com.tactical.bodycam.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Plays the dispatcher talkback path from MediaMTX (WebRTC WHEP, receive only).
 * Hardware acoustic echo cancellation is enabled on this audio device. The uplink
 * microphone is owned by RootEncoder, which applies AcousticEchoCanceler as well.
 */
class DispatchWhepPlayer(context: Context, private val listener: Listener) {
    interface Listener {
        fun onPlaying()
        fun onStopped()
        fun onFailed(reason: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val audioModule = JavaAudioDeviceModule.builder(context)
        .setUseHardwareAcousticEchoCanceler(true)
        .setUseHardwareNoiseSuppressor(true)
        .createAudioDeviceModule()
        .also { it.setMicrophoneMute(true) }
    private val factory: PeerConnectionFactory
    private var peer: PeerConnection? = null
    private var sessionUrl: String? = null
    private var running = false

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions()
        )
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
    }

    fun start(whepUrl: String) {
        if (running) return
        running = true
        val connection = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(emptyList()),
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    if (state == PeerConnection.IceConnectionState.FAILED) {
                        fail("Dispatch audio lost the network path")
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
                override fun onIceCandidate(candidate: org.webrtc.IceCandidate) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) = Unit
                override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
                override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
                override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) = Unit
            }
        )
        if (connection == null) {
            fail("Could not open a talkback session")
            return
        }
        peer = connection
        connection.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        connection.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(description: SessionDescription) {
                connection.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        waitForIce(connection) { exchange(connection, whepUrl) }
                    }
                    override fun onSetFailure(error: String) = fail(error)
                }, description)
            }
            override fun onCreateFailure(error: String) = fail(error)
        }, MediaConstraints())
    }

    fun stop() {
        running = false
        val url = sessionUrl
        sessionUrl = null
        peer?.close()
        peer = null
        if (url != null) {
            network.execute {
                runCatching { request(url, "DELETE", null) }
            }
        }
        listener.onStopped()
    }

    fun release() {
        stop()
        factory.dispose()
        audioModule.release()
        network.shutdownNow()
    }

    private fun waitForIce(connection: PeerConnection, ready: () -> Unit) {
        val started = android.os.SystemClock.elapsedRealtime()
        val check = object : Runnable {
            override fun run() {
                val done = connection.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE
                val timedOut = android.os.SystemClock.elapsedRealtime() - started > 2500
                if (done || timedOut || !running) ready() else mainHandler.postDelayed(this, 100)
            }
        }
        mainHandler.post(check)
    }

    private fun exchange(connection: PeerConnection, whepUrl: String) {
        val offer = connection.localDescription?.description
        if (offer.isNullOrBlank()) {
            fail("Dispatch offer was empty")
            return
        }
        network.execute {
            try {
                val result = request(whepUrl, "POST", offer)
                if (!running) return@execute
                if (result.code !in 200..299 || result.body.isBlank()) {
                    fail("Talkback path is not ready (${result.code})")
                    return@execute
                }
                sessionUrl = result.location
                mainHandler.post {
                    connection.setRemoteDescription(object : SdpAdapter() {
                        override fun onSetSuccess() = listener.onPlaying()
                        override fun onSetFailure(error: String) = fail(error)
                    }, SessionDescription(SessionDescription.Type.ANSWER, result.body))
                }
            } catch (error: Exception) {
                fail(error.message ?: "Talkback request failed")
            }
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        mainHandler.post {
            if (!running && peer == null) return@post
            running = false
            peer?.close()
            peer = null
            listener.onFailed(reason)
        }
    }

    private fun request(url: String, method: String, sdp: String?): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4000
            readTimeout = 8000
            if (sdp != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/sdp")
            }
        }
        try {
            if (sdp != null) {
                connection.outputStream.use { it.write(sdp.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val locationHeader = connection.getHeaderField("Location")
            val location = locationHeader?.let { header ->
                if (header.startsWith("http")) header else URL(URL(url), header).toString()
            }
            return HttpResult(code, body, location)
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpResult(val code: Int, val body: String, val location: String?)

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private const val TAG = "DispatchWhep"
    }
}
