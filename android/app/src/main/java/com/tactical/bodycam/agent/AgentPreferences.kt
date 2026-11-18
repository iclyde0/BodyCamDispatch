package com.tactical.bodycam.agent

import android.content.Context

class AgentPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var path: String
        get() = prefs.getString(KEY_PATH, DEFAULT_PATH) ?: DEFAULT_PATH
        set(value) = prefs.edit().putString(KEY_PATH, value.trim().trim('/')).apply()

    var mqttBroker: String
        get() = prefs.getString(KEY_MQTT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MQTT, value.trim()).apply()

    var shouldRun: Boolean
        get() = prefs.getBoolean(KEY_RUN, false)
        set(value) = prefs.edit().putBoolean(KEY_RUN, value).apply()

    fun rtspUrl(): String = "rtsp://${host}:$RTSP_PORT/${path}"

    fun dispatchWhepUrl(): String = "http://${host}:$WEBRTC_PORT/$DISPATCH_PATH/whep"

    fun dispatchApiUrl(): String = "http://${host}:$API_PORT/v3/paths/get/$DISPATCH_PATH"

    fun commandTopic(): String = "bodycam/${path}/cmd"

    fun telemetryTopic(): String = "bodycam/${path}/telemetry"

    fun pttTopic(): String = "bodycam/${path}/ptt"

    companion object {
        private const val PREFS = "bodycam_agent"
        private const val KEY_HOST = "host"
        private const val KEY_PATH = "path"
        private const val KEY_MQTT = "mqtt"
        private const val KEY_RUN = "should_run"
        const val DEFAULT_PATH = "cam-alpha-09"
        private const val RTSP_PORT = 8554
        private const val WEBRTC_PORT = 8889
        private const val API_PORT = 9997
        const val DISPATCH_PATH = "dispatch"
    }
}
