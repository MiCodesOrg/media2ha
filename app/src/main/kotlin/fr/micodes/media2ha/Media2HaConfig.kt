package fr.micodes.media2ha

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale

class Media2HaConfig(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var useAuth: Boolean
        get() = prefs.getBoolean(KEY_USE_AUTH, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_AUTH, value).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    /** MQTT discovery prefix configured in Home Assistant (default `homeassistant`). */
    var discoveryPrefix: String
        get() = prefs.getString(KEY_DISCOVERY_PREFIX, null)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DISCOVERY_PREFIX
        set(value) = prefs.edit()
            .putString(KEY_DISCOVERY_PREFIX, value.trim().ifBlank { DEFAULT_DISCOVERY_PREFIX })
            .apply()

    var deviceName: String
        get() {
            val stored = prefs.getString(KEY_DEVICE_NAME, null)
            if (!stored.isNullOrBlank()) return stored
            return Build.MODEL?.ifBlank { DEVICE_NAME_FALLBACK } ?: DEVICE_NAME_FALLBACK
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_NAME, value.trim()).apply()

    var deviceId: String
        get() {
            val stored = prefs.getString(KEY_DEVICE_ID, null)
            if (!stored.isNullOrBlank()) return stored
            return defaultDeviceId(context, deviceName)
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, slugify(value)).apply()

    /** Id left behind by a rename, whose retained topics are cleared on the next connect. */
    var retiredDeviceId: String
        get() = prefs.getString(KEY_RETIRED_DEVICE_ID, "") ?: ""
        set(value) {
            // commit(), not apply(): the cleanup must survive a background process kill.
            prefs.edit().putString(KEY_RETIRED_DEVICE_ID, value.trim()).commit()
        }

    val isConfigured: Boolean
        get() = host.isNotBlank()

    fun serverUri(): String = "tcp://$host:$port"

    fun setStatus(message: String, isError: Boolean) {
        prefs.edit()
            .putString(KEY_LAST_STATUS, message)
            .putBoolean(KEY_LAST_STATUS_ERROR, isError)
            .apply()
    }

    fun lastStatus(): String = prefs.getString(KEY_LAST_STATUS, "") ?: ""

    fun lastStatusIsError(): Boolean = prefs.getBoolean(KEY_LAST_STATUS_ERROR, false)

    companion object {
        private const val PREFS = "media2ha"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_USE_AUTH = "use_auth"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_DISCOVERY_PREFIX = "discovery_prefix"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_RETIRED_DEVICE_ID = "retired_device_id"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LAST_STATUS_ERROR = "last_status_error"

        const val DEFAULT_PORT = 1883
        const val DEFAULT_DISCOVERY_PREFIX = "homeassistant"
        private const val DEVICE_NAME_FALLBACK = "Android TV"

        fun defaultDeviceId(context: Context, name: String): String {
            val slug = slugify(name).ifBlank { "media2ha" }
            return "${slug}_${hardwareSuffix(context)}"
        }

        fun slugify(value: String): String {
            val lowered = value.lowercase(Locale.US)
            val sb = StringBuilder(lowered.length)
            var lastUnderscore = false
            for (ch in lowered) {
                val ok = ch in 'a'..'z' || ch in '0'..'9'
                if (ok) {
                    sb.append(ch)
                    lastUnderscore = false
                } else if (!lastUnderscore) {
                    sb.append('_')
                    lastUnderscore = true
                }
            }
            return sb.toString().trim('_')
        }

        private fun hardwareSuffix(context: Context): String {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            ) ?: Build.FINGERPRINT ?: "0000"
            val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
            return digest.take(2).joinToString("") { "%02x".format(it) }
        }
    }
}
