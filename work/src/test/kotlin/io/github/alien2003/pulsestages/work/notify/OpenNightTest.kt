package io.github.alien2003.pulsestages.work.notify

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which launches of the app open a night ([OpenNight.nightToOpen]): only a fresh one by the notice's intent; and the
 * request codes that keep the pending intents of the nights apart.
 */
class OpenNightTest {
    @Test
    fun `the notice's intent opens its night`() {
        assertEquals("ps:0a", OpenNight.nightToOpen("ps:0a", OpenNight.FLAGS, restored = false))
        assertEquals("ps:0a", OpenNight.nightToOpen("ps:0a", flags = 0, restored = false))
    }

    @Test
    fun `an intent without the night, such as the launcher's, opens no night`() {
        assertNull(OpenNight.nightToOpen(null, Intent.FLAG_ACTIVITY_NEW_TASK, restored = false))
    }

    @Test
    fun `a restored activity opens no night again`() {
        assertNull(OpenNight.nightToOpen("ps:0a", OpenNight.FLAGS, restored = true))
    }

    @Test
    fun `a relaunch from Recents opens no night again`() {
        val flags = OpenNight.FLAGS or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY

        assertNull(OpenNight.nightToOpen("ps:0a", flags, restored = false))
    }

    @Test
    fun `a blank id opens no night`() {
        assertNull(OpenNight.nightToOpen("", OpenNight.FLAGS, restored = false))
        assertNull(OpenNight.nightToOpen("  ", OpenNight.FLAGS, restored = false))
    }

    @Test
    fun `each night has its own request code, never the progress notifications' 0`() {
        assertEquals(OpenNight.requestCodeOf("ps:0a"), OpenNight.requestCodeOf("ps:0a"))
        assertNotEquals(OpenNight.requestCodeOf("ps:0a"), OpenNight.requestCodeOf("ps:0b"))
        assertEquals(0, "".hashCode())
        assertEquals(1, OpenNight.requestCodeOf(""), "an id that hashes to 0")
    }
}
