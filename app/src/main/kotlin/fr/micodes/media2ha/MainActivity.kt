package fr.micodes.media2ha

import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.widget.doAfterTextChanged

class MainActivity : AppCompatActivity() {

    private lateinit var config: Media2HaConfig

    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var authSwitch: SwitchCompat
    private lateinit var authContainer: LinearLayout
    private lateinit var usernameEdit: EditText
    private lateinit var passwordEdit: EditText
    private lateinit var deviceNameEdit: EditText
    private lateinit var deviceIdEdit: EditText
    private lateinit var prefixEdit: EditText

    private lateinit var permissionCard: LinearLayout
    private lateinit var statusCard: LinearLayout
    private lateinit var formCard: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var statusContext: TextView
    private lateinit var statusIcon: ImageView
    private lateinit var advancedToggle: Button
    private lateinit var advancedContainer: LinearLayout
    private lateinit var cancelButton: Button
    private lateinit var testResultText: TextView

    private var loading = true
    private var deviceIdTouched = false
    private var updatingDeviceId = false
    private var editing = false
    private var testing = false
    private var testSession: HomeAssistantSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = Media2HaConfig(this)

        bindViews()
        loadConfig()
        bindListeners()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        testSession?.disconnect()
        testSession = null
        super.onDestroy()
    }

    private fun bindViews() {
        hostEdit = findViewById(R.id.mqtt_host_edit)
        portEdit = findViewById(R.id.mqtt_port_edit)
        authSwitch = findViewById(R.id.mqtt_auth_switch)
        authContainer = findViewById(R.id.mqtt_auth_container)
        usernameEdit = findViewById(R.id.mqtt_username_edit)
        passwordEdit = findViewById(R.id.mqtt_password_edit)
        deviceNameEdit = findViewById(R.id.mqtt_device_name_edit)
        deviceIdEdit = findViewById(R.id.mqtt_device_id_edit)
        prefixEdit = findViewById(R.id.mqtt_prefix_edit)

        permissionCard = findViewById(R.id.permission_card)
        statusCard = findViewById(R.id.status_card)
        formCard = findViewById(R.id.form_card)
        statusText = findViewById(R.id.status_text)
        statusContext = findViewById(R.id.status_context)
        statusIcon = findViewById(R.id.status_icon)
        advancedToggle = findViewById(R.id.advanced_toggle)
        advancedContainer = findViewById(R.id.advanced_container)
        cancelButton = findViewById(R.id.cancel_button)
        testResultText = findViewById(R.id.test_result_text)
    }

    private fun loadConfig() {
        loading = true
        hostEdit.setText(config.host)
        portEdit.setText(config.port.toString())
        authSwitch.isChecked = config.useAuth
        authContainer.visibility = if (config.useAuth) View.VISIBLE else View.GONE
        usernameEdit.setText(config.username)
        passwordEdit.setText(config.password)
        deviceNameEdit.setText(config.deviceName)
        deviceIdEdit.setText(config.deviceId)
        prefixEdit.setText(config.discoveryPrefix)
        loading = false
    }

    private fun bindListeners() {
        authSwitch.setOnCheckedChangeListener { _, checked ->
            authContainer.visibility = if (checked) View.VISIBLE else View.GONE
        }

        deviceNameEdit.doAfterTextChanged { text ->
            if (loading || deviceIdTouched) return@doAfterTextChanged
            // Keep an existing id stable: renaming must not create a new entity.
            if (deviceIdEdit.text.toString().isNotBlank()) return@doAfterTextChanged
            updatingDeviceId = true
            deviceIdEdit.setText(Media2HaConfig.defaultDeviceId(this, text?.toString().orEmpty()))
            updatingDeviceId = false
        }

        deviceIdEdit.doAfterTextChanged {
            if (!loading && !updatingDeviceId) deviceIdTouched = true
        }

        findViewById<Button>(R.id.permission_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.save_config_button).setOnClickListener {
            if (persistConfig()) {
                editing = false
                testResultText.text = ""
                toast(getString(R.string.saved))
                updateStatus()
            }
        }

        findViewById<Button>(R.id.modify_button).setOnClickListener {
            editing = true
            loadConfig()
            updateStatus()
            hostEdit.requestFocus()
        }

        cancelButton.setOnClickListener {
            editing = false
            loadConfig()
            testResultText.text = ""
            updateStatus()
        }

        advancedToggle.setOnClickListener {
            setAdvancedVisible(advancedContainer.visibility != View.VISIBLE)
        }

        findViewById<Button>(R.id.test_mqtt_button).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.test_button_hero).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.unpublish_button).setOnClickListener { confirmUnpublish() }
    }

    private fun setAdvancedVisible(visible: Boolean) {
        advancedContainer.visibility = if (visible) View.VISIBLE else View.GONE
        advancedToggle.text = getString(if (visible) R.string.advanced_open else R.string.advanced_closed)
    }

    /** Persists the form. Returns false (and warns) when the host is missing. */
    private fun persistConfig(): Boolean {
        val host = hostEdit.text.toString().trim()
        if (host.isBlank()) {
            toast(getString(R.string.host_required))
            return false
        }
        val port = portEdit.text.toString().trim().toIntOrNull() ?: Media2HaConfig.DEFAULT_PORT
        config.host = host
        config.port = port
        config.useAuth = authSwitch.isChecked
        config.username = usernameEdit.text.toString()
        config.password = passwordEdit.text.toString()
        val name = deviceNameEdit.text.toString().trim()
        config.deviceName = name
        val previousId = config.deviceId
        val id = deviceIdEdit.text.toString().trim()
        config.deviceId = if (id.isBlank()) Media2HaConfig.defaultDeviceId(this, name) else id
        if (previousId.isNotBlank() && previousId != config.deviceId) {
            config.retiredDeviceId = previousId
        }
        config.discoveryPrefix = prefixEdit.text.toString()
        reloadService()
        return true
    }

    /**
     * Tells the background service to re-read the config and republish with the current
     * identity. Relying on a listener rebind alone is not enough: when only the device id
     * changed, the running service kept publishing to the previous topics.
     */
    private fun reloadService() {
        startService(
            Intent(this, MediaSessionListenerService::class.java)
                .setAction(MediaSessionListenerService.ACTION_RELOAD)
        )
        NotificationListenerService.requestRebind(
            ComponentName(this, MediaSessionListenerService::class.java)
        )
    }

    private fun updateStatus() {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )?.contains(packageName) == true
        val configured = config.isConfigured

        permissionCard.visibility = if (enabled) View.GONE else View.VISIBLE
        formCard.visibility = if (!configured || editing) View.VISIBLE else View.GONE
        statusCard.visibility = if (configured && !editing) View.VISIBLE else View.GONE
        cancelButton.visibility = if (configured && editing) View.VISIBLE else View.GONE

        val lastStatus = config.lastStatus()
        val status: String
        val colorRes: Int
        when {
            !enabled -> {
                status = getString(R.string.status_permission)
                colorRes = R.color.status_error
            }

            !configured -> {
                status = getString(R.string.status_unconfigured)
                colorRes = R.color.status_warning
            }

            lastStatus.isBlank() -> {
                status = getString(R.string.status_waiting)
                colorRes = R.color.status_warning
            }

            config.lastStatusIsError() -> {
                status = lastStatus
                colorRes = R.color.status_error
            }

            else -> {
                status = lastStatus
                colorRes = R.color.status_ok
            }
        }
        statusText.text = status
        val color = getColor(colorRes)
        statusText.setTextColor(color)
        statusIcon.imageTintList = ColorStateList.valueOf(color)
        statusContext.text = if (configured) "${config.host}:${config.port} · ${config.deviceName}" else ""
    }

    private fun confirmUnpublish() {
        AlertDialog.Builder(this)
            .setTitle(R.string.unpublish_title)
            .setMessage(R.string.unpublish_message)
            .setPositiveButton(R.string.unpublish_confirm) { _, _ -> removeFromHomeAssistant() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun testConnection() {
        if (testing) return
        if (!persistConfig()) return
        testing = true
        testResultText.text = getString(R.string.testing)
        testResultText.setTextColor(getColor(R.color.text_secondary))

        testSession = HomeAssistantSession.transient(config, object : MqttClientManager.Listener {
            override fun onConnected(reconnect: Boolean) {
                testSession?.announce()
                runOnUiThread {
                    testResultText.text = getString(R.string.test_ok)
                    testResultText.setTextColor(getColor(R.color.status_ok))
                    toast(getString(R.string.test_ok))
                    testing = false
                }
                testSession?.disconnect()
                testSession = null
            }

            override fun onConnectionLost(cause: Throwable?) {
                runOnUiThread {
                    val message = getString(R.string.test_failed, cause?.message ?: "")
                    testResultText.text = message
                    testResultText.setTextColor(getColor(R.color.status_error))
                    toast(message)
                    testing = false
                }
            }

            override fun onMessage(topic: String, payload: String) {}

            override fun onLog(message: String, isError: Boolean) {
                if (isError) {
                    runOnUiThread {
                        testResultText.text = message
                        testResultText.setTextColor(getColor(R.color.status_error))
                        toast(message)
                        testing = false
                    }
                }
            }
        })
        testSession?.connect()
    }

    private fun removeFromHomeAssistant() {
        if (!config.isConfigured) {
            toast(getString(R.string.host_required))
            return
        }
        testSession?.disconnect()
        testSession = HomeAssistantSession.transient(config, object : MqttClientManager.Listener {
            override fun onConnected(reconnect: Boolean) {
                testSession?.withdraw()
                runOnUiThread { toast(getString(R.string.removed)) }
                testSession?.disconnect()
                testSession = null
            }

            override fun onConnectionLost(cause: Throwable?) {}
            override fun onMessage(topic: String, payload: String) {}
            override fun onLog(message: String, isError: Boolean) {}
        })
        testSession?.connect()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
