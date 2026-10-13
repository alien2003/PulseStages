package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.SleepStage
import io.github.alien2003.pulsestages.algo.model.StageInterval
import io.github.alien2003.pulsestages.data.hc.EstimatedSessionWrite
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.OWN
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.SourceOutcome
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Processes every night starting in the last 30 days with the current settings. */
suspend fun PipelineHarness.processRecent(trigger: PipelineTrigger = PipelineTrigger.NEW_NIGHTS): PipelineReport {
    val now = clock.millis()
    val current = settings.current
    return pipeline.process(windowReader.nightsInWindow(now - 30 * DAY_MS, now, current), current, trigger, repository.granted.toSet())
}

/** Asserts the invariants of a written hypnogram. */
fun assertValidStages(session: HcSleepSession) {
    val stages = session.stages
    assertTrue(stages.isNotEmpty())
    assertEquals(session.startMs, stages.first().startMs, "stages start at the session start")
    assertEquals(session.endMs, stages.last().endMs, "stages end at the session end")
    stages.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs, "contiguous and sorted") }
    stages.forEach { stage ->
        assertTrue(stage.endMs - stage.startMs >= MINUTE_MS, "every stage is at least one epoch: $stage")
        assertTrue(stage.type in setOf(0, 1, 4, 5, 6), "only STAGE_TYPE_* values we write: $stage")
    }
}

class NightPipelineTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a night is written once with valid stages, title, notes and explicit offsets`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(1), startZoneOffsetSec = 10_800, endZoneOffsetSec = 7_200)

            val report = h.processRecent()

            assertEquals(PipelineReport(found = 1, estimated = 1), report)
            val written = h.repository.ownSessions().single()
            val cid = NightKeys.clientRecordId(listOf(source.uuid))
            assertEquals(cid, written.clientRecordId)
            assertEquals(1000L, written.clientRecordVersion)
            assertEquals(source.startMs, written.startMs)
            assertEquals(source.endMs, written.endMs)
            assertEquals(10_800, written.startZoneOffsetSec)
            assertEquals(7_200, written.endZoneOffsetSec)
            assertValidStages(written)
            assertTrue(written.stages.map { it.type }.containsAll(listOf(4, 5, 6)), "the pattern yields LIGHT, DEEP and REM")
            assertEquals(PipelineHarness.DEFAULT_TITLE, written.title)

            val notes = Json.parseToJsonElement(written.notes.orEmpty()).jsonObject
            assertEquals(
                listOf("algo", "version", "paramsHash", "coverage", "confidence", "sourceUuids", "hrSamples", "calibrated"),
                notes.keys.toList(),
            )
            assertEquals("HYBRID", notes.getValue("algo").jsonPrimitive.content)
            assertEquals("1", notes.getValue("version").jsonPrimitive.content)
            assertEquals(
                h.pipeline.currentParamsHash(h.settings.current, h.repository.granted),
                notes.getValue("paramsHash").jsonPrimitive.content,
            )
            assertEquals("1.0", notes.getValue("coverage").jsonPrimitive.content)
            assertEquals(listOf(source.uuid), notes.getValue("sourceUuids").jsonArray.map { it.jsonPrimitive.content })
            assertEquals("240", notes.getValue("hrSamples").jsonPrimitive.content)
            assertEquals("false", notes.getValue("calibrated").jsonPrimitive.content)
        }

    @Test
    fun `the written night is recorded in the ledger with its sources and baseline`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(1))
            h.processRecent()
            val written = h.repository.ownSessions().single()
            val cid = NightKeys.clientRecordId(listOf(source.uuid))

            val night = h.ledger.allNights().single()
            assertEquals(NightStatus.WRITTEN, night.status)
            assertEquals(h.repository.ownRecordId(cid), night.hcRecordId)
            assertEquals(1000L, night.clientRecordVersion)
            assertEquals(listOf(source.uuid), night.sourceUuids)
            assertEquals(480.0, night.awakeMin + night.lightMin + night.deepMin + night.remMin + night.unknownMin, 1e-9)
            assertEquals(written.stages.map { StageInterval(it.startMs, it.endMs, StageMapping.fromHcType(it.type)) }, night.stages)
            assertEquals(
                SourceOutcome.ESTIMATED,
                h.ledger
                    .allSources()
                    .single()
                    .outcome,
            )
            assertEquals(
                cid,
                h.ledger
                    .allSources()
                    .single()
                    .nightClientRecordId,
            )
            assertEquals(
                49.0,
                h.ledger
                    .allBaselines()
                    .single()
                    .nadirBpm,
            )
            assertEquals(1, h.estimator.inputs.size)
            assertTrue(
                h.estimator.inputs
                    .single()
                    .heartRate
                    .first()
                    .timeMs < source.startMs,
                "a context margin is read before the session",
            )
        }

    @Test
    fun `a double run writes exactly one record with one clientRecordId`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.processRecent()
            val second = h.processRecent()

            assertEquals(PipelineReport(found = 1, skipped = 1, upToDate = 1), second)
            assertEquals(1, h.repository.ownSessions().size)
            assertEquals(
                1000L,
                h.repository
                    .ownSessions()
                    .single()
                    .clientRecordVersion,
            )
            assertEquals(1, h.repository.callCount(Operation.WRITE))
            assertEquals(1, h.estimator.inputs.size, "an up-to-date night is not estimated again")
        }

    @Test
    fun `RECOMPUTE_ALL rewrites the same record and the version grows`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.processRecent()
            val cid =
                h.repository
                    .ownSessions()
                    .single()
                    .clientRecordId
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.RECOMPUTE_ALL)) }

            h.processRecent()
            h.processRecent()

            val own = h.repository.ownSessions().single()
            assertEquals(cid, own.clientRecordId)
            assertEquals(1002L, own.clientRecordVersion)
            val night = h.ledger.allNights().single()
            assertEquals(2, night.recomputeCount)
            assertEquals(1002L, night.clientRecordVersion)
        }

    @Test
    fun `SKIP_SAME_VERSION leaves stale nights untouched until an explicit recompute`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.processRecent()
            val before = h.repository.ownSessions().single()
            val oldHash = h.pipeline.currentParamsHash(h.settings.current, h.repository.granted)
            h.settings.set { it.copy(algo = it.algo.copy(preprocess = it.algo.preprocess.copy(smoothWindowEpochs = 7))) }
            val newHash = h.pipeline.currentParamsHash(h.settings.current, h.repository.granted)
            assertNotEquals(oldHash, newHash)

            val report = h.processRecent()

            assertEquals(PipelineReport(found = 1, skipped = 1, stale = 1), report)
            assertEquals(before, h.repository.ownSessions().single())
            assertTrue(
                h.ledger
                    .allNights()
                    .single()
                    .isStale(1, newHash),
            )

            val recompute = h.recomputer.recomputeStale(inBackground = false)

            assertEquals(1, recompute.estimated)
            val after = h.repository.ownSessions().single()
            assertEquals(1001L, after.clientRecordVersion)
            assertTrue(after.notes.orEmpty().contains(newHash))
            assertFalse(
                h.ledger
                    .allNights()
                    .single()
                    .isStale(1, newHash),
            )
        }

    @Test
    fun `NEVER keeps existing outputs, even on an explicit recompute or when a fragment arrives`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, durationMs = 4 * HOUR_MS)
            h.processRecent()
            val before = h.repository.ownSessions().single()
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.NEVER)) }

            val recompute = h.recomputer.recompute(h.ledger.allNights().map { it.clientRecordId }, inBackground = false)
            assertEquals(0, recompute.estimated)

            h.repository.addNight(OHEALTH, start + 4 * HOUR_MS + 10 * MINUTE_MS, durationMs = 3 * HOUR_MS)
            val report = h.processRecent()

            assertEquals(0, report.estimated)
            assertEquals(listOf(before), h.repository.ownSessions())
            assertEquals(listOf(NightStatus.WRITTEN), h.ledger.allNights().map { it.status })
        }

    @Test
    fun `a fragment that arrives later supersedes the earlier night - one output remains`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            val first = h.repository.addNight(OHEALTH, start, durationMs = 4 * HOUR_MS)
            h.processRecent()
            val oldCid =
                h.repository
                    .ownSessions()
                    .single()
                    .clientRecordId

            val second = h.repository.addNight(OHEALTH, start + 4 * HOUR_MS + 20 * MINUTE_MS, durationMs = 3 * HOUR_MS + 40 * MINUTE_MS)
            val report = h.processRecent()

            assertEquals(1, report.estimated)
            assertEquals(1, report.superseded)
            val own = h.repository.ownSessions().single()
            val newCid = NightKeys.clientRecordId(listOf(first.uuid, second.uuid))
            assertEquals(newCid, own.clientRecordId)
            assertEquals(start, own.startMs)
            assertEquals(second.endMs, own.endMs)
            assertValidStages(own)
            val gap = own.stages.single { it.startMs <= first.endMs && it.endMs > first.endMs }
            assertEquals(1, gap.type, "the gap between the fragments is AWAKE")

            val old = h.ledger.allNights().single { it.clientRecordId == oldCid }
            assertEquals(NightStatus.SUPERSEDED, old.status)
            assertEquals("superseded by $newCid", old.statusReason)
            assertNull(old.hcRecordId)
            assertEquals(listOf(newCid), h.ledger.allBaselines().map { it.clientRecordId })
            assertEquals(
                setOf(newCid),
                h.ledger
                    .allSources()
                    .map { it.nightClientRecordId }
                    .toSet(),
            )
        }

    @Test
    fun `naps are skipped and recorded`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1) + 14 * HOUR_MS, durationMs = 60 * MINUTE_MS)

            val report = h.processRecent()

            assertEquals(PipelineReport(found = 1, skipped = 1), report)
            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(
                SourceOutcome.NAP,
                h.ledger
                    .allSources()
                    .single()
                    .outcome,
            )
        }

    @Test
    fun `sessions with stages are skipped unless everything is recomputed`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, stages = listOf(HcStage(start, start + 8 * HOUR_MS, 4)))

            assertEquals(PipelineReport(found = 1, skipped = 1), h.processRecent())
            assertEquals(
                SourceOutcome.HAS_STAGES,
                h.ledger
                    .allSources()
                    .single()
                    .outcome,
            )
            assertTrue(h.repository.ownSessions().isEmpty())

            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.RECOMPUTE_ALL)) }
            assertEquals(1, h.processRecent().estimated)
            assertEquals(1, h.repository.ownSessions().size)

            h.clock.advanceBy(MINUTE_MS)
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.SKIP_SAME_VERSION)) }
            assertEquals(0, h.processRecent().superseded, "the stages were there when we estimated: a deliberate RECOMPUTE_ALL output")
            assertEquals(1, h.repository.ownSessions().size)
        }

    @Test
    fun `our own sessions are never used as sources`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.settings.set {
                it.copy(
                    sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OWN), sourcePriority = listOf(OHEALTH, OWN)),
                )
            }

            h.processRecent()
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.RECOMPUTE_ALL)) }
            h.processRecent()

            assertEquals(1, h.repository.ownSessions().size)
            assertTrue(h.ledger.allNights().none { it.sourcePackage == OWN })
        }

    @Test
    fun `the higher-priority source wins and a priority change moves the single output`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start)
            val other = h.repository.addNight(OTHER_APP, start + 20 * MINUTE_MS, durationMs = 7 * HOUR_MS)
            h.settings.set {
                it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OHEALTH, OTHER_APP)))
            }

            val first = h.processRecent()

            assertEquals(PipelineReport(found = 2, estimated = 1, skipped = 1), first)
            assertEquals(
                OHEALTH,
                h.ledger
                    .allNights()
                    .single()
                    .sourcePackage,
            )
            assertEquals(
                SourceOutcome.LOWER_PRIORITY_DUPLICATE,
                h.ledger
                    .allSources()
                    .single { it.uuid == other.uuid }
                    .outcome,
            )

            h.settings.set { it.copy(sources = it.sources.copy(sourcePriority = listOf(OTHER_APP, OHEALTH))) }
            val second = h.processRecent(PipelineTrigger.BACKFILL)

            assertEquals(1, second.estimated)
            assertEquals(1, second.superseded)
            assertEquals(listOf(NightKeys.clientRecordId(listOf(other.uuid))), h.repository.ownSessions().map { it.clientRecordId })
            assertEquals(
                mapOf(OHEALTH to NightStatus.SUPERSEDED, OTHER_APP to NightStatus.WRITTEN),
                h.ledger.allNights().associate { it.sourcePackage to it.status },
            )
        }

    @Test
    fun `heart rate comes from the sleep source unless hrSources is ANY`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, withHeartRate = false)
            h.repository.addHeartRate(OTHER_APP, start - 30 * MINUTE_MS, start + 9 * HOUR_MS)

            val same = h.processRecent()
            assertEquals(PipelineReport(found = 1, skipped = 1, notEstimated = 1), same)
            assertEquals(
                NightStatus.NOT_ESTIMATED,
                h.ledger
                    .allNights()
                    .single()
                    .status,
            )
            assertTrue(
                h.ledger
                    .allNights()
                    .single()
                    .statusReason
                    .orEmpty()
                    .startsWith("NO_HR_DATA"),
            )

            h.settings.set { it.copy(sources = it.sources.copy(hrSources = HrSourcesMode.ANY)) }
            assertEquals(1, h.processRecent().estimated)
        }

    @Test
    fun `a night without heart rate is estimated later when its heart rate arrives`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, withHeartRate = false)
            h.processRecent()
            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(
                SourceOutcome.NOT_ESTIMATED,
                h.ledger
                    .allSources()
                    .single()
                    .outcome,
            )

            h.repository.addHeartRate(OHEALTH, start, start + 8 * HOUR_MS)
            assertEquals(1, h.processRecent().estimated)
            assertEquals(
                1000L,
                h.repository
                    .ownSessions()
                    .single()
                    .clientRecordVersion,
                "the first write is not a rewrite",
            )
            assertEquals(
                NightStatus.WRITTEN,
                h.ledger
                    .allNights()
                    .single()
                    .status,
            )
        }

    @Test
    fun `one failing night does not stop the others and is retried next time`() =
        harnessTest(dir) { h ->
            val starts = listOf(nightStart(3), nightStart(2), nightStart(1))
            starts.forEach { h.repository.addNight(OHEALTH, it) }
            h.estimator.failWhen = { input -> if (input.sessionStartMs == starts[1]) IllegalStateException("estimator bug") else null }

            val report = h.processRecent()

            assertEquals(PipelineReport(found = 3, estimated = 2, failed = 1), report)
            val failed = h.ledger.allNights().single { it.sessionStartMs == starts[1] }
            assertEquals(NightStatus.FAILED, failed.status)
            assertTrue(failed.statusReason.orEmpty().contains("estimator bug"))
            assertEquals(
                SourceOutcome.FAILED,
                h.ledger
                    .allSources()
                    .single { it.startMs == starts[1] }
                    .outcome,
            )
            assertTrue(h.logger.lines.any { it.nightKey == failed.clientRecordId && it.throwable != null })

            h.estimator.failWhen = { null }
            assertEquals(PipelineReport(found = 3, estimated = 1, skipped = 2, upToDate = 2), h.processRecent())
            assertEquals(3, h.repository.ownSessions().size)
        }

    @Test
    fun `an invalid hypnogram fails the night and nothing is written`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            h.estimator.stagesOverride = { input, _ ->
                listOf(
                    StageInterval(input.sessionStartMs, input.sessionStartMs + HOUR_MS, SleepStage.LIGHT),
                    StageInterval(input.sessionStartMs + 2 * HOUR_MS, input.sessionEndMs, SleepStage.DEEP),
                )
            }

            val report = h.processRecent()

            assertEquals(1, report.failed)
            assertTrue(h.repository.ownSessions().isEmpty())
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertTrue(
                h.ledger
                    .allNights()
                    .single()
                    .statusReason
                    .orEmpty()
                    .contains("gap after stage 0"),
            )
        }

    @Test
    fun `a quota error mid-run stops the run without crashing, the next run finishes`() =
        harnessTest(dir) { h ->
            (1..5).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            h.repository.quotaAfter(8)

            val report = h.processRecent()

            assertTrue(report.quotaHit)
            assertTrue(report.estimated in 1..4, "some nights were written before the quota hit: $report")
            assertEquals(0, report.failed)

            h.repository.clearFailures()
            val rest = h.processRecent()
            assertEquals(5 - report.estimated, rest.estimated)
            assertEquals(5, h.repository.ownSessions().size)
            assertEquals(
                5,
                h.repository
                    .ownSessions()
                    .map { it.clientRecordId }
                    .distinct()
                    .size,
            )
        }

    @Test
    fun `a permission revoked mid-run stops the run`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(2))
            h.repository.addNight(OHEALTH, nightStart(1))
            h.repository.failOn(Operation.WRITE, HealthDataException.PermissionDenied("revoked"))

            val report = h.processRecent()

            assertEquals(BlockedReason.PERMISSION_MISSING, report.blockedReason)
            assertEquals(0, report.estimated)
            assertTrue(h.ledger.allNights().none { it.status == NightStatus.FAILED }, "a permission problem is not a night failure")
        }

    @Test
    fun `awakeAsLight writes no AWAKE stages and changes the params hash`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, durationMs = 4 * HOUR_MS)
            h.repository.addNight(OHEALTH, start + 4 * HOUR_MS + 20 * MINUTE_MS, durationMs = 3 * HOUR_MS)
            val plainHash = h.pipeline.currentParamsHash(h.settings.current, h.repository.granted)
            h.settings.set { it.copy(write = it.write.copy(awakeAsLight = true)) }

            h.processRecent()

            val own = h.repository.ownSessions().single()
            assertValidStages(own)
            assertTrue(own.stages.none { it.type == 1 })
            assertEquals(4, own.stages.single { it.startMs <= start + 4 * HOUR_MS && it.endMs > start + 4 * HOUR_MS }.type)
            assertNotEquals(
                plainHash,
                h.ledger
                    .allNights()
                    .single()
                    .paramsHash,
            )
            assertEquals(
                0.0,
                h.ledger
                    .allNights()
                    .single()
                    .awakeMin,
            )
        }

    @Test
    fun `a long heart-rate gap is written as UNKNOWN inside the session`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addSleepSession(OHEALTH, start, start + 8 * HOUR_MS)
            h.repository.addHeartRate(OHEALTH, start - 30 * MINUTE_MS, start + 3 * HOUR_MS)
            h.repository.addHeartRate(OHEALTH, start + 5 * HOUR_MS, start + 9 * HOUR_MS)

            h.processRecent()

            val own = h.repository.ownSessions().single()
            assertValidStages(own)
            val gap = own.stages.single { it.type == 0 }
            assertTrue(gap.startMs > start + 3 * HOUR_MS && gap.endMs < start + 5 * HOUR_MS, "UNKNOWN covers the gap: $gap")
            assertTrue(
                h.ledger
                    .allNights()
                    .single()
                    .unknownMin > 100,
            )
        }

    @Test
    fun `optional channels are read only when granted and enabled`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start)
            h.repository.addSpo2(OHEALTH, start + HOUR_MS, 96.0)
            h.repository.addHrv(OHEALTH, start + HOUR_MS, 40.0)
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.RECOMPUTE_ALL)) }

            h.processRecent()
            assertEquals(
                1,
                h.estimator.inputs
                    .last()
                    .spo2.size,
            )
            assertEquals(
                1,
                h.estimator.inputs
                    .last()
                    .hrv.size,
            )

            h.repository.revoke(HealthPermissions.READ_OXYGEN_SATURATION)
            h.repository.clearCalls()
            h.processRecent()
            assertTrue(
                h.estimator.inputs
                    .last()
                    .spo2
                    .isEmpty(),
            )
            assertEquals(0, h.repository.callCount(Operation.READ_SPO2))
            assertEquals(1, h.repository.callCount(Operation.READ_HRV))

            h.settings.set { it.copy(algo = it.algo.copy(features = it.algo.features.copy(useHrv = false))) }
            h.repository.clearCalls()
            h.processRecent()
            assertEquals(0, h.repository.callCount(Operation.READ_HRV))
            assertEquals(1, h.repository.ownSessions().size)
        }

    @Test
    fun `the rolling baseline comes from the previous nights`() =
        harnessTest(dir) { h ->
            (4 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }

            h.processRecent()

            assertEquals(listOf(null, null, null, 49.0), h.estimator.inputs.map { it.baselineNadirBpm })
        }

    @Test
    fun `missing zone offsets fall back to the device zone`() =
        runTest {
            val h =
                PipelineHarness(backgroundScope, dir, StandardTestDispatcher(testScheduler), startMs = NOON, fallbackZoneOffsetSec = 7_200)
            h.repository.addNight(OHEALTH, nightStart(1), startZoneOffsetSec = null, endZoneOffsetSec = null)

            h.processRecent()

            val own = h.repository.ownSessions().single()
            assertEquals(7_200, own.startZoneOffsetSec)
            assertEquals(7_200, own.endZoneOffsetSec)
        }

    @Test
    fun `reinstall - empty ledger and a higher version already in Health Connect - one record with our version`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(1))
            val cid = NightKeys.clientRecordId(listOf(source.uuid))
            h.repository.preloadOwnSession(
                EstimatedSessionWrite(
                    cid,
                    1007,
                    source.startMs,
                    source.endMs,
                    0,
                    0,
                    listOf(HcStage(source.startMs, source.endMs, 4)),
                    "old",
                    "{}",
                ),
            )

            h.processRecent()

            val own = h.repository.ownSessions().single()
            assertEquals(1008L, own.clientRecordVersion)
            assertEquals(PipelineHarness.DEFAULT_TITLE, own.title)
            val night = h.ledger.allNights().single()
            assertEquals(1008L, night.clientRecordVersion)
            assertEquals(8, night.recomputeCount)
        }

    @Test
    fun `concurrent runs never interleave on the ledger`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(1))
            var active = 0
            var maxActive = 0
            h.estimator.beforeEstimate = {
                active++
                maxActive = maxOf(maxActive, active)
                delay(1_000)
                active--
            }

            val first = launch { h.processRecent() }
            val second = launch { h.processRecent() }
            first.join()
            second.join()

            assertEquals(1, maxActive)
            assertEquals(1, h.estimator.inputs.size, "the second run saw the first run's result")
            assertEquals(1, h.repository.ownSessions().size)
            assertNotNull(
                h.ledger
                    .allNights()
                    .single()
                    .hcRecordId,
            )
        }

    @Test
    fun `other apps' sessions are never modified`() =
        harnessTest(dir) { h ->
            (3 downTo 1).forEach { h.repository.addNight(OHEALTH, nightStart(it)) }
            val before = h.repository.allSessions().filter { it.packageName == OHEALTH }
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.RECOMPUTE_ALL)) }

            h.processRecent()
            h.processRecent()
            h.eraser.eraseAll()

            assertEquals(before, h.repository.allSessions().filter { it.packageName == OHEALTH })
            assertTrue(h.repository.ownSessions().isEmpty())
        }
}
