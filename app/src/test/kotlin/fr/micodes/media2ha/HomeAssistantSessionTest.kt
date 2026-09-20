package fr.micodes.media2ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantSessionTest {

    private val discovery = Discovery("dev", "Living Room TV", "hass")

    @Test
    fun `announce publishes availability then discovery`() {
        val publishes = HomeAssistantSession.announcePublishes(discovery)

        assertEquals(
            listOf(discovery.topics.availability, discovery.topics.discovery),
            publishes.map { it.topic },
        )
        assertEquals("online", publishes[0].payload)
        assertEquals(discovery.payload(), publishes[1].payload)
        assertTrue(publishes.all { it.retained })
    }

    @Test
    fun `withdraw clears discovery and marks offline`() {
        val publishes = HomeAssistantSession.withdrawPublishes(discovery)

        assertEquals(
            listOf(discovery.topics.discovery, discovery.topics.availability),
            publishes.map { it.topic },
        )
        assertEquals("", publishes[0].payload)
        assertEquals("offline", publishes[1].payload)
        assertTrue(publishes.all { it.retained })
    }
}
