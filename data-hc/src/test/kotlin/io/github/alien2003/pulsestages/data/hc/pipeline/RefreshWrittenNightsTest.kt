package io.github.alien2003.pulsestages.data.hc.pipeline

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.data.hc.HcSleepSession
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.cyclingBpm
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.InputChange
import io.github.alien2003.pulsestages.data.local.db.InputUpdate
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import io.github.alien2003.pulsestages.data.local.sync.SyncOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Minutes after the session start up to which [addPartialNight] adds heart rate by default. The fake estimator gives an
 * epoch data when a sample lies within 5 min of it, so the samples up to minute 306 cover epochs 0..311 of an 8-h
 * session: coverage 312 / 480 = 0.65, with 154 samples inside the session (240 once the rest arrived).
 */
const val PARTIAL_HR_MIN: Long = 307

/**
 * Adds a sleep session of [packageName] whose heart rate has only partly reached Health Connect: the samples [addNight]
 * would add (every 2 min from 30 min before the start, [cyclingBpm]), but only those before [untilMin] minutes after the
 * start. With an odd [untilMin] the fake estimator's coverage is `(untilMin + 5) / session minutes` (0.65 for the
 * default, 0.5 for 235 on an 8-h session).
 */
fun FakeHealthRepository.addPartialNight(
    packageName: String,
    startMs: Long,
    durationMs: Long = 8 * HOUR_MS,
    untilMin: Long = PARTIAL_HR_MIN,
): HcSleepSession {
    val session = addSleepSession(packageName, startMs, startMs + durationMs)
    addHeartRate(packageName, startMs - 30 * MINUTE_MS, startMs + untilMin * MINUTE_MS, bpm = cyclingBpm(startMs))
    return session
}

/**
 * The heart rate of [session] that [addPartialNight] (with [fromMin] as its `untilMin`) left out: from then on, the
 * samples [addNight] would have added, so that the night ends up with exactly [addNight]'s heart rate.
 */
fun FakeHealthRepository.addRestOfHeartRate(
    session: HcSleepSession,
    fromMin: Long = PARTIAL_HR_MIN,
) {
    val firstMs = session.startMs + (fromMin + 1) / 2 * 2 * MINUTE_MS
    addHeartRate(session.packageName, firstMs, session.endMs + 30 * MINUTE_MS, bpm = cyclingBpm(session.startMs))
}

/** The value of [key] in the notes of one of our records ([SessionText.notes]). */
fun HcSleepSession.noteValue(key: String): String =
    Json
        .parseToJsonElement(checkNotNull(notes))
        .jsonObject
        .getValue(key)
        .jsonPrimitive.content

/**
 * Health Connect holds exactly the records of the WRITTEN nights, each with the version, record id, coverage and
 * heart-rate sample count of its ledger row: no night is half written.
 */
fun PipelineHarness.assertLedgerMatchesHealthConnect() {
    val written = ledger.allNights().filter { it.status == NightStatus.WRITTEN }.associateBy { it.clientRecordId }
    val own = repository.ownSessions().associateBy { it.clientRecordId }
    assertEquals(written.keys, own.keys, "one record per written night")
    written.values.forEach { night ->
        val record = own.getValue(night.clientRecordId)
        assertEquals(night.clientRecordVersion, record.clientRecordVersion, night.clientRecordId)
        assertEquals(night.hcRecordId, record.uuid, night.clientRecordId)
        assertEquals(SessionText.round3(night.coverage).toString(), record.noteValue("coverage"), night.clientRecordId)
        assertEquals(night.hrSamples.toString(), record.noteValue("hrSamples"), night.clientRecordId)
    }
}

/** The only night of the ledger. */
fun PipelineHarness.singleNight(): EstimatedNightEntity = ledger.allNights().single()

/** Our only record in Health Connect. */
fun PipelineHarness.singleRecord(): HcSleepSession = repository.ownSessions().single()

/** The row with the refresh bookkeeping (`inputFingerprint`, `inputCheckedAt`) left out, for comparisons. */
fun EstimatedNightEntity.withoutInputCheck(): EstimatedNightEntity = copy(inputFingerprint = null, inputCheckedAt = null)

/**
 * The refresh of written nights ([NewNightsSync], [NightPipeline.refresh]): heart rate (or steps) that reaches Health
 * Connect after a night was written makes a later sync estimate the night again and rewrite its record in place, while
 * an unchanged input costs only the reads.
 */
class RefreshWrittenNightsTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `a night written before all its heart rate arrived is rewritten in place once the rest arrives`() =
        harnessTest(dir) { h ->
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val cid = NightKeys.clientRecordId(listOf(source.uuid))
            val first = h.singleNight()
            assertEquals(0.65, first.coverage, 1e-9)
            assertEquals(154, first.hrSamples)
            assertEquals(1000L, first.clientRecordVersion)
            assertNull(first.statusReason, "a regular write has no reason")
            assertNotNull(first.inputFingerprint, "every write stores the fingerprint of its input")
            assertEquals(NOON, first.inputCheckedAt)

            h.clock.advanceBy(HOUR_MS)
            h.repository.addRestOfHeartRate(source)
            h.repository.clearCalls()
            val report = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.SUCCESS, report.outcome)
            assertEquals(1, report.pipeline.refreshed)
            assertEquals(1, report.pipeline.inputChecks)
            assertEquals(0, report.pipeline.estimated, "a refresh is not counted as a new estimate")
            assertEquals(1, h.repository.callCount(Operation.WRITE))
            assertEquals(0, h.repository.callCount(Operation.DELETE))
            val record = h.singleRecord()
            assertEquals(cid, record.clientRecordId)
            assertEquals(1001L, record.clientRecordVersion)
            assertEquals("1.0", record.noteValue("coverage"))
            assertEquals("240", record.noteValue("hrSamples"))
            val night = h.singleNight()
            assertEquals(cid, night.clientRecordId)
            assertEquals(NightStatus.WRITTEN, night.status)
            assertEquals(1001L, night.clientRecordVersion)
            assertEquals(1, night.recomputeCount)
            assertEquals(h.repository.ownRecordId(cid), night.hcRecordId)
            assertEquals(1.0, night.coverage, 1e-9)
            assertEquals(240, night.hrSamples)
            assertEquals(NOON, night.createdAt)
            assertEquals(NOON + HOUR_MS, night.updatedAt)
            assertEquals(NOON + HOUR_MS, night.inputCheckedAt)
            assertNotEquals(first.inputFingerprint, night.inputFingerprint)
            val update = InputUpdate(InputChange.MORE_HEART_RATE, 154, 240)
            assertEquals(update, night.inputUpdate)
            assertEquals(update, InputUpdate.of(night.status, night.statusReason))
            assertEquals(update.reason(), night.statusReason)
            val line = h.logger.lines.single { it.message.startsWith("updated:") }
            assertEquals("updated: more heart rate arrived (154 -> 240 heart-rate samples)", line.message)
            assertEquals(cid, line.nightKey)
            h.assertLedgerMatchesHealthConnect()
        }

    @Test
    fun `a rules fallback written at low coverage becomes a hybrid estimate when the heart rate arrives`() =
        harnessTest(dir) { h ->
            h.estimator.emulateRulesFallback = true
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0), untilMin = 235)
            h.sync.run(inBackground = false)
            val fallback = h.singleNight()
            assertEquals(0.5, fallback.coverage, 1e-9)
            assertEquals(EstimatorType.RULES, fallback.estimator)
            assertTrue(fallback.rulesFallback)
            assertEquals(listOf(WarningCode.RULES_FALLBACK_LOW_COVERAGE), fallback.warnings)
            assertEquals("RULES", h.singleRecord().noteValue("algo"))

            h.clock.advanceBy(HOUR_MS)
            h.repository.addRestOfHeartRate(source, fromMin = 235)
            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.refreshed)
            val night = h.singleNight()
            assertEquals(fallback.clientRecordId, night.clientRecordId)
            assertEquals(EstimatorType.HYBRID, night.estimator)
            assertEquals("fake-model", night.modelId)
            assertEquals(false, night.rulesFallback)
            assertTrue(night.warnings.isEmpty())
            assertEquals(1.0, night.coverage, 1e-9)
            assertEquals(fallback.clientRecordVersion + 1, night.clientRecordVersion)
            assertEquals(InputChange.MORE_HEART_RATE, night.inputUpdate?.change)
            val record = h.singleRecord()
            assertEquals("HYBRID", record.noteValue("algo"))
            assertEquals(1001L, record.clientRecordVersion)
            h.assertLedgerMatchesHealthConnect()
        }

    @Test
    fun `a written night whose input did not change is only read again, never estimated or rewritten`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            val record = h.singleRecord()
            val estimates = h.estimator.inputs.size
            h.repository.clearCalls()

            val reports =
                (1..24).map {
                    h.clock.advanceBy(HOUR_MS)
                    h.sync.run(inBackground = false)
                }

            assertEquals(24, h.repository.callCount(Operation.READ_HEART_RATE), "checked by each hourly sync while it ended < 36 h ago")
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(0, h.repository.callCount(Operation.DELETE))
            assertEquals(0, h.repository.callCount(Operation.READ_OWN_SESSIONS), "an unchanged input needs no look at our records")
            assertEquals(estimates, h.estimator.inputs.size, "not estimated again")
            assertEquals(listOf(record), h.repository.ownSessions(), "version, lastModified, stages, title and notes unchanged")
            val night = h.singleNight()
            assertEquals(written.copy(inputCheckedAt = null), night.copy(inputCheckedAt = null))
            assertEquals(NOON + 24 * HOUR_MS, night.inputCheckedAt)
            assertTrue(reports.all { it.outcome == SyncOutcome.SUCCESS })
            assertTrue(reports.all { it.pipeline.inputChecks == 1 && it.pipeline.refreshed == 0 && it.pipeline.estimated == 0 })
            assertTrue(
                reports.all { it.pipeline.found == 0 && it.pipeline.skipped == 0 && it.pipeline.upToDate == 0 },
                "a refresh check is not a night found by the run (\"Estimate new\" reports new nights only)",
            )
        }

    @Test
    fun `steps that arrive later update the fingerprint in the ledger without rewriting the record`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            val record = h.singleRecord()

            h.clock.advanceBy(HOUR_MS)
            h.repository.addSteps(OHEALTH, source.startMs + 3 * HOUR_MS, source.startMs + 3 * HOUR_MS + 5 * MINUTE_MS, 40)
            h.repository.clearCalls()
            val estimates = h.estimator.inputs.size
            val report = h.sync.run(inBackground = false)

            assertEquals(estimates + 1, h.estimator.inputs.size, "the input changed, so the night is estimated again")
            assertEquals(0, h.repository.callCount(Operation.WRITE), "the fake estimator ignores steps: the same record")
            assertEquals(listOf(record), h.repository.ownSessions())
            assertEquals(1, report.pipeline.inputChecks)
            assertEquals(0, report.pipeline.refreshed)
            val night = h.singleNight()
            assertEquals(written.withoutInputCheck(), night.withoutInputCheck(), "version, updatedAt and the estimate stay")
            assertEquals(NOON + HOUR_MS, night.inputCheckedAt)
            val before = checkNotNull(written.inputFingerprint).split('|')
            val after = checkNotNull(night.inputFingerprint).split('|')
            assertEquals(before.filter { it.startsWith("hr:") }, after.filter { it.startsWith("hr:") })
            assertEquals("steps:0:", before.single { it.startsWith("steps:") }.take("steps:0:".length))
            assertEquals("steps:1:", after.single { it.startsWith("steps:") }.take("steps:1:".length))
            assertTrue(
                h.logger.lines.any {
                    it.message == "refresh: the input changed (steps or other input data changed); the written session stays the same" &&
                        it.nightKey == night.clientRecordId
                },
            )

            h.clock.advanceBy(HOUR_MS)
            h.sync.run(inBackground = false)
            assertEquals(estimates + 1, h.estimator.inputs.size, "the new fingerprint is stored: not estimated again")
        }

    @Test
    fun `with startBeforeSource a refresh rewrite keeps the record one second before its source`() =
        harnessTest(dir) { h ->
            h.settings.set { it.copy(write = it.write.copy(startBeforeSource = true)) }
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val first = h.singleRecord()
            assertEquals(source.startMs - OutputOptions.START_BEFORE_SOURCE_MS, first.startMs)

            h.clock.advanceBy(HOUR_MS)
            h.repository.addRestOfHeartRate(source)
            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.refreshed)
            val record = h.singleRecord()
            assertEquals(first.uuid, record.uuid)
            assertEquals(1001L, record.clientRecordVersion)
            assertEquals(source.startMs - OutputOptions.START_BEFORE_SOURCE_MS, record.startMs)
            assertValidStages(record)
            assertEquals(source.startMs, h.singleNight().sessionStartMs)
            h.assertLedgerMatchesHealthConnect()
        }

    @Test
    fun `with startBeforeSource a changed input that gives the same record does not rewrite it`() =
        harnessTest(dir) { h ->
            h.settings.set { it.copy(write = it.write.copy(startBeforeSource = true)) }
            val source = h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val record = h.singleRecord()

            h.clock.advanceBy(HOUR_MS)
            h.repository.addSteps(OHEALTH, source.startMs + 3 * HOUR_MS, source.startMs + 3 * HOUR_MS + 5 * MINUTE_MS, 40)
            h.repository.clearCalls()
            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.inputChecks)
            assertEquals(0, report.pipeline.refreshed)
            assertEquals(0, h.repository.callCount(Operation.WRITE), "the shifted start is part of the same record")
            assertEquals(listOf(record), h.repository.ownSessions())
        }

    @Test
    fun `a night written by the Changes part of a sync is not read again by the refresh of the same sync`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            h.repository.addNight(OHEALTH, nightStart(0))
            // The clock stands still during the run: the night's updatedAt is exactly the run's start.
            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()

            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.estimated)
            assertEquals(0, report.pipeline.inputChecks)
            assertEquals(1, h.repository.callCount(Operation.READ_HEART_RATE))
            assertEquals(1, h.estimator.inputs.size)
        }

    @Test
    fun `a night written by the retry part of a sync is not read again by the refresh of the same sync`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(0), withHeartRate = false)
            h.sync.run(inBackground = false)
            assertEquals(NightStatus.NOT_ESTIMATED, h.singleNight().status)
            // Time passes while a run estimates, as on a device.
            h.estimator.beforeEstimate = { h.clock.advanceBy(MINUTE_MS) }
            h.clock.advanceBy(HOUR_MS)
            h.repository.addHeartRate(OHEALTH, source.startMs, source.endMs, bpm = cyclingBpm(source.startMs))
            h.repository.clearCalls()

            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.estimated)
            assertEquals(0, report.pipeline.inputChecks)
            assertEquals(1, h.repository.callCount(Operation.READ_HEART_RATE))
            assertEquals(NightStatus.WRITTEN, h.singleNight().status)
        }

    @Test
    fun `one sync checks at most 8 written nights and the next sync checks the rest`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            // Ten nights that arrive late through the Changes API (e.g. the phone was off), each with part of its heart rate.
            val sessions = (2..11).map { h.repository.addPartialNight(OHEALTH, nightStart(it)) }
            h.sync.run(inBackground = false)
            assertEquals(10, h.repository.ownSessions().size)
            sessions.forEach { h.repository.addRestOfHeartRate(it) }
            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()

            val first = h.sync.run(inBackground = false)

            val limit = NewNightsSync.MAX_REFRESH_NIGHTS_PER_RUN
            assertEquals(8, limit)
            assertEquals(limit, h.repository.callCount(Operation.READ_HEART_RATE), "one heart-rate read per checked night")
            assertEquals(1, h.repository.callCount(Operation.READ_SLEEP_SESSIONS), "one sleep-session read for all of them")
            assertEquals(limit, first.pipeline.inputChecks)
            assertEquals(limit, first.pipeline.refreshed)
            assertEquals(limit, h.repository.ownSessions().count { it.clientRecordVersion == 1001L })
            assertTrue(h.logger.lines.any { it.message == "2 more written night(s) are due for a refresh; later runs check them" })
            h.assertLedgerMatchesHealthConnect()

            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()
            val second = h.sync.run(inBackground = false)

            assertTrue(h.repository.callCount(Operation.READ_HEART_RATE) <= limit)
            assertEquals(2, second.pipeline.refreshed, "the two nights left over are checked first")
            assertTrue(h.repository.ownSessions().all { it.clientRecordVersion == 1001L })
            assertTrue(h.ledger.allNights().all { it.coverage == 1.0 && it.hrSamples == 240 })
            h.assertLedgerMatchesHealthConnect()
        }

    @Test
    fun `a quota error while reading for the refresh stops the sync without a half-written night, the next sync completes it`() =
        harnessTest(dir) { h ->
            val newer = h.repository.addPartialNight(OHEALTH, nightStart(0))
            val older = h.repository.addPartialNight(OHEALTH, nightStart(1))
            h.sync.run(inBackground = false)
            h.clock.advanceBy(HOUR_MS)
            listOf(newer, older).forEach { h.repository.addRestOfHeartRate(it) }
            // The heart-rate read of the second night the refresh checks hits the rate limit.
            h.repository.failOn(Operation.READ_HEART_RATE, HealthDataException.QuotaExceeded("rate limit"), afterCalls = 1)

            val limited = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.QUOTA_EXCEEDED, limited.outcome)
            assertTrue(limited.pipeline.quotaHit)
            assertEquals(1, limited.pipeline.refreshed)
            val versions = { h.repository.ownSessions().associate { it.startMs to it.clientRecordVersion } }
            assertEquals(listOf(1000L, 1001L), versions().values.sorted())
            val notChecked = h.ledger.allNights().single { it.clientRecordVersion == 1000L }
            assertEquals(0.65, notChecked.coverage, 1e-9)
            assertEquals(NOON, notChecked.inputCheckedAt, "not marked as checked")
            h.assertLedgerMatchesHealthConnect()

            h.clock.advanceBy(HOUR_MS)
            val completed = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.SUCCESS, completed.outcome)
            assertEquals(1, completed.pipeline.refreshed)
            assertEquals(mapOf(newer.startMs to 1001L, older.startMs to 1001L), versions())
            h.assertLedgerMatchesHealthConnect()
        }

    @Test
    fun `a quota error on the rewrite leaves the earlier record and its ledger row as they were`() =
        harnessTest(dir) { h ->
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            val record = h.singleRecord()
            h.clock.advanceBy(HOUR_MS)
            h.repository.addRestOfHeartRate(source)
            h.repository.failOn(Operation.WRITE, HealthDataException.QuotaExceeded("rate limit"))

            val limited = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.QUOTA_EXCEEDED, limited.outcome)
            assertEquals(listOf(record), h.repository.ownSessions())
            assertEquals(written, h.singleNight(), "not even marked as checked: the next sync tries again")

            h.clock.advanceBy(HOUR_MS)
            val retried = h.sync.run(inBackground = false)
            assertEquals(1, retried.pipeline.refreshed)
            assertEquals(1001L, h.singleRecord().clientRecordVersion)
            h.assertLedgerMatchesHealthConnect()
        }

    @Test
    fun `a late night is checked about 1, 2, 4, 8, 16 and 32 hours after it was first seen`() =
        harnessTest(dir) { h ->
            h.sync.run(inBackground = false)
            h.repository.addNight(OHEALTH, nightStart(3))
            h.sync.run(inBackground = false)
            assertEquals(1, h.repository.ownSessions().size)
            h.repository.clearCalls()

            val checkedAfterHours = mutableListOf<Int>()
            (1..40).forEach { hour ->
                h.clock.advanceBy(HOUR_MS)
                val reads = h.repository.callCount(Operation.READ_HEART_RATE)
                h.sync.run(inBackground = false)
                if (h.repository.callCount(Operation.READ_HEART_RATE) > reads) checkedAfterHours += hour
            }

            assertEquals(listOf(1, 2, 4, 8, 16, 32), checkedAfterHours)
            assertEquals(0, h.repository.callCount(Operation.WRITE))
        }

    @Test
    fun `a night seen when it ended is checked by every hourly sync for 36 hours after its end and then never again`() =
        harnessTest(dir) { h ->
            // Ended 5 h before the first sync.
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            h.repository.clearCalls()

            val checkedAfterHours = mutableListOf<Int>()
            (1..40).forEach { hour ->
                h.clock.advanceBy(HOUR_MS)
                val reads = h.repository.callCount(Operation.READ_HEART_RATE)
                h.sync.run(inBackground = false)
                if (h.repository.callCount(Operation.READ_HEART_RATE) > reads) checkedAfterHours += hour
            }
            assertEquals((1..30).toList(), checkedAfterHours)

            h.repository.addRestOfHeartRate(source)
            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()
            h.sync.run(inBackground = false)

            assertEquals(0, h.repository.callCount(Operation.READ_HEART_RATE), "older nights are left to an explicit recompute")
            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(1000L, h.singleRecord().clientRecordVersion)
        }

    @Test
    fun `a row written before fingerprints existed is taken as unchanged when the heart-rate count and coverage match`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            val record = h.singleRecord()
            h.ledger.putNight(written.copy(inputFingerprint = null, inputCheckedAt = null))

            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()
            h.sync.run(inBackground = false)

            assertEquals(0, h.repository.callCount(Operation.WRITE))
            assertEquals(listOf(record), h.repository.ownSessions())
            val night = h.singleNight()
            assertEquals(written.inputFingerprint, night.inputFingerprint, "the fingerprint is stored from now on")
            assertEquals(NOON + HOUR_MS, night.inputCheckedAt)
            assertEquals(written.withoutInputCheck(), night.withoutInputCheck())

            val estimates = h.estimator.inputs.size
            h.clock.advanceBy(HOUR_MS)
            h.sync.run(inBackground = false)
            assertEquals(estimates, h.estimator.inputs.size, "compared by fingerprint now: not estimated again")
        }

    @Test
    fun `a row written before fingerprints existed is rewritten when its heart-rate count changed`() =
        harnessTest(dir) { h ->
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            h.ledger.putNight(written.copy(inputFingerprint = null, inputCheckedAt = null))

            h.clock.advanceBy(HOUR_MS)
            h.repository.addRestOfHeartRate(source)
            val report = h.sync.run(inBackground = false)

            assertEquals(1, report.pipeline.refreshed)
            val night = h.singleNight()
            assertEquals(1001L, night.clientRecordVersion)
            assertEquals(InputUpdate(InputChange.MORE_HEART_RATE, 154, 240), night.inputUpdate)
            assertNotNull(night.inputFingerprint)
            h.assertLedgerMatchesHealthConnect()
        }

    @Test
    fun `a row written before fingerprints existed with an unchanged count and coverage is kept even if samples around it changed`() =
        harnessTest(dir) { h ->
            val source = h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val written = h.singleNight()
            h.ledger.putNight(written.copy(inputFingerprint = null, inputCheckedAt = null))

            // A sample in the context before the session: neither the count inside the session nor the coverage changes.
            h.repository.addHeartRate(OHEALTH, source.startMs - 3 * MINUTE_MS, source.startMs - 2 * MINUTE_MS, bpm = { 40.0 })
            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()
            h.sync.run(inBackground = false)

            assertEquals(0, h.repository.callCount(Operation.WRITE), "without a stored fingerprint only the counts can be compared")
            val night = h.singleNight()
            assertNotEquals(written.inputFingerprint, night.inputFingerprint, "the fingerprint of the current input is stored")
            assertEquals(written.withoutInputCheck(), night.withoutInputCheck())
        }

    @Test
    fun `under RECOMPUTE_ALL an up-to-date night is not rewritten by every sync, only once when its heart rate changes`() =
        harnessTest(dir) { h ->
            h.settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.RECOMPUTE_ALL)) }
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            h.repository.clearCalls()

            repeat(3) {
                h.clock.advanceBy(HOUR_MS)
                h.sync.run(inBackground = false)
            }
            assertEquals(0, h.repository.callCount(Operation.WRITE), "unchanged input: no rewrite")
            assertEquals(3, h.repository.callCount(Operation.READ_HEART_RATE))

            h.clock.advanceBy(HOUR_MS)
            h.repository.addRestOfHeartRate(source)
            val changed = h.sync.run(inBackground = false)
            assertEquals(1, changed.pipeline.refreshed)
            repeat(3) {
                h.clock.advanceBy(HOUR_MS)
                h.sync.run(inBackground = false)
            }

            assertEquals(1, h.repository.callCount(Operation.WRITE), "rewritten once")
            assertEquals(1001L, h.singleRecord().clientRecordVersion)
            h.assertLedgerMatchesHealthConnect()
        }

    @Test
    fun `a refresh rewrite interrupted before its ledger transaction is taken over by the next check, not written again`() =
        harnessTest(dir) { h ->
            val source = h.repository.addPartialNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            val before = h.singleNight()
            h.clock.advanceBy(HOUR_MS)
            h.repository.addRestOfHeartRate(source)
            assertEquals(
                1,
                h.sync
                    .run(inBackground = false)
                    .pipeline.refreshed,
            )
            val rewritten = h.singleRecord()
            assertEquals(1001L, rewritten.clientRecordVersion)
            // The process died after the insert: the ledger transaction of the rewrite never happened.
            h.ledger.putNight(before)

            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()
            val report = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.SUCCESS, report.outcome)
            assertEquals(0, h.repository.callCount(Operation.WRITE), "our record already holds this estimate")
            assertEquals(listOf(rewritten), h.repository.ownSessions())
            val night = h.singleNight()
            assertEquals(1001L, night.clientRecordVersion)
            assertEquals(1, night.recomputeCount)
            assertEquals(rewritten.uuid, night.hcRecordId)
            assertEquals(rewritten.lastModifiedMs, night.updatedAt, "the time of the interrupted write")
            assertEquals(InputUpdate(InputChange.MORE_HEART_RATE, 154, 240), night.inputUpdate)
            assertEquals(240, night.hrSamples)
            assertTrue(h.logger.lines.any { "(written by an earlier run that was interrupted)" in it.message })
            h.assertLedgerMatchesHealthConnect()
        }

    @Test
    fun `with more recent written sessions than the per-run limit, the checks rotate through all of them`() =
        harnessTest(dir) { h ->
            h.settings.set { it.copy(sources = it.sources.copy(minSessionMin = 30)) }
            // Nine one-hour sessions that all ended in the last 36 h.
            repeat(9) { k -> h.repository.addNight(OHEALTH, NOON - 30 * HOUR_MS + k * 3 * HOUR_MS, durationMs = HOUR_MS) }
            h.sync.run(inBackground = false)
            assertEquals(9, h.ledger.allNights().count { it.status == NightStatus.WRITTEN })

            h.clock.advanceBy(HOUR_MS)
            h.repository.clearCalls()
            h.sync.run(inBackground = false)
            assertEquals(8, h.repository.callCount(Operation.READ_HEART_RATE))
            val left = h.ledger.allNights().single { it.inputCheckedAt == NOON }

            h.clock.advanceBy(HOUR_MS)
            h.sync.run(inBackground = false)

            assertEquals(
                NOON + 2 * HOUR_MS,
                h.ledger
                    .allNights()
                    .single { it.id == left.id }
                    .inputCheckedAt,
                "checked first",
            )
            assertTrue(h.ledger.allNights().all { checkNotNull(it.inputCheckedAt) > NOON }, "every night was checked")
            assertEquals(0, h.repository.callCount(Operation.WRITE))
        }

    @Test
    fun `a failed read of the refresh window keeps the report of the nights the same sync wrote before`() =
        harnessTest(dir) { h ->
            h.repository.addNight(OHEALTH, nightStart(0))
            h.sync.run(inBackground = false)
            h.clock.advanceBy(HOUR_MS)
            // A night that arrives late; the refresh's read of its window (the second sleep read of the sync) fails.
            h.repository.addNight(OHEALTH, nightStart(3))
            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.QuotaExceeded("rate limit"), afterCalls = 1)

            val limited = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.QUOTA_EXCEEDED, limited.outcome)
            assertEquals(1, limited.pipeline.estimated, "the night written before the refresh is still reported")
            assertEquals(1, limited.pages)
            assertEquals(2, h.repository.ownSessions().size)

            h.clock.advanceBy(HOUR_MS)
            h.repository.addNight(OHEALTH, nightStart(5))
            h.repository.failOn(Operation.READ_SLEEP_SESSIONS, HealthDataException.Transient("binder died"), afterCalls = 1)
            val transient = h.sync.run(inBackground = false)

            assertEquals(SyncOutcome.SUCCESS, transient.outcome, "a transient refresh failure is left to the next late-data pass")
            assertEquals(1, transient.pipeline.estimated)
            assertTrue(h.logger.lines.any { it.message.startsWith("refresh of written nights stopped") })
        }
}
