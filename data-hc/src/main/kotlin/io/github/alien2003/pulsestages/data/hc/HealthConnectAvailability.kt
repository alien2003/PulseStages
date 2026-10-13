package io.github.alien2003.pulsestages.data.hc

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Whether the Health Connect APIs can be used on this device. */
enum class HealthConnectAvailability {
    /** Health Connect can be used (`SDK_AVAILABLE`). */
    AVAILABLE,

    /** The Health Connect APK (Android 9–13) is missing or too old (`SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED`). */
    PROVIDER_UPDATE_REQUIRED,

    /** Health Connect is not supported on this device or profile (`SDK_UNAVAILABLE`). */
    UNAVAILABLE,
    ;

    /** Mapping from the client's SDK status. */
    companion object {
        /** Maps a [HealthConnectClient.getSdkStatus] result to [HealthConnectAvailability]. */
        fun fromSdkStatus(sdkStatus: Int): HealthConnectAvailability =
            when (sdkStatus) {
                HealthConnectClient.SDK_AVAILABLE -> AVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> PROVIDER_UPDATE_REQUIRED
                else -> UNAVAILABLE
            }
    }
}

/** Data origins PulseStages reads from. */
object DataOrigins {
    /** OHealth (OPPO/HeyTap), which writes the watch's sleep sessions, heart rate and steps. */
    const val OHEALTH_PACKAGE_NAME = "com.heytap.health.international"
}

/** Reads [HealthConnectClient.getSdkStatus] for this device (see also [HealthRepository.availability]). */
class HealthConnectAvailabilityChecker
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** The current availability of Health Connect. */
        fun check(): HealthConnectAvailability = HealthConnectAvailability.fromSdkStatus(HealthConnectClient.getSdkStatus(context))
    }
