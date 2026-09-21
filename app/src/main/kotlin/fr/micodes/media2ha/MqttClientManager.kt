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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around the bare Paho mqttv3 client.
 *
 * Publishes and subscriptions are serialised on a single-thread executor so the main
 * thread is never blocked by network I/O. Reconnection is handled by Paho; every
 * (re)connection is surfaced through [Listener.onConnected] so the owner can resubscribe
 * and republish its retained state.
 *
 * Paho's automatic reconnect only covers losing an already-established connection, so a
 * failed *initial* connect (typical when the network is not up yet at boot) is retried here
 * with a capped backoff.
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

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "media2ha-mqtt").apply { isDaemon = true }
    }
    private var client: MqttClient? = null
    private var closed = false
    private var connecting = false
    private var retryAttempt = 0
    private var retryScheduled = false

    fun connect() {
        retryAttempt = 0
        retryScheduled = false
        executor.execute { doConnect() }
    }

    /** Retry right now if a previous attempt failed and nothing is scheduled. */
    fun reconnectIfNeeded() {
        if (closed || isConnected || connecting || retryScheduled) return
        executor.execute { if (!closed && !isConnected) doConnect() }
    }

    private fun doConnect() {
        if (closed || connecting) return
        connecting = true
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
                    retryAttempt = 0
                    retryScheduled = false
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
            scheduleRetry()
        } catch (e: Exception) {
            listener.onLog("Erreur MQTT: ${e.message}", true)
            scheduleRetry()
        } finally {
            connecting = false
        }
    }

    private fun scheduleRetry() {
        if (closed || retryScheduled) return
        val delay = retryDelaySeconds(retryAttempt)
        retryAttempt += 1
        retryScheduled = true
        listener.onLog("Nouvelle tentative dans ${delay}s", false)
        executor.schedule({
            retryScheduled = false
            doConnect()
        }, delay.toLong(), TimeUnit.SECONDS)
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
        if (executor.isShutdown) return
        // Graceful: queued publishes run before the disconnect task, instead of being
        // cancelled by shutdownNow() while in flight (which throws MqttException).
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

        /** Capped exponential backoff for a failed initial connect: 5, 10, 20, 40, 60, 60… */
        fun retryDelaySeconds(attempt: Int): Int =
            minOf(5 shl minOf(attempt.coerceAtLeast(0), 5), 60)
    }
}
