package io.github.alien2003.pulsestages.data.hc.usecase

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.data.hc.EstimatedSessionWrite
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.THIRD_APP
import io.github.alien2003.pulsestages.data.hc.testutil.cyclingBpm
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ReferenceComparisonTest {
    @TempDir
    lateinit var dir: File

    private val stageAwake = 1
    private val stageSleeping = 2
    private val stageOutOfBed = 3
    private val stageLight = 4
    private val stageDeep = 5
    private val stageRem = 6
    private val stageAwakeInBed = 7

    /** Stages following [cyclingBpm]'s 90-minute pattern (what a perfect reference would say), awake as AWAKE_IN_BED. */
    private fun cyclingStages(
        startMs: Long,
        durationMs: Long,
    ): List<HcStage> {
        val pattern = listOf(15 to stageLight, 25 to stageDeep, 25 to stageLight, 20 to stageRem, 5 to stageAwakeInBed)
        val stages = mutableListOf<HcStage>()
        var t = startMs
        val end = startMs + durationMs
        while (t < end) {
            for ((minutes, type) in pattern) {
                val to = minOf(t + minutes * MINUTE_MS, end)
                if (t < to) stages += HcStage(t, to, type)
                t = to
            }
        }
        return stages
    }

    private fun FakeHealthRepository.addReference(
        startMs: Long,
        durationMs: Long = 8 * HOUR_MS,
        stages: List<HcStage> = cyclingStages(startMs, durationMs),
        heartRateFrom: String? = OHEALTH,
        packageName: String = OTHER_APP,
    ): HcSleepSession {
        val session = addSleepSession(packageName, startMs, startMs + durationMs, stages)
        if (heartRateFrom !=
            null
        ) {
            addHeartRate(heartRateFrom, startMs - 30 * MINUTE_MS, startMs + durationMs + 30 * MINUTE_MS, bpm = cyclingBpm(startMs))
        }
        return session
    }

    private fun <T> success(result: UseCaseResult<T>): T =
        when (result) {
            is UseCaseResult.Success -> result.value
            else -> fail("expected a success, got $result")
        }

    @Test
    fun `reference nights are other apps' sessions with real stages, with heart rate from the enabled sources first`() =
        harnessTest(dir) { h ->
            val good = h.repository.addReference(nightStart(5))
            h.repository.addReference(nightStart(4), stages = listOf(HcStage(nightStart(4), nightStart(4) + 8 * HOUR_MS, stageSleeping)))
            h.repository.addReference(nightStart(3), durationMs = 60 * MINUTE_MS)
            val fallback = h.repository.addReference(nightStart(2), heartRateFrom = THIRD_APP)
            val noHeartRate = h.repository.addReference(nightStart(1), heartRateFrom = null)
            h.repository.preloadOwnSession(
                EstimatedSessionWrite(
                    "ps:own",
                    1_000,
                    nightStart(6),
                    nightStart(6) + 8 * HOUR_MS,
                    0,
                    0,
                    cyclingStages(
                        nightStart(6),
                        8 * HOUR_MS,
                    ),
                    "t",
                    "{}",
                ),
            )

            val search = success(h.referenceComparison.findReferenceNights(NOON - 30 * DAY_MS, NOON))

            assertEquals(listOf(good.uuid, fallback.uuid), search.nights.map { it.session.uuid })
            assertEquals(listOf(noHeartRate.uuid), search.withoutHeartRate.map { it.uuid })
            val first = search.nights.first()
            assertEquals(setOf(OHEALTH), first.heartRatePackages, "heart rate of the enabled source")
            assertNull(search.nights.last().heartRatePackages, "no heart rate from the enabled sources: any origin")
            assertTrue(
                search.nights
                    .last()
                    .input.heartRate
                    .isNotEmpty(),
            )
            assertEquals(
                SleepStage.AWAKE,
                first.truth.first { it.startMs == nightStart(5) + 85 * MINUTE_MS }.stage,
                "AWAKE_IN_BED is AWAKE",
            )
            assertEquals(
                nightStart(5) - 30 * MINUTE_MS,
                first.input.heartRate
                    .first()
                    .timeMs,
                "the context margin is read",
            )
        }

    @Test
    fun `sessions without real sleep stages are no reference nights and are reported apart`() =
        harnessTest(dir) { h ->
            val good = h.repository.addReference(nightStart(3))
            // An asleep / awake-only tracker: SLEEPING for the night with three 10-minute AWAKE bouts.
            val awakeOnlyStart = nightStart(2)
            val awakeOnly =
                h.repository.addReference(
                    awakeOnlyStart,
                    stages =
                        listOf(
                            HcStage(awakeOnlyStart, awakeOnlyStart + 2 * HOUR_MS, stageSleeping),
                            HcStage(awakeOnlyStart + 2 * HOUR_MS, awakeOnlyStart + 2 * HOUR_MS + 10 * MINUTE_MS, stageAwake),
                            HcStage(awakeOnlyStart + 2 * HOUR_MS + 10 * MINUTE_MS, awakeOnlyStart + 4 * HOUR_MS, stageSleeping),
                            HcStage(awakeOnlyStart + 4 * HOUR_MS, awakeOnlyStart + 4 * HOUR_MS + 10 * MINUTE_MS, stageAwake),
                            HcStage(awakeOnlyStart + 4 * HOUR_MS + 10 * MINUTE_MS, awakeOnlyStart + 6 * HOUR_MS, stageSleeping),
                            HcStage(awakeOnlyStart + 6 * HOUR_MS, awakeOnlyStart + 6 * HOUR_MS + 10 * MINUTE_MS, stageAwake),
                            HcStage(awakeOnlyStart + 6 * HOUR_MS + 10 * MINUTE_MS, awakeOnlyStart + 8 * HOUR_MS, stageSleeping),
                        ),
                )
            // Mostly SLEEPING, with one hour of real stages.
            val sparseStart = nightStart(1)
            val sparse =
                h.repository.addReference(
                    sparseStart,
                    stages =
                        listOf(
                            HcStage(sparseStart, sparseStart + HOUR_MS, stageLight),
                            HcStage(sparseStart + HOUR_MS, sparseStart + 8 * HOUR_MS, stageSleeping),
                        ),
                )

            val search = success(h.referenceComparison.findReferenceNights(NOON - 7 * DAY_MS, NOON))

            assertEquals(listOf(good.uuid), search.nights.map { it.session.uuid })
            assertEquals(listOf(awakeOnly.uuid, sparse.uuid), search.withoutSleepStages.map { it.uuid })
            assertTrue(search.withoutHeartRate.isEmpty())
            val report = h.referenceComparison.compare(search.nights, AlgoParams())
            assertTrue(checkNotNull(report.kappa) > 0.7, "the pooled metrics are not dragged down: ${report.kappa}")
        }

    @Test
    fun `a reference session needs a sleep stage covering, with the other real stages, half of it`() {
        fun session(vararg stages: HcStage) = HcSleepSession("u", OTHER_APP, 0, 100, 0, 0, stages.toList(), null, null, null, 0, 0)

        assertTrue(ReferenceStages.isReference(session(HcStage(0, 40, stageAwake), HcStage(40, 50, stageDeep))))
        assertTrue(ReferenceStages.isReference(session(HcStage(0, 100, stageRem))))
        assertTrue(!ReferenceStages.isReference(session(HcStage(0, 100, stageAwake))), "awake only")
        assertTrue(!ReferenceStages.isReference(session(HcStage(0, 49, stageLight), HcStage(49, 100, stageSleeping))), "too little")
        assertTrue(!ReferenceStages.isReference(session()), "no stages")
        assertTrue(ReferenceStages.isReference(session(HcStage(0, 30, stageLight), HcStage(20, 50, stageDeep))), "overlaps count once")
        assertTrue(!ReferenceStages.isReference(session(HcStage(0, 30, stageLight), HcStage(10, 40, stageDeep))), "40 % covered")
    }

    @Test
    fun `SLEEPING and UNKNOWN reference stages are not scored, the awake family is AWAKE`() {
        assertEquals(SleepStage.AWAKE, ReferenceStages.stageOf(stageAwake))
        assertEquals(SleepStage.AWAKE, ReferenceStages.stageOf(stageAwakeInBed))
        assertEquals(SleepStage.AWAKE, ReferenceStages.stageOf(stageOutOfBed))
        assertEquals(SleepStage.LIGHT, ReferenceStages.stageOf(stageLight))
        assertEquals(SleepStage.DEEP, ReferenceStages.stageOf(stageDeep))
        assertEquals(SleepStage.REM, ReferenceStages.stageOf(stageRem))
        assertNull(ReferenceStages.stageOf(stageSleeping))
        assertNull(ReferenceStages.stageOf(0))
        val session =
            HcSleepSession(
                "u",
                OTHER_APP,
                0,
                100,
                0,
                0,
                listOf(HcStage(-10, 20, stageSleeping), HcStage(20, 60, stageDeep), HcStage(60, 150, stageRem)),
                null,
                null,
                null,
                0,
                0,
            )
        assertEquals(
            listOf(20L to 60L, 60L to 100L),
            ReferenceStages.truthOf(session).map { it.startMs to it.endMs },
            "clipped, SLEEPING left out",
        )
    }

    @Test
    fun `compare scores every night and pools the confusion matrices`() =
        harnessTest(dir) { h ->
            h.repository.addReference(nightStart(3))
            h.repository.addReference(nightStart(2))
            val allRem = nightStart(1)
            h.repository.addReference(allRem, stages = listOf(HcStage(allRem, allRem + 8 * HOUR_MS, stageRem)))
            val nights = success(h.referenceComparison.findReferenceNights(NOON - 7 * DAY_MS, NOON)).nights

            val report = h.referenceComparison.compare(nights, AlgoParams())

            assertEquals(3, report.scoredNights)
            val (good1, good2, bad) = report.nights
            assertTrue(checkNotNull(good1.kappa) > 0.7, "a reference that matches the heart rate agrees: ${good1.kappa}")
            assertTrue(checkNotNull(good2.kappa) > 0.7)
            assertTrue((bad.kappa ?: 0.0) < 0.1, "a reference that says REM all night does not: ${bad.kappa}")
            assertEquals(report.nights.sumOf { checkNotNull(it.score).confusion.total }, report.pooled.total)
            val pooledKappa = checkNotNull(report.kappa)
            assertTrue(pooledKappa < checkNotNull(good1.kappa) && pooledKappa > 0.0, "pooled kappa lies between: $pooledKappa")
            assertTrue(checkNotNull(good1.score).confusion.stage(SleepStage.DEEP).f1 > 0.8, "per-stage scores of one night")
            assertTrue(report.pooled.stage(SleepStage.DEEP).recall > 0.8, "pooled recall (the REM-only night has no DEEP)")
            assertTrue(
                report.pooled.stage(SleepStage.DEEP).precision < checkNotNull(good1.score).confusion.stage(SleepStage.DEEP).precision,
            )
            assertEquals(8 * 60 * 2L * 3, report.pooled.total, "every 30-s chunk of the three nights")
            assertNotNull(report.accuracy)
            assertNotNull(report.macroF1)
            val minutes = checkNotNull(report.meanMinutesError)
            assertTrue(minutes.rem > 0.0)
        }

    @Test
    fun `a night our estimator cannot estimate is reported with the reason and not scored`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addReference(start, heartRateFrom = null)
            h.repository.addHeartRate(OHEALTH, start, start + 2 * HOUR_MS, bpm = cyclingBpm(start))
            val nights = success(h.referenceComparison.findReferenceNights(NOON - 7 * DAY_MS, NOON)).nights

            val report = h.referenceComparison.compareWithCurrentSettings(nights)

            val night = report.nights.single()
            assertNull(night.score)
            assertTrue(checkNotNull(night.notEstimatedReason).startsWith("LOW_COVERAGE"))
            assertEquals(0, report.scoredNights)
            assertNull(report.kappa)
        }

    @Test
    fun `without the sleep permission nothing is read`() =
        harnessTest(dir) { h ->
            h.repository.addReference(nightStart(1))
            h.repository.revoke(HealthPermissions.READ_SLEEP)

            val result = h.referenceComparison.findReferenceNights(NOON - 7 * DAY_MS, NOON)

            assertEquals(BlockedReason.PERMISSION_MISSING, assertInstanceOf(UseCaseResult.Blocked::class.java, result).reason)
        }
}
