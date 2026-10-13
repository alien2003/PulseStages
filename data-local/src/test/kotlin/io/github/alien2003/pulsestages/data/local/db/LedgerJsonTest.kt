package io.github.alien2003.pulsestages.data.local.db

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.algo.model.WarningCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LedgerJsonTest {
    private val converters = LedgerConverters()

    @Test
    fun `stages use the compact tuple format and round-trip`() {
        val stages =
            listOf(
                StageInterval(0L, 60_000L, SleepStage.AWAKE),
                StageInterval(60_000L, 1_800_000L, SleepStage.DEEP),
                StageInterval(1_800_000L, 1_700_000_000_000L, SleepStage.UNKNOWN),
            )

        val json = converters.fromStages(stages)

        assertEquals("""[[0,60000,"AWAKE"],[60000,1800000,"DEEP"],[1800000,1700000000000,"UNKNOWN"]]""", json)
        assertEquals(stages, converters.toStages(json))
        assertEquals("[]", converters.fromStages(emptyList()))
        assertEquals(emptyList<StageInterval>(), converters.toStages("[]"))
    }

    @Test
    fun `malformed stage tuples are rejected`() {
        assertThrows<IllegalArgumentException> { converters.toStages("""[[0,60000]]""") }
        assertThrows<IllegalArgumentException> { converters.toStages("""[[0,60000,"NAPPING"]]""") }
    }

    @Test
    fun `string lists round-trip including characters that need escaping`() {
        val values = listOf("a1b2-c3", "quote\"and\\backslash", "")

        assertEquals(values, converters.toStrings(converters.fromStrings(values)))
        assertEquals("""["x","y"]""", converters.fromStrings(listOf("x", "y")))
    }

    @Test
    fun `warnings are stored by name and unknown names are dropped`() {
        val warnings = listOf(WarningCode.LOW_HR_RANGE, WarningCode.DATA_GAPS)

        assertEquals("""["LOW_HR_RANGE","DATA_GAPS"]""", converters.fromWarnings(warnings))
        assertEquals(warnings, converters.toWarnings("""["LOW_HR_RANGE","FUTURE_WARNING","DATA_GAPS"]"""))
    }

    @Test
    fun `baseline median follows the minimum-night and even-count rules`() {
        assertNull(NightBaseline.median(emptyList()))
        assertNull(NightBaseline.median(listOf(50.0, 52.0)), "fewer than 3 nights")
        assertEquals(52.0, NightBaseline.median(listOf(55.0, 50.0, 52.0)))
        assertEquals(53.5, NightBaseline.median(listOf(60.0, 50.0, 52.0, 55.0)), "mean of the middle two")
        assertEquals(51.0, NightBaseline.median(listOf(51.0, 51.0, 51.0, 49.0, 80.0)))
        assertNull(NightBaseline.median(listOf(50.0, Double.NaN, 52.0)))
    }

    @Test
    fun `stale is computed from status, algorithm version and params hash`() {
        val night =
            EstimatedNightEntity(
                clientRecordId = "ps:1",
                sourceUuids = listOf("u1"),
                sourcePackage = "com.example",
                sessionStartMs = 0,
                sessionEndMs = 1,
                startZoneOffsetSec = 0,
                endZoneOffsetSec = 0,
                algoVersion = 1,
                paramsHash = "h1",
                estimator = EstimatorType.HMM,
                status = NightStatus.WRITTEN,
                createdAt = 0,
                updatedAt = 0,
            )

        assertEquals(false, night.isStale(1, "h1"))
        assertEquals(true, night.isStale(2, "h1"))
        assertEquals(true, night.isStale(1, "h2"))
        assertEquals(false, night.copy(status = NightStatus.SUPERSEDED).isStale(2, "h2"), "only WRITTEN nights can be stale")
    }
}
