package fr.micodes.media2ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttClientManagerTest {

    @Test
    fun `retry backoff grows then caps at 60 seconds`() {
        assertEquals(5, MqttClientManager.retryDelaySeconds(0))
        assertEquals(10, MqttClientManager.retryDelaySeconds(1))
        assertEquals(20, MqttClientManager.retryDelaySeconds(2))
        assertEquals(40, MqttClientManager.retryDelaySeconds(3))
        assertEquals(60, MqttClientManager.retryDelaySeconds(4))
        assertEquals(60, MqttClientManager.retryDelaySeconds(10))
    }

    @Test
    fun `retry backoff tolerates a negative attempt`() {
        assertEquals(5, MqttClientManager.retryDelaySeconds(-3))
    }

    @Test
    fun `work is accepted only while the session is open`() {
        assertTrue(MqttClientManager.acceptsWork(closed = false, executorShutdown = false))
        assertFalse(MqttClientManager.acceptsWork(closed = true, executorShutdown = false))
        assertFalse(MqttClientManager.acceptsWork(closed = false, executorShutdown = true))
    }
}
