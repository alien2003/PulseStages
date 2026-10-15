package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.HrSample
import io.github.alien2003.pulsestages.algo.model.HrvSample
import io.github.alien2003.pulsestages.algo.model.NightInput
import io.github.alien2003.pulsestages.algo.model.RespiratoryRateSample
import io.github.alien2003.pulsestages.algo.model.Spo2Sample
import io.github.alien2003.pulsestages.algo.model.StepsSample
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.data.local.db.InputChange
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class InputFingerprintTest {
    private val all = InputSelection(HrSourcesMode.SAME_AS_SLEEP, spo2 = true, respiratoryRate = true, hrv = true)
    private val none = InputSelection(HrSourcesMode.SAME_AS_SLEEP, spo2 = false, respiratoryRate = false, hrv = false)

    private fun input(
        heartRate: List<HrSample> = listOf(HrSample(T0, 55.0), HrSample(T0 + MINUTE, 56.0), HrSample(T0 + 2 * MINUTE, 54.5)),
        steps: List<StepsSample> = listOf(StepsSample(T0, T0 + MINUTE, 3)),
        spo2: List<Spo2Sample> = listOf(Spo2Sample(T0, 96.0)),
        respiratoryRate: List<RespiratoryRateSample> = listOf(RespiratoryRateSample(T0, 14.5)),
        hrv: List<HrvSample> = listOf(HrvSample(T0, 42.0)),
    ): NightInput = NightInput(T0, T0 + HOUR, heartRate, steps, spo2, respiratoryRate, hrv)

    private fun part(
        fingerprint: String,
        channel: String,
    ): String = fingerprint.split('|').single { it.startsWith("$channel:") }

    @Test
    fun `the format is the version, then count and SHA-256 per channel in a fixed order`() {
        val fingerprint = InputFingerprint.of(input(), all)

        val hex = "[0-9a-f]{64}"
        val pattern = Regex("1\\|hr:3:$hex\\|steps:1:$hex\\|spo2:1:$hex\\|rr:1:$hex\\|hrv:1:$hex")
        assertTrue(pattern.matches(fingerprint), fingerprint)
        assertEquals(InputFingerprint.FORMAT_VERSION, fingerprint.substringBefore('|'))
    }

    @Test
    fun `each part hashes the samples as big-endian longs`() {
        val fingerprint = InputFingerprint.of(input(heartRate = listOf(HrSample(T0, 55.0)), steps = emptyList()), all)

        val bytes =
            ByteBuffer
                .allocate(16)
                .putLong(T0)
                .putLong(55.0.toRawBits())
                .array()
        assertEquals("hr:1:${sha256(bytes)}", part(fingerprint, "hr"))
        assertEquals("steps:0:${sha256(ByteArray(0))}", part(fingerprint, "steps"), "no samples: the hash of nothing")
    }

    @Test
    fun `optional channels that were not read are a dash, read but empty ones are counted`() {
        val notRead = InputFingerprint.of(input(spo2 = emptyList(), respiratoryRate = emptyList(), hrv = emptyList()), none)
        val readEmpty = InputFingerprint.of(input(spo2 = emptyList(), respiratoryRate = emptyList(), hrv = emptyList()), all)

        assertEquals(listOf("spo2:-", "rr:-", "hrv:-"), notRead.split('|').takeLast(3))
        assertEquals(listOf("spo2:0:", "rr:0:", "hrv:0:"), readEmpty.split('|').takeLast(3).map { it.substringBeforeLast(':') + ":" })
        assertNotEquals(notRead, readEmpty)
        assertEquals(notRead.split('|').take(3), readEmpty.split('|').take(3), "heart rate and steps are always read")
    }

    @Test
    fun `the order Health Connect returns samples in does not matter, also for equal times`() {
        val heartRate = listOf(HrSample(T0, 55.0), HrSample(T0, 61.0), HrSample(T0 + MINUTE, 50.0), HrSample(T0 + MINUTE, 49.0))
        val steps = listOf(StepsSample(T0, T0 + MINUTE, 5), StepsSample(T0, T0 + MINUTE, 2), StepsSample(T0, T0 + 2 * MINUTE, 1))
        val spo2 = listOf(Spo2Sample(T0, 97.0), Spo2Sample(T0, 95.0))

        val sorted = InputFingerprint.of(input(heartRate = heartRate, steps = steps, spo2 = spo2), all)
        val shuffled = InputFingerprint.of(input(heartRate = heartRate.reversed(), steps = steps.reversed(), spo2 = spo2.reversed()), all)

        assertEquals(sorted, shuffled)
    }

    @Test
    fun `a changed value, an extra sample or a moved sample changes only its channel`() {
        val base = InputFingerprint.of(input(), all)
        val changedHeartRate = listOf(HrSample(T0, 55.0), HrSample(T0 + MINUTE, 56.5), HrSample(T0 + 2 * MINUTE, 54.5))
        val changedBpm = InputFingerprint.of(input(heartRate = changedHeartRate), all)
        val movedStep = InputFingerprint.of(input(steps = listOf(StepsSample(T0 + 1, T0 + MINUTE, 3))), all)
        val extraHrv = InputFingerprint.of(input(hrv = listOf(HrvSample(T0, 42.0), HrvSample(T0 + MINUTE, 40.0))), all)

        assertNotEquals(part(base, "hr"), part(changedBpm, "hr"))
        assertEquals("hr:3:", part(changedBpm, "hr").take("hr:3:".length))
        assertEquals(base.split('|').drop(2), changedBpm.split('|').drop(2))
        assertNotEquals(part(base, "steps"), part(movedStep, "steps"))
        assertEquals(part(base, "hr"), part(movedStep, "hr"))
        assertEquals("hrv:2:", part(extraHrv, "hrv").take("hrv:2:".length))
        assertEquals(base.split('|').dropLast(1), extraHrv.split('|').dropLast(1))
    }

    @Test
    fun `the session bounds, forced-awake gaps and the baseline nadir are not part of it`() {
        val base = input()
        val other =
            base.copy(
                sessionStartMs = T0 - HOUR,
                sessionEndMs = T0 + 2 * HOUR,
                forcedAwake = listOf(TimeSpan(T0 + MINUTE, T0 + 2 * MINUTE)),
                baselineNadirBpm = 48.0,
            )

        assertEquals(InputFingerprint.of(base, all), InputFingerprint.of(other, all))
    }

    @Test
    fun `more heart-rate samples inside the session is MORE_HEART_RATE whatever else changed`() {
        val previous = InputFingerprint.of(input(), all)
        val current = InputFingerprint.of(input(heartRate = input().heartRate + HrSample(T0 + 3 * MINUTE, 57.0)), all)

        assertEquals(InputChange.MORE_HEART_RATE, InputFingerprint.changeOf(previous, current, hrBefore = 46, hrAfter = 57))
        assertEquals(InputChange.MORE_HEART_RATE, InputFingerprint.changeOf(null, current, hrBefore = 46, hrAfter = 57))
        assertEquals(InputChange.MORE_HEART_RATE, InputFingerprint.changeOf(previous, previous, hrBefore = 1, hrAfter = 2))
    }

    @Test
    fun `another heart-rate change or an unknown previous input is HEART_RATE`() {
        val previous = InputFingerprint.of(input(), all)
        val replaced = InputFingerprint.of(input(heartRate = listOf(HrSample(T0, 70.0), HrSample(T0 + MINUTE, 56.0))), all)

        assertEquals(InputChange.HEART_RATE, InputFingerprint.changeOf(previous, replaced, hrBefore = 46, hrAfter = 46))
        assertEquals(InputChange.HEART_RATE, InputFingerprint.changeOf(previous, replaced, hrBefore = 46, hrAfter = 40), "fewer samples")
        assertEquals(InputChange.HEART_RATE, InputFingerprint.changeOf(null, previous, hrBefore = 46, hrAfter = 46))
    }

    @Test
    fun `a change of steps or an optional channel with the same heart rate is OTHER_DATA`() {
        val previous = InputFingerprint.of(input(), all)
        val steps = InputFingerprint.of(input(steps = listOf(StepsSample(T0, T0 + MINUTE, 30))), all)
        val spo2 = InputFingerprint.of(input(spo2 = emptyList()), all)
        val selection = InputFingerprint.of(input(), none)

        listOf(steps, spo2, selection).forEach { current ->
            assertEquals(InputChange.OTHER_DATA, InputFingerprint.changeOf(previous, current, hrBefore = 46, hrAfter = 46), current)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val T0 = 1_791_849_600_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
