package com.tactical.bodycam.agent.services

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pedro.common.AudioCodec
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.rtsp.RtspCamera2
import com.tactical.bodycam.agent.AgentPreferences
import com.tactical.bodycam.agent.DispatchWhepPlayer
import com.tactical.bodycam.agent.MainActivity
import com.tactical.bodycam.agent.R
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class BodycamStreamingService : Service() {
    interface AgentListener {
        fun onState(banner: String, status: String)
    }

    inner class LocalBinder : Binder() {
        val service: BodycamStreamingService
            get() = this@BodycamStreamingService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadScheduledExecutor()
    private lateinit var preferences: AgentPreferences
    private var listener: AgentListener? = null
    private var camera: RtspCamera2? = null
    private var prepared = false
    private var wearerPtt = false
    private var dispatchLive = false
    private var player: DispatchWhepPlayer? = null
    private var playerStarting = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var mqtt: MqttClient? = null
    private var lastLocation: Location? = null
    private var fusedCallback: LocationCallback? = null
    private var legacyListener: LocationListener? = null
    private var pollTask: ScheduledFuture<*>? = null
    private var retryPosted = false
    private var workStarted = false
    private var statusLine = "Ready"
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private val connectChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) = postStatus("Connecting")
        override fun onConnectionSuccess() = postStatus("Streaming ${preferences.path}")
        override fun onConnectionFailed(reason: String) {
            Log.w(TAG, "RTSP failed: $reason")
            mainHandler.post {
                postStatus("Stream failed: $reason")
                runCatching { camera?.stopStream() }
                scheduleStreamRetry()
            }
        }
        override fun onNewBitrate(bitrate: Long) = Unit
        override fun onDisconnect() = postStatus("Disconnected")
        override fun onAuthError() = postStatus("RTSP authentication failed")
        override fun onAuthSuccess() = Unit
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        preferences = AgentPreferences(this)
        startAsForeground("Starting camera")
        when (intent?.action) {
            ACTION_STOP -> stopStreaming()
            else -> {
                if (preferences.shouldRun || intent?.action == ACTION_START) {
                    preferences.shouldRun = true
                    beginWork()
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (preferences.shouldRun) scheduleRestart()
        super.onDestroy()
    }

    fun setListener(agentListener: AgentListener?) {
        listener = agentListener
        publishState()
    }

    fun setPushToTalkActive(active: Boolean) {
        wearerPtt = active
        publishPtt(active)
        publishState()
    }

    private fun beginWork() {
        if (workStarted) {
            publishState()
            return
        }
        workStarted = true
        acquireWakeLock()
        startLocation()
        work.execute { connectMqtt() }
        startCameraStream()
        startDispatchPoll()
    }

    private fun startCameraStream() {
        if (!hasPermission(Manifest.permission.CAMERA) || !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            postStatus("Camera and microphone permission are required")
            return
        }
        val url = preferences.rtspUrl()
        try {
            if (!ensurePrepared()) {
                postStatus("This phone could not start the H.264 encoder")
                scheduleStreamRetry()
                return
            }
            val active = camera
            if (active != null && !active.isStreaming) {
                active.startStream(url)
                postStatus("Publishing $url")
            }
        } catch (error: Exception) {
            Log.e(TAG, "startCameraStream", error)
            postStatus(error.message ?: "Camera failed")
            scheduleStreamRetry()
        }
    }

    private fun ensurePrepared(): Boolean {
        if (prepared && camera != null) return true
        val created = RtspCamera2(applicationContext, false, connectChecker)
        camera = created
        created.setVideoCodec(VideoCodec.H264)
        created.setAudioCodec(AudioCodec.AAC)
        val rotation = CameraHelper.getCameraOrientation(applicationContext)
        var videoReady = created.prepareVideo(1280, 720, 30, 2_500_000, 2, rotation)
        if (!videoReady) {
            videoReady = created.prepareVideo(640, 480, 30, 1_200_000, 2, rotation)
        }
        // echoCanceler=true binds Android's AcousticEchoCanceler to the mic session.
        var audioReady = created.prepareAudio(128 * 1024, 48_000, false, true, true)
        if (!audioReady) {
            audioReady = created.prepareAudio(128 * 1024, 44_100, false, true, true)
        }
        prepared = videoReady
        if (!audioReady) postStatus("Video only: microphone encoder was not available")
        return videoReady
    }

    private fun scheduleStreamRetry() {
        if (retryPosted || !preferences.shouldRun) return
        retryPosted = true
        mainHandler.postDelayed({
            retryPosted = false
            if (preferences.shouldRun && camera?.isStreaming != true) startCameraStream()
        }, 2000)
    }

    private fun startDispatchPoll() {
        pollTask?.cancel(false)
        pollTask = work.scheduleWithFixedDelay({
            val ready = queryDispatchReady()
            mainHandler.post { onDispatchAvailability(ready) }
            publishTelemetry()
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun queryDispatchReady(): Boolean {
        val connection = (URL(preferences.dispatchApiUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 800
            readTimeout = 800
        }
        return try {
            connection.responseCode == 200 &&
                connection.inputStream.bufferedReader().use { it.readText() }.contains(READY_JSON)
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun onDispatchAvailability(ready: Boolean) {
        if (!preferences.shouldRun) return
        if (ready && !dispatchLive && !playerStarting) {
            playerStarting = true
            camera?.disableAudio()
            routeSpeaker(true)
            val whep = player ?: DispatchWhepPlayer(this, playerListener).also { player = it }
            whep.start(preferences.dispatchWhepUrl())
        } else if (!ready && (dispatchLive || playerStarting)) {
            player?.stop()
            playerStarting = false
            dispatchLive = false
            routeSpeaker(false)
            if (!wearerPtt) camera?.enableAudio()
            publishState()
        }
    }

    private val playerListener = object : DispatchWhepPlayer.Listener {
        override fun onPlaying() {
            playerStarting = false
            dispatchLive = true
            camera?.disableAudio()
            publishState()
        }
        override fun onStopped() {
            playerStarting = false
            if (!dispatchLive) return
            dispatchLive = false
            publishState()
        }
        override fun onFailed(reason: String) {
            playerStarting = false
            dispatchLive = false
            routeSpeaker(false)
            camera?.enableAudio()
            postStatus(reason)
        }
    }

    private fun routeSpeaker(dispatch: Boolean) {
        if (dispatch) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun startLocation() {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return
        val playServices = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        if (playServices) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L).build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    lastLocation = result.lastLocation ?: lastLocation
                }
            }
            fusedCallback = callback
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(request, callback, Looper.getMainLooper())
            return
        }
        val manager = getSystemService(LocationManager::class.java)
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return
        }
        val legacy = LocationListener { location -> lastLocation = location }
        legacyListener = legacy
        runCatching { manager.requestLocationUpdates(provider, 5000L, 1f, legacy) }
    }

    private fun connectMqtt() {
        val broker = preferences.mqttBroker
        if (broker.isBlank()) return
        val serverUri = when {
            broker.startsWith("tcp://") -> broker
            broker.contains(":") -> "tcp://$broker"
            else -> "tcp://$broker:1883"
        }
        try {
            val client = MqttClient(serverUri, "bodycam-${preferences.path}-${System.currentTimeMillis()}", MemoryPersistence())
            client.setCallback(object : MqttCallback {
                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "MQTT lost", cause)
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val command = message?.payload?.toString(Charsets.UTF_8)?.trim()?.lowercase().orEmpty()
                    mainHandler.post { handleCommand(command) }
                }
                override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) = Unit
            })
            client.connect(MqttConnectOptions().apply {
                isAutomaticReconnect = true
                connectionTimeout = 5
                isCleanSession = true
            })
            client.subscribe(preferences.commandTopic(), 0)
            mqtt = client
            Log.i(TAG, "MQTT connected to $serverUri")
        } catch (error: Exception) {
            Log.w(TAG, "MQTT skipped", error)
            mainHandler.post { postStatus("MQTT skipped: ${error.message}") }
        }
    }

    private fun handleCommand(command: String) {
        when (command) {
            "start" -> if (camera?.isStreaming != true) startCameraStream()
            "stop" -> stopStreaming()
            "ptt_on" -> setPushToTalkActive(true)
            "ptt_off" -> setPushToTalkActive(false)
        }
    }

    private fun publishPtt(active: Boolean) {
        val client = mqtt ?: return
        if (!client.isConnected) return
        work.execute {
            runCatching {
                client.publish(preferences.pttTopic(), MqttMessage(active.toString().toByteArray()))
            }
        }
    }

    private fun publishTelemetry() {
        val client = mqtt ?: return
        if (!client.isConnected) return
        val location = lastLocation
        val payload = buildString {
            append("{\"streaming\":").append(camera?.isStreaming == true)
            append(",\"ptt\":").append(wearerPtt)
            append(",\"dispatch\":").append(dispatchLive)
            if (location != null) {
                append(",\"lat\":").append(location.latitude)
                append(",\"lon\":").append(location.longitude)
                append(",\"accuracy\":").append(location.accuracy)
            }
            append("}")
        }
        runCatching {
            client.publish(preferences.telemetryTopic(), MqttMessage(payload.toByteArray()).apply { qos = 0 })
        }
    }

    private fun stopStreaming() {
        preferences.shouldRun = false
        workStarted = false
        pollTask?.cancel(false)
        pollTask = null
        player?.release()
        player = null
        dispatchLive = false
        routeSpeaker(false)
        runCatching { camera?.stopStream() }
        runCatching { camera?.stopPreview() }
        camera = null
        prepared = false
        stopLocation()
        work.execute {
            runCatching { mqtt?.disconnect() }
            mqtt = null
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
        statusLine = "Stopped"
        publishState()
    }

    private fun stopLocation() {
        fusedCallback?.let {
            LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it)
        }
        fusedCallback = null
        legacyListener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        legacyListener = null
    }

    private fun scheduleRestart() {
        if (!this::preferences.isInitialized || !preferences.shouldRun) return
        val restart = Intent(this, BodycamStreamingService::class.java).apply { action = ACTION_START }
        val pending = PendingIntent.getService(
            this,
            1,
            restart,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = getSystemService(AlarmManager::class.java)
        alarm.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1500, pending)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bodycam:stream").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Bodycam stream", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun postStatus(text: String) {
        statusLine = text
        val notice = getSystemService(NotificationManager::class.java)
        notice.notify(NOTIFICATION_ID, buildNotification(text))
        publishState()
    }

    private fun publishState() {
        val banner = when {
            wearerPtt -> getString(R.string.banner_ptt)
            dispatchLive -> getString(R.string.banner_dispatch)
            else -> getString(R.string.banner_standby)
        }
        listener?.onState(banner, statusLine)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "BodycamAgent"
        private const val CHANNEL_ID = "bodycam_stream"
        private const val NOTIFICATION_ID = 41
        private val READY_JSON = Regex("\"ready\"\\s*:\\s*true")
        const val ACTION_START = "com.tactical.bodycam.agent.START"
        const val ACTION_STOP = "com.tactical.bodycam.agent.STOP"
    }
}
