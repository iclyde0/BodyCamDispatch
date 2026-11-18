package com.tactical.bodycam.agent

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tactical.bodycam.agent.databinding.ActivityMainBinding
import com.tactical.bodycam.agent.services.BodycamStreamingService

class MainActivity : AppCompatActivity(), BodycamStreamingService.AgentListener {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: AgentPreferences
    private var streamingService: BodycamStreamingService? = null
    private var bound = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val camera = granted[Manifest.permission.CAMERA] == true
        val microphone = granted[Manifest.permission.RECORD_AUDIO] == true
        if (camera && microphone) {
            startFieldStream()
        } else {
            binding.status.text = "Camera and microphone permission are required."
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            streamingService = (service as BodycamStreamingService.LocalBinder).service
            streamingService?.setListener(this@MainActivity)
            bound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            streamingService = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        preferences = AgentPreferences(this)
        binding.hostInput.setText(preferences.host)
        binding.pathInput.setText(preferences.path)
        binding.mqttInput.setText(preferences.mqttBroker)
        binding.startButton.setOnClickListener { requestStreamPermissions() }
        binding.stopButton.setOnClickListener { stopFieldStream() }
    }

    override fun onStart() {
        super.onStart()
        if (preferences.shouldRun) startFieldStream()
    }

    override fun onStop() {
        streamingService?.setListener(null)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        streamingService = null
        super.onStop()
    }

    override fun onState(banner: String, status: String) {
        runOnUiThread {
            binding.banner.text = banner
            binding.status.text = status
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event == null || event.repeatCount == 0) {
                streamingService?.setPushToTalkActive(true)
                showTacticalBanner(getString(R.string.banner_ptt))
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            streamingService?.setPushToTalkActive(false)
            showTacticalBanner(getString(R.string.banner_standby))
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun showTacticalBanner(text: String) {
        binding.banner.text = text
    }

    private fun requestStreamPermissions() {
        saveFields()
        if (preferences.host.isBlank()) {
            binding.status.text = "Enter the PC address shown on the dispatch page."
            return
        }
        val needed = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startFieldStream() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startFieldStream() {
        saveFields()
        preferences.shouldRun = true
        val intent = Intent(this, BodycamStreamingService::class.java).apply {
            action = BodycamStreamingService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindAgent()
        binding.status.text = "Publishing ${preferences.rtspUrl()}"
    }

    private fun stopFieldStream() {
        saveFields()
        preferences.shouldRun = false
        val intent = Intent(this, BodycamStreamingService::class.java).apply {
            action = BodycamStreamingService.ACTION_STOP
        }
        startService(intent)
        showTacticalBanner(getString(R.string.banner_standby))
    }

    private fun bindAgent() {
        if (bound) return
        bindService(
            Intent(this, BodycamStreamingService::class.java),
            connection,
            BIND_AUTO_CREATE
        )
    }

    private fun saveFields() {
        preferences.host = binding.hostInput.text?.toString().orEmpty()
        preferences.path = binding.pathInput.text?.toString().orEmpty().ifBlank { AgentPreferences.DEFAULT_PATH }
        preferences.mqttBroker = binding.mqttInput.text?.toString().orEmpty()
    }
}
