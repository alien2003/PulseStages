package io.github.alien2003.pulsestages.data.hc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HealthPermissionsTest {
    @Test
    fun `permission strings are the documented Health Connect permissions`() {
        assertEquals("android.permission.health.READ_SLEEP", HealthPermissions.READ_SLEEP)
        assertEquals("android.permission.health.WRITE_SLEEP", HealthPermissions.WRITE_SLEEP)
        assertEquals("android.permission.health.READ_HEART_RATE", HealthPermissions.READ_HEART_RATE)
        assertEquals("android.permission.health.READ_STEPS", HealthPermissions.READ_STEPS)
        assertEquals("android.permission.health.READ_OXYGEN_SATURATION", HealthPermissions.READ_OXYGEN_SATURATION)
        assertEquals("android.permission.health.READ_RESPIRATORY_RATE", HealthPermissions.READ_RESPIRATORY_RATE)
        assertEquals("android.permission.health.READ_HEART_RATE_VARIABILITY", HealthPermissions.READ_HEART_RATE_VARIABILITY)
        assertEquals("android.permission.health.READ_HEALTH_DATA_HISTORY", HealthPermissions.READ_HEALTH_DATA_HISTORY)
        assertEquals("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND", HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND)
    }

    @Test
    fun `required, optional and special sets follow the specification`() {
        assertEquals(
            setOf(
                HealthPermissions.READ_SLEEP,
                HealthPermissions.WRITE_SLEEP,
                HealthPermissions.READ_HEART_RATE,
                HealthPermissions.READ_STEPS,
            ),
            HealthPermissions.REQUIRED,
        )
        assertEquals(
            setOf(
                HealthPermissions.READ_OXYGEN_SATURATION,
                HealthPermissions.READ_RESPIRATORY_RATE,
                HealthPermissions.READ_HEART_RATE_VARIABILITY,
            ),
            HealthPermissions.OPTIONAL,
        )
        assertEquals(
            setOf(HealthPermissions.READ_HEALTH_DATA_HISTORY, HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND),
            HealthPermissions.SPECIAL,
        )
    }

    @Test
    fun `special permissions are requestable only when their feature is available`() {
        val none = HealthPermissions.requestable { false }
        assertEquals(HealthPermissions.REQUIRED + HealthPermissions.OPTIONAL, none)

        val historyOnly = HealthPermissions.requestable { it == HcFeature.READ_HISTORY }
        assertEquals(HealthPermissions.REQUIRED + HealthPermissions.OPTIONAL + HealthPermissions.READ_HEALTH_DATA_HISTORY, historyOnly)

        val all = HealthPermissions.requestable { true }
        assertEquals(HealthPermissions.REQUIRED + HealthPermissions.OPTIONAL + HealthPermissions.SPECIAL, all)
    }

    @Test
    fun `features and missing permissions`() {
        assertEquals(HcFeature.READ_HISTORY, HealthPermissions.featureOf(HealthPermissions.READ_HEALTH_DATA_HISTORY))
        assertEquals(HcFeature.READ_IN_BACKGROUND, HealthPermissions.featureOf(HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND))
        assertNull(HealthPermissions.featureOf(HealthPermissions.READ_SLEEP))
        assertEquals(
            setOf(HealthPermissions.WRITE_SLEEP, HealthPermissions.READ_STEPS),
            HealthPermissions.missingRequired(setOf(HealthPermissions.READ_SLEEP, HealthPermissions.READ_HEART_RATE)),
        )
        assertEquals(emptySet<String>(), HealthPermissions.missingRequired(HealthPermissions.REQUIRED + HealthPermissions.SPECIAL))
        assertEquals(HealthPermissions.READ_OXYGEN_SATURATION, HealthPermissions.forChannel(OptionalChannel.SPO2))
        assertEquals(HealthPermissions.READ_RESPIRATORY_RATE, HealthPermissions.forChannel(OptionalChannel.RESPIRATORY_RATE))
        assertEquals(HealthPermissions.READ_HEART_RATE_VARIABILITY, HealthPermissions.forChannel(OptionalChannel.HRV))
    }
}
