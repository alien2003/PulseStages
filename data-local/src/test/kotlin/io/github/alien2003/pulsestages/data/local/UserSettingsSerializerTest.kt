package io.github.alien2003.pulsestages.data.local

import androidx.datastore.core.CorruptionException
import io.github.alien2003.pulsestages.data.local.settings.UserSettings
import io.github.alien2003.pulsestages.data.local.settings.UserSettingsSerializer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class UserSettingsSerializerTest {
    @Test
    fun `round-trips settings`() =
        runTest {
            val settings = UserSettings(autoProcessEnabled = false)
            val bytes = ByteArrayOutputStream().also { UserSettingsSerializer.writeTo(settings, it) }.toByteArray()

            val decoded = UserSettingsSerializer.readFrom(ByteArrayInputStream(bytes))

            assertEquals(settings, decoded)
        }

    @Test
    fun `corrupt input raises CorruptionException`() =
        runTest {
            assertThrows<CorruptionException> {
                UserSettingsSerializer.readFrom(ByteArrayInputStream("{not json".encodeToByteArray()))
            }
        }
}
