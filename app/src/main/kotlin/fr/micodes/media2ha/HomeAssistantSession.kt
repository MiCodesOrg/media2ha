package fr.micodes.media2ha

import android.os.Build

/**
 * The application seam for the Home Assistant link: builds the MQTT connection from the
 * configuration and speaks the discovery protocol. Callers no longer know the broker URI,
 * the client id, the credentials, or which topics add/remove the entity.
 */
class HomeAssistantSession private constructor(
    val discovery: Discovery,
    private val mqtt: MqttClientManager,
) {

    val topics get() = discovery.topics

    fun connect() = mqtt.connect()
    fun reconnectIfNeeded() = mqtt.reconnectIfNeeded()
    fun publish(topic: String, payload: String, retained: Boolean) = mqtt.publish(topic, payload, retained)
    fun subscribe(topic: String, qos: Int = 1) = mqtt.subscribe(topic, qos)
    fun disconnect() = mqtt.disconnect()

    fun announce() {
        announcePublishes(discovery).forEach { publish(it.topic, it.payload, it.retained) }
    }

    fun withdraw() {
        withdrawPublishes(discovery).forEach { publish(it.topic, it.payload, it.retained) }
    }

    companion object {
        /** Long-lived session owned by the service (stable client id, cleanSession=false). */
        fun forService(config: Media2HaConfig, listener: MqttClientManager.Listener): HomeAssistantSession =
            build(config, MqttClientManager.stableClientId(config.deviceId), listener)

        /** Short-lived session for the UI's test/remove actions (distinct client id). */
        fun transient(config: Media2HaConfig, listener: MqttClientManager.Listener): HomeAssistantSession =
            build(config, MqttClientManager.transientClientId(config.deviceId), listener)

        fun announcePublishes(discovery: Discovery): List<Publish> = listOf(
            Publish(discovery.topics.availability, Topics.PAYLOAD_ONLINE, true),
            Publish(discovery.topics.discovery, discovery.payload(), true),
        )

        fun withdrawPublishes(discovery: Discovery): List<Publish> = listOf(
            Publish(discovery.topics.discovery, "", true),
            Publish(discovery.topics.availability, Topics.PAYLOAD_OFFLINE, true),
        )

        /** Clears every retained topic of a device id left behind by a rename. */
        fun retirePublishes(deviceId: String, discoveryPrefix: String): List<Publish> {
            val topics = Topics(deviceId, discoveryPrefix)
            return listOf(
                topics.discovery,
                topics.availability,
                topics.state,
                topics.title,
                topics.artist,
                topics.album,
                topics.mediatype,
                topics.duration,
                topics.position,
                topics.volume,
                topics.albumArt,
                topics.mute,
                topics.source,
                topics.summary,
                topics.season,
                topics.episode,
                topics.series,
                topics.year,
            ).map { Publish(it, "", true) }
        }

        private fun build(
            config: Media2HaConfig,
            clientId: String,
            listener: MqttClientManager.Listener,
        ): HomeAssistantSession {
            val discovery = Discovery(
                deviceId = config.deviceId,
                deviceName = config.deviceName,
                discoveryPrefix = config.discoveryPrefix,
                model = Build.MODEL ?: "Android",
                swVersion = BuildConfig.VERSION_NAME,
            )
            val mqtt = MqttClientManager(
                serverUri = config.serverUri(),
                clientId = clientId,
                username = if (config.useAuth) config.username else null,
                password = if (config.useAuth) config.password.toCharArray() else null,
                willTopic = discovery.topics.availability,
                listener = listener,
            )
            return HomeAssistantSession(discovery, mqtt)
        }
    }
}
