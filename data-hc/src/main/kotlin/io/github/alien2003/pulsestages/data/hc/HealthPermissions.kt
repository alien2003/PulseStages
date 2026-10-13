package io.github.alien2003.pulsestages.data.hc

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord

/**
 * Health Connect permission strings used by PulseStages (they must also be declared in the app manifest). The
 * values come from the Jetpack helpers, e.g. `android.permission.health.READ_SLEEP`.
 */
object HealthPermissions {
    /** Read sleep sessions. Required. */
    val READ_SLEEP: String = HealthPermission.getReadPermission(SleepSessionRecord::class)

    /** Write our estimated sleep sessions. Required. */
    val WRITE_SLEEP: String = HealthPermission.getWritePermission(SleepSessionRecord::class)

    /** Read heart rate. Required. */
    val READ_HEART_RATE: String = HealthPermission.getReadPermission(HeartRateRecord::class)

    /** Read steps (movement proxy). Required. */
    val READ_STEPS: String = HealthPermission.getReadPermission(StepsRecord::class)

    /** Read SpO2. Optional: the app works without it. */
    val READ_OXYGEN_SATURATION: String = HealthPermission.getReadPermission(OxygenSaturationRecord::class)

    /** Read respiratory rate. Optional. */
    val READ_RESPIRATORY_RATE: String = HealthPermission.getReadPermission(RespiratoryRateRecord::class)

    /** Read heart-rate variability (RMSSD). Optional. */
    val READ_HEART_RATE_VARIABILITY: String = HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)

    /** Read data older than 30 days before the first grant (needed for a full backfill). Special, feature-gated. */
    const val READ_HEALTH_DATA_HISTORY: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

    /** Read other apps' data while the app is in the background (WorkManager). Special, feature-gated. */
    const val READ_HEALTH_DATA_IN_BACKGROUND: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    /** Permissions without which nothing can be estimated or written. */
    val REQUIRED: Set<String> = linkedSetOf(READ_SLEEP, WRITE_SLEEP, READ_HEART_RATE, READ_STEPS)

    /** Optional data channels. */
    val OPTIONAL: Set<String> = linkedSetOf(READ_OXYGEN_SATURATION, READ_RESPIRATORY_RATE, READ_HEART_RATE_VARIABILITY)

    /** Special permissions; each may only be requested when its [HcFeature] is available. */
    val SPECIAL: Set<String> = linkedSetOf(READ_HEALTH_DATA_HISTORY, READ_HEALTH_DATA_IN_BACKGROUND)

    /** The feature that gates [permission], or null when it is not feature-gated. */
    fun featureOf(permission: String): HcFeature? =
        when (permission) {
            READ_HEALTH_DATA_HISTORY -> HcFeature.READ_HISTORY
            READ_HEALTH_DATA_IN_BACKGROUND -> HcFeature.READ_IN_BACKGROUND
            else -> null
        }

    /**
     * Everything the app may request on this device: [REQUIRED] + [OPTIONAL] + the [SPECIAL] permissions whose
     * feature is available according to [isFeatureAvailable].
     */
    fun requestable(isFeatureAvailable: (HcFeature) -> Boolean): Set<String> =
        linkedSetOf<String>().apply {
            addAll(REQUIRED)
            addAll(OPTIONAL)
            SPECIAL.filterTo(this) { permission -> featureOf(permission)?.let(isFeatureAvailable) ?: true }
        }

    /** The [REQUIRED] permissions missing from [granted]. */
    fun missingRequired(granted: Set<String>): Set<String> = REQUIRED.filterNotTo(linkedSetOf()) { it in granted }

    /** The read permission needed for the optional [channel]. */
    fun forChannel(channel: OptionalChannel): String =
        when (channel) {
            OptionalChannel.SPO2 -> READ_OXYGEN_SATURATION
            OptionalChannel.RESPIRATORY_RATE -> READ_RESPIRATORY_RATE
            OptionalChannel.HRV -> READ_HEART_RATE_VARIABILITY
        }
}

/** The optional data channels of a night. */
enum class OptionalChannel {
    /** Blood-oxygen saturation. */
    SPO2,

    /** Respiratory rate. */
    RESPIRATORY_RATE,

    /** Heart-rate variability (RMSSD). */
    HRV,
}
