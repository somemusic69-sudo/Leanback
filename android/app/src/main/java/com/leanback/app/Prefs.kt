package com.leanback.app

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.UUID

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("leanback_prefs", Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            var id = prefs.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    val deviceName: String
        get() {
            val model = Build.MODEL?.trim().orEmpty()
            val brand = Build.MANUFACTURER?.trim().orEmpty()
            return when {
                model.isEmpty() -> "Android Phone"
                brand.isNotEmpty() && !model.startsWith(brand, ignoreCase = true) ->
                    "${brand.replaceFirstChar { it.uppercase() }} $model"
                else -> model
            }
        }

    var lastHost: String?
        get() = prefs.getString(KEY_LAST_HOST, null)
        set(value) = prefs.edit().putString(KEY_LAST_HOST, value).apply()

    var lastPort: Int
        get() = prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_LAST_PORT, value).apply()

    var lastServerName: String?
        get() = prefs.getString(KEY_LAST_SERVER_NAME, null)
        set(value) = prefs.edit().putString(KEY_LAST_SERVER_NAME, value).apply()

    var sensitivity: Float
        get() = prefs.getFloat(KEY_SENSITIVITY, 1.25f)
        set(value) = prefs.edit().putFloat(KEY_SENSITIVITY, value.coerceIn(0.4f, 3.5f)).apply()

    var naturalScroll: Boolean
        get() = prefs.getBoolean(KEY_NATURAL_SCROLL, true)
        set(value) = prefs.edit().putBoolean(KEY_NATURAL_SCROLL, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var buttonClickMode: Boolean
        get() = prefs.getBoolean(KEY_BUTTON_CLICK_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_BUTTON_CLICK_MODE, value).apply()

    var buttonLayoutStacked: Boolean
        get() = prefs.getBoolean(KEY_BUTTON_LAYOUT_STACKED, false)
        set(value) = prefs.edit().putBoolean(KEY_BUTTON_LAYOUT_STACKED, value).apply()

    fun getToken(host: String, port: Int): String? {
        val map = readTokenMap()
        return map.optString("$host:$port", "").takeIf { it.isNotEmpty() }
    }

    fun saveToken(host: String, port: Int, token: String) {
        val map = readTokenMap()
        map.put("$host:$port", token)
        prefs.edit().putString(KEY_TOKENS, map.toString()).apply()
    }

    fun clearSavedServer() {
        val host = lastHost
        val port = lastPort
        val editor = prefs.edit()
            .remove(KEY_LAST_HOST)
            .remove(KEY_LAST_PORT)
            .remove(KEY_LAST_SERVER_NAME)
        if (!host.isNullOrBlank()) {
            val map = readTokenMap()
            map.remove("$host:$port")
            editor.putString(KEY_TOKENS, map.toString())
        }
        editor.apply()
    }

    private fun readTokenMap(): JSONObject {
        val raw = prefs.getString(KEY_TOKENS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    companion object {
        const val DEFAULT_PORT = 7890
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_HOST = "last_host"
        private const val KEY_LAST_PORT = "last_port"
        private const val KEY_LAST_SERVER_NAME = "last_server_name"
        private const val KEY_TOKENS = "tokens_json"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_NATURAL_SCROLL = "natural_scroll"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_BUTTON_CLICK_MODE = "button_click_mode"
        private const val KEY_BUTTON_LAYOUT_STACKED = "button_layout_stacked"
    }
}
