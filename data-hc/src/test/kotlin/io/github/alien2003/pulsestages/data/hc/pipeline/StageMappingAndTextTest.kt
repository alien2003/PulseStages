package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.local.settings.WriteSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StageMappingTest {
    private val m = 60_000L

    @Test
    fun `stages map to Health Connect constants`() {
        assertEquals(1, StageMapping.toHcType(SleepStage.AWAKE, awakeAsLight = false))
        assertEquals(4, StageMapping.toHcType(SleepStage.LIGHT, awakeAsLight = false))
        assertEquals(5, StageMapping.toHcType(SleepStage.DEEP, awakeAsLight = false))
        assertEquals(6, StageMapping.toHcType(SleepStage.REM, awakeAsLight = false))
        assertEquals(0, StageMapping.toHcType(SleepStage.UNKNOWN, awakeAsLight = false))
        assertEquals(4, StageMapping.toHcType(SleepStage.AWAKE, awakeAsLight = true))
        assertEquals(0, StageMapping.toHcType(SleepStage.UNKNOWN, awakeAsLight = true))
        SleepStage.entries.forEach { assertEquals(it, StageMapping.fromHcType(StageMapping.toHcType(it, awakeAsLight = false))) }
        assertEquals(SleepStage.UNKNOWN, StageMapping.fromHcType(2))
    }

    @Test
    fun `awakeAsLight merges the resulting adjacent LIGHT stages`() {
        val stages =
            listOf(
                StageInterval(0, 10 * m, SleepStage.LIGHT),
                StageInterval(10 * m, 15 * m, SleepStage.AWAKE),
                StageInterval(15 * m, 30 * m, SleepStage.LIGHT),
                StageInterval(30 * m, 50 * m, SleepStage.DEEP),
            )
        assertEquals(
            listOf(HcStage(0, 10 * m, 4), HcStage(10 * m, 15 * m, 1), HcStage(15 * m, 30 * m, 4), HcStage(30 * m, 50 * m, 5)),
            StageMapping.toHcStages(stages, awakeAsLight = false),
        )
        assertEquals(listOf(HcStage(0, 30 * m, 4), HcStage(30 * m, 50 * m, 5)), StageMapping.toHcStages(stages, awakeAsLight = true))
    }

    @Test
    fun `valid hypnograms pass`() {
        val stages = listOf(HcStage(0, 10 * m, 4), HcStage(10 * m, 11 * m, 1), HcStage(11 * m, 50 * m + 30_000, 0))
        assertEquals(emptyList<String>(), StageMapping.validate(stages, 0, 50 * m + 30_000, m))
        assertEquals(
            emptyList<String>(),
            StageMapping.validate(listOf(HcStage(0, 30_000, 4)), 0, 30_000, m),
            "session shorter than an epoch",
        )
    }

    @Test
    fun `every kind of invalid hypnogram is reported`() {
        val session = 0L to 60 * m

        fun problems(vararg stages: HcStage) = StageMapping.validate(stages.toList(), session.first, session.second, m)

        assertEquals(listOf("no stages"), problems())
        assertTrue(problems(HcStage(m, 60 * m, 4)).any { "first stage" in it })
        assertTrue(problems(HcStage(0, 59 * m, 4)).any { "last stage" in it })
        assertTrue(problems(HcStage(0, 30 * m, 4), HcStage(31 * m, 60 * m, 5)).any { "gap" in it })
        assertTrue(problems(HcStage(0, 31 * m, 4), HcStage(30 * m, 60 * m, 5)).any { "overlap" in it })
        assertTrue(
            problems(HcStage(0, 30 * m, 4), HcStage(30 * m, 30 * m + 20_000, 5), HcStage(30 * m + 20_000, 60 * m, 4)).any {
                "shorter than one epoch" in
                    it
            },
        )
        assertTrue(problems(HcStage(0, 60 * m, 2)).any { "type 2" in it })
        assertTrue(problems(HcStage(-m, 60 * m, 4)).any { "outside" in it })
        assertTrue(StageMapping.validate(listOf(HcStage(0, 10_000, 4), HcStage(10_000, 30_000, 5)), 0, 30_000, m).isNotEmpty())
    }

    @Test
    fun `stage minutes per type`() {
        val minutes =
            StageMinutes.of(
                listOf(
                    HcStage(0, 30 * m, 4),
                    HcStage(30 * m, 45 * m, 5),
                    HcStage(45 * m, 50 * m, 1),
                    HcStage(
                        50 * m,
                        70 * m,
                        6,
                    ),
                    HcStage(70 * m, 71 * m, 0),
                ),
            )
        assertEquals(StageMinutes(awake = 5.0, light = 30.0, deep = 15.0, rem = 20.0, unknown = 1.0), minutes)
    }
}

class SessionTextTest {
    @Test
    fun `the default template is used for a null or blank template and placeholders are replaced`() {
        val default = "Sleep stages from heart rate (estimate)"
        assertEquals(default, SessionText.title(null, default, EstimatorType.HYBRID, 1, 0.61))
        assertEquals("Default", SessionText.title("  ", "Default", EstimatorType.HYBRID, 1, 0.61))
        assertEquals("HMM v1 61%", SessionText.title("{estimator} v{version} {confidence}%", "Default", EstimatorType.HMM, 1, 0.605))
        assertEquals("0 %", SessionText.title("{confidence} %", "Default", EstimatorType.RULES, 1, Double.NaN))
    }

    @Test
    fun `titles are limited to 1000 characters`() {
        assertEquals(1000, SessionText.title("x".repeat(1500), "Default", EstimatorType.RULES, 1, 0.5).length)
    }

    @Test
    fun `notes are compact JSON with the keys in the specified order and rounded numbers`() {
        val notes =
            SessionText.notes(
                estimator = EstimatorType.HYBRID,
                algoVersion = 1,
                paramsHash = "abc123",
                coverage = 0.92649,
                confidence = 0.6105,
                sourceUuids = listOf("u1", "u2"),
                hrSamples = 231,
                calibrated = false,
            )
        assertEquals(
            """{"algo":"HYBRID","version":1,"paramsHash":"abc123","coverage":0.926,"confidence":0.611,""" +
                """"sourceUuids":["u1","u2"],"hrSamples":231,"calibrated":false}""",
            notes,
        )
        assertEquals(0.0, SessionText.round3(Double.POSITIVE_INFINITY))
        assertEquals(1.0, SessionText.round3(0.99951))
    }

    @Test
    fun `the source uuids are read back from our notes, anything else has none`() {
        val notes = SessionText.notes(EstimatorType.RULES, 1, "h", 0.5, 0.5, listOf("u1", "u2"), 10, calibrated = true)
        assertEquals(listOf("u1", "u2"), SessionText.sourceUuidsOf(notes))
        listOf(null, "", "{}", "not json", "[1,2]", """{"sourceUuids":"u1"}""", """{"sourceUuids":[1,null]}""").forEach {
            assertEquals(emptyList<String>(), SessionText.sourceUuidsOf(it), "notes: $it")
        }
    }

    @Test
    fun `awakeAsLight is part of the params hash extra, the title template is not`() {
        val base = WriteSettings()
        assertNotEquals(OutputOptions.hashExtra(base), OutputOptions.hashExtra(base.copy(awakeAsLight = true)))
        assertEquals(OutputOptions.hashExtra(base), OutputOptions.hashExtra(base.copy(titleTemplate = "x")))
    }
}
