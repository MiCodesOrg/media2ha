package fr.micodes.media2ha

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import android.widget.Toast
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
    private lateinit var statusText: TextView
    private lateinit var permissionButton: Button
    private lateinit var adbInstructions: TextView
    private lateinit var testResultText: TextView

    private var loading = true
    private var deviceIdTouched = false
    private var updatingDeviceId = false
    private var testing = false
    private var testManager: MqttClientManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        config = Media2HaConfig(this)

        hostEdit = findViewById(R.id.mqtt_host_edit)
        portEdit = findViewById(R.id.mqtt_port_edit)
        authSwitch = findViewById(R.id.mqtt_auth_switch)
        authContainer = findViewById(R.id.mqtt_auth_container)
        usernameEdit = findViewById(R.id.mqtt_username_edit)
        passwordEdit = findViewById(R.id.mqtt_password_edit)
        deviceNameEdit = findViewById(R.id.mqtt_device_name_edit)
        deviceIdEdit = findViewById(R.id.mqtt_device_id_edit)
        prefixEdit = findViewById(R.id.mqtt_prefix_edit)
        statusText = findViewById(R.id.status_text)
        permissionButton = findViewById(R.id.permission_button)
        adbInstructions = findViewById(R.id.adb_instructions)
        testResultText = findViewById(R.id.test_result_text)

        loadConfig()
        bindListeners()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        testManager?.disconnect()
        testManager = null
        super.onDestroy()
    }

    private fun loadConfig() {
        loading = true
        hostEdit.setText(config.host)
        portEdit.setText(config.port.toString())
        authSwitch.isChecked = config.useAuth
        authContainer.visibility = if (config.useAuth) LinearLayout.VISIBLE else LinearLayout.GONE
        usernameEdit.setText(config.username)
        passwordEdit.setText(config.password)
        deviceNameEdit.setText(config.deviceName)
        deviceIdEdit.setText(config.deviceId)
        prefixEdit.setText(config.discoveryPrefix)
        loading = false
    }

    private fun bindListeners() {
        authSwitch.setOnCheckedChangeListener { _, checked ->
            authContainer.visibility = if (checked) LinearLayout.VISIBLE else LinearLayout.GONE
        }

        deviceNameEdit.doAfterTextChanged { text ->
            if (loading || deviceIdTouched) return@doAfterTextChanged
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
                NotificationListenerService.requestRebind(
                    ComponentName(this, MediaSessionListenerService::class.java)
                )
                toast(getString(R.string.saved))
                updateStatus()
            }
        }

        findViewById<Button>(R.id.test_mqtt_button).setOnClickListener { testConnection() }
        findViewById<Button>(R.id.remove_ha_button).setOnClickListener { removeFromHomeAssistant() }
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
        val id = deviceIdEdit.text.toString().trim()
        config.deviceId = if (id.isBlank()) Media2HaConfig.defaultDeviceId(this, name) else id
        config.discoveryPrefix = prefixEdit.text.toString()
        return true
    }

    private fun testConnection() {
        if (testing) return
        if (!persistConfig()) return
        testing = true
        val topics = Topics(config.deviceId, config.discoveryPrefix)
        testResultText.text = getString(R.string.testing)
        testResultText.setTextColor(getColor(R.color.text_secondary))

        testManager = MqttClientManager(
            serverUri = config.serverUri(),
            clientId = MqttClientManager.transientClientId(config.deviceId),
            username = if (config.useAuth) config.username else null,
            password = if (config.useAuth) config.password.toCharArray() else null,
            willTopic = topics.availability,
            listener = object : MqttClientManager.Listener {
                override fun onConnected(reconnect: Boolean) {
                    testManager?.publish(topics.availability, Topics.PAYLOAD_ONLINE, true)
                    testManager?.publish(topics.discovery, DiscoveryPayload.build(config, topics), true)
                    runOnUiThread {
                        testResultText.text = getString(R.string.test_ok)
                        testResultText.setTextColor(getColor(R.color.status_ok))
                        testing = false
                    }
                    testManager?.disconnect()
                    testManager = null
                }

                override fun onConnectionLost(cause: Throwable?) {
                    runOnUiThread {
                        testResultText.text = getString(R.string.test_failed, cause?.message ?: "")
                        testResultText.setTextColor(getColor(R.color.status_error))
                        testing = false
                    }
                }

                override fun onMessage(topic: String, payload: String) {}

                override fun onLog(message: String, isError: Boolean) {
                    if (isError) {
                        runOnUiThread {
                            testResultText.text = message
                            testResultText.setTextColor(getColor(R.color.status_error))
                            testing = false
                        }
                    }
                }
            }
        )
        testManager?.connect()
    }

    private fun removeFromHomeAssistant() {
        if (!config.isConfigured) {
            toast(getString(R.string.host_required))
            return
        }
        val topics = Topics(config.deviceId, config.discoveryPrefix)
        val manager = MqttClientManager(
            serverUri = config.serverUri(),
            clientId = MqttClientManager.transientClientId(config.deviceId),
            username = if (config.useAuth) config.username else null,
            password = if (config.useAuth) config.password.toCharArray() else null,
            willTopic = topics.availability,
            listener = object : MqttClientManager.Listener {
                override fun onConnected(reconnect: Boolean) {
                    testManager?.publish(topics.discovery, "", true)
                    testManager?.publish(topics.availability, Topics.PAYLOAD_OFFLINE, true)
                    runOnUiThread { toast(getString(R.string.removed)) }
                    testManager?.disconnect()
                    testManager = null
                }

                override fun onConnectionLost(cause: Throwable?) {}
                override fun onMessage(topic: String, payload: String) {}
                override fun onLog(message: String, isError: Boolean) {}
            }
        )
        testManager?.disconnect()
        testManager = manager
        manager.connect()
    }

    private fun updateStatus() {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )?.contains(packageName) == true

        when {
            !enabled -> {
                statusText.text = getString(R.string.status_permission)
                statusText.setTextColor(getColor(R.color.status_error))
                permissionButton.visibility = View.VISIBLE
                adbInstructions.visibility = View.VISIBLE
            }
            !config.isConfigured -> {
                statusText.text = getString(R.string.status_unconfigured)
                statusText.setTextColor(getColor(R.color.text_secondary))
                permissionButton.visibility = View.GONE
                adbInstructions.visibility = View.GONE
            }
            else -> {
                statusText.text = config.lastStatus().ifBlank { getString(R.string.status_waiting) }
                statusText.setTextColor(
                    getColor(if (config.lastStatusIsError()) R.color.status_error else R.color.status_ok)
                )
                permissionButton.visibility = View.GONE
                adbInstructions.visibility = View.GONE
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
