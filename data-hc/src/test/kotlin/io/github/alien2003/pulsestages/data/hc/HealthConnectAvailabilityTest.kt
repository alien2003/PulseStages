package io.github.alien2003.pulsestages.data.hc

import androidx.health.connect.client.HealthConnectClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class HealthConnectAvailabilityTest {
    @ParameterizedTest
    @ValueSource(ints = [HealthConnectClient.SDK_AVAILABLE])
    fun `available status maps to AVAILABLE`(status: Int) {
        assertEquals(HealthConnectAvailability.AVAILABLE, HealthConnectAvailability.fromSdkStatus(status))
    }

    @ParameterizedTest
    @ValueSource(ints = [HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED])
    fun `update required status maps to PROVIDER_UPDATE_REQUIRED`(status: Int) {
        assertEquals(HealthConnectAvailability.PROVIDER_UPDATE_REQUIRED, HealthConnectAvailability.fromSdkStatus(status))
    }

    @ParameterizedTest
    @ValueSource(ints = [HealthConnectClient.SDK_UNAVAILABLE, -1, 0, 42])
    fun `anything else maps to UNAVAILABLE`(status: Int) {
        assertEquals(HealthConnectAvailability.UNAVAILABLE, HealthConnectAvailability.fromSdkStatus(status))
    }
}
