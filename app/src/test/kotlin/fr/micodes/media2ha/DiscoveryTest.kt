package fr.micodes.media2ha

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryTest {

    private val discovery = Discovery(
        deviceId = "dev",
        deviceName = "Living Room TV",
        discoveryPrefix = "hass",
        model = "Pixel",
        swVersion = "9.9",
    )

    @Test
    fun `topics follow the device id and the prefix`() {
        assertEquals("media2ha/dev/state", discovery.topics.state)
        assertEquals("media2ha/dev/cmd/seek", discovery.topics.cmdSeek)
        assertEquals("hass/media_player/dev/config", discovery.topics.discovery)
    }

    @Test
    fun `payload carries the naming scheme and the device block`() {
        val payload = JSONObject(discovery.payload())

        assertEquals("Living Room TV", payload.getString("name"))
        assertEquals(discovery.topics.availability, payload.getJSONObject("availability").getString("topic"))
        assertEquals(discovery.topics.state, payload.getString("state_state_topic"))
        assertEquals(discovery.topics.mute, payload.getString("state_mute_topic"))
        assertEquals(discovery.topics.source, payload.getString("state_source_topic"))
        assertEquals(discovery.topics.summary, payload.getString("state_summary_topic"))
        assertEquals(discovery.topics.season, payload.getString("state_season_topic"))
        assertEquals(discovery.topics.episode, payload.getString("state_episode_topic"))
        assertEquals(discovery.topics.series, payload.getString("state_series_topic"))
        assertEquals(discovery.topics.year, payload.getString("state_year_topic"))
        assertEquals(discovery.topics.cmdTurnOff, payload.getString("command_turn_off_topic"))
        assertEquals("off", payload.getString("command_turn_off_payload"))

        val device = payload.getJSONObject("device")
        assertEquals("dev", device.getJSONArray("identifiers").getString(0))
        assertEquals("Living Room TV", device.getString("name"))
        assertEquals("Pixel", device.getString("model"))
        assertEquals("9.9", device.getString("sw_version"))
        assertEquals("micodes", device.getString("manufacturer"))
    }
}
