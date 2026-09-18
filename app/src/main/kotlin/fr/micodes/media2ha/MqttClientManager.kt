package fr.micodes.media2ha

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.Executors

/**
 * Thin wrapper around the bare Paho mqttv3 client.
 *
 * Publishes and subscriptions are serialised on a single-thread executor so the main
 * thread is never blocked by network I/O. Reconnection is handled by Paho; every
 * (re)connection is surfaced through [Listener.onConnected] so the owner can resubscribe
 * and republish its retained state.
 */
class MqttClientManager(
    private val serverUri: String,
    private val clientId: String,
    private val username: String?,
    private val password: CharArray?,
    private val willTopic: String,
    private val listener: Listener
) {

    interface Listener {
        fun onConnected(reconnect: Boolean)
        fun onConnectionLost(cause: Throwable?)
        fun onMessage(topic: String, payload: String)
        fun onLog(message: String, isError: Boolean)
    }

    @Volatile
    var isConnected: Boolean = false
        private set

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "media2ha-mqtt").apply { isDaemon = true }
    }
    private var client: MqttClient? = null
    private var closed = false

    fun connect() {
        executor.execute { doConnect() }
    }

    private fun doConnect() {
        if (closed) return
        try {
            val newClient = MqttClient(serverUri, clientId, MemoryPersistence())
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = false
                connectionTimeout = 10
                keepAliveInterval = 60
                maxInflight = 10
                if (!username.isNullOrBlank()) {
                    userName = username
                    this.password = password
                }
                setWill(willTopic, Topics.PAYLOAD_OFFLINE.toByteArray(), 1, true)
            }

            newClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    isConnected = true
                    listener.onLog("Connecté à $serverURI${if (reconnect) " (reconnexion)" else ""}", false)
                    listener.onConnected(reconnect)
                }

                override fun connectionLost(cause: Throwable?) {
                    isConnected = false
                    listener.onConnectionLost(cause)
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic != null && message != null) {
                        listener.onMessage(topic, String(message.payload))
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

            client = newClient
            newClient.connect(options)
        } catch (e: MqttException) {
            listener.onLog("Erreur MQTT: ${e.message}", true)
        } catch (e: Exception) {
            listener.onLog("Erreur MQTT: ${e.message}", true)
        }
    }

    fun publish(topic: String, payload: String, retained: Boolean) {
        executor.execute {
            val c = client ?: return@execute
            if (!c.isConnected) return@execute
            try {
                val message = MqttMessage(payload.toByteArray()).apply {
                    qos = 1
                    isRetained = retained
                }
                c.publish(topic, message)
            } catch (e: Exception) {
                listener.onLog("Échec publication: ${e.message}", true)
            }
        }
    }

    fun subscribe(topic: String, qos: Int = 1) {
        executor.execute {
            val c = client ?: return@execute
            try {
                if (c.isConnected) c.subscribe(topic, qos)
            } catch (e: Exception) {
                listener.onLog("Échec abonnement: ${e.message}", true)
            }
        }
    }

    fun disconnect() {
        closed = true
        executor.execute {
            try {
                client?.takeIf { it.isConnected }?.disconnect()
                client?.close()
            } catch (e: Exception) {
                Log.w(TAG, "disconnect: ${e.message}")
            } finally {
                client = null
                isConnected = false
            }
        }
        executor.shutdown()
    }

    companion object {
        private const val TAG = "MqttClientManager"

        /** Stable id so the broker session survives restarts (used with cleanSession=false). */
        fun stableClientId(deviceId: String): String = "media2ha_${deviceId.take(40)}"

        /** Unique id for short-lived test/removal connections, so they don't evict the service. */
        fun transientClientId(deviceId: String): String {
            val random = (1000..9999).random()
            return "media2ha_tmp_${deviceId.take(32)}_$random"
        }
    }
}
