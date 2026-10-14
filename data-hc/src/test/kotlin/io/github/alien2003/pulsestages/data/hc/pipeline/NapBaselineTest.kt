package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.hc.usecase.UseCaseResult
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Naps estimated with `minSessionMin` = 30 and the rolling nadir baseline (the nap threshold `napMode.maxSessionMin`,
 * 180 min, which replaced `features.baselineMinSessionMin`): naps neither contribute to the baseline nor get one, in the
 * pipeline and in every other reader of the inputs.
 */
class NapBaselineTest {
    @TempDir
    lateinit var dir: File

    /** Daytime nap heart rate: well above the nights' nadir of 49 bpm (the fake estimator's nadir is the minimum). */
    private val napBpm = 70.0

    /**
     * Three nights (nadir 49) starting six to four days ago, then a 45-min nap on each of the last three afternoons
     * (nadir 70), then last night; naps are estimated (`minSessionMin` = 30). Returns the starts: nights, naps, last night.
     */
    private suspend fun PipelineHarness.nightsAndNaps(maxSessionMin: Double? = null): Triple<List<Long>, List<Long>, Long> {
        settings.set { s ->
            val napMode = maxSessionMin?.let { s.algo.napMode.copy(maxSessionMin = it) } ?: s.algo.napMode
            s.copy(sources = s.sources.copy(minSessionMin = 30), algo = s.algo.copy(napMode = napMode))
        }
        val nights = (6 downTo 4).map { nightStart(it) }
        nights.forEach { repository.addNight(OHEALTH, it) }
        val naps = (3 downTo 1).map { nightStart(it) + 16 * HOUR_MS }
        naps.forEach { start ->
            repository.addSleepSession(OHEALTH, start, start + NAP_MIN * MINUTE_MS)
            repository.addHeartRate(OHEALTH, start - 30 * MINUTE_MS, start + (NAP_MIN + 30) * MINUTE_MS) { napBpm }
        }
        val last = nightStart(0)
        repository.addNight(OHEALTH, last)
        return Triple(nights, naps, last)
    }

    private fun PipelineHarness.baselineOf(startMs: Long): Double? =
        estimator.inputs.single { it.sessionStartMs == startMs }.baselineNadirBpm

    @Test
    fun `naps are estimated without a baseline and the night after them uses only nights`() =
        harnessTest(dir) { h ->
            val (nights, naps, last) = h.nightsAndNaps()

            val report = h.processRecent()

            assertEquals(7, report.estimated, "$report")
            assertEquals(listOf(null, null, null), nights.map { h.baselineOf(it) }, "fewer than three nights before them")
            assertEquals(listOf<Double?>(null, null, null), naps.map { h.baselineOf(it) }, "a nap is never compared with the baseline")
            assertEquals(49.0, h.baselineOf(last), "the naps' nadir of $napBpm does not inflate the baseline")
            // Every written session keeps its nadir row, naps included; the baseline query skips the naps.
            assertEquals(7, h.ledger.allBaselines().size)
            assertEquals(
                listOf(napBpm, napBpm, napBpm),
                h.ledger
                    .allNights()
                    .filter { it.sessionStartMs in naps }
                    .map { it.nadirBpm },
            )
            assertTrue(h.ledger.allNights().all { it.status == NightStatus.WRITTEN })
        }

    @Test
    fun `with napMode maxSessionMin 0 naps count and are compared as before`() =
        harnessTest(dir) { h ->
            val (_, naps, last) = h.nightsAndNaps(maxSessionMin = 0.0)

            h.processRecent()

            assertEquals(listOf<Double?>(49.0, 49.0, 49.0), naps.map { h.baselineOf(it) })
            assertEquals((49.0 + napBpm) / 2, h.baselineOf(last), "median of 49, 49, 49, 70, 70, 70")
        }

    @Test
    fun `a changed threshold applies to the stored history at once`() =
        harnessTest(dir) { h ->
            val (_, naps, last) = h.nightsAndNaps(maxSessionMin = 0.0)
            h.processRecent()
            val ledgerNight = h.ledger.allNights().single { it.sessionStartMs == last }

            h.settings.set { it.copy(algo = it.algo.copy(napMode = it.algo.napMode.copy(maxSessionMin = 180.0))) }

            assertEquals(49.0, h.ledgerInputs.read(ledgerNight, h.settings.current, h.repository.granted).baselineNadirBpm)
            val napNight = h.ledger.allNights().single { it.sessionStartMs == naps.last() }
            assertNull(h.ledgerInputs.read(napNight, h.settings.current, h.repository.granted).baselineNadirBpm)
        }

    @Test
    fun `the night detail re-reads a nap without a baseline and a night with the nights-only baseline`() =
        harnessTest(dir) { h ->
            val (_, naps, last) = h.nightsAndNaps()
            h.processRecent()
            val napNight = h.ledger.allNights().single { it.sessionStartMs == naps.last() }
            val lastNight = h.ledger.allNights().single { it.sessionStartMs == last }

            val napInspection = h.inspector.inspect(napNight.clientRecordId)
            val nightInspection = h.inspector.inspect(lastNight.clientRecordId)

            assertNull(success(napInspection).input.baselineNadirBpm)
            assertEquals(49.0, success(nightInspection).input.baselineNadirBpm)
        }

    private fun <T> success(result: UseCaseResult<T>): T =
        when (result) {
            is UseCaseResult.Success -> result.value
            else -> fail("expected a success, got $result")
        }

    private companion object {
        const val NAP_MIN = 45L
    }
}
