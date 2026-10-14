package io.github.alien2003.pulsestages.data.hc.usecase

import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.NotEstimatedReason
import io.github.alien2003.pulsestages.algo.model.TimeSpan
import io.github.alien2003.pulsestages.data.hc.HcAvailability
import io.github.alien2003.pulsestages.data.hc.HealthDataException
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository.Operation
import io.github.alien2003.pulsestages.data.hc.fake.FakeNightEstimator
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.pipeline.BlockedReason
import io.github.alien2003.pulsestages.data.hc.pipeline.StageMapping
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.cyclingBpm
import io.github.alien2003.pulsestages.data.hc.testutil.harnessTest
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightEntity
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs

class NightInspectorTest {
    @TempDir
    lateinit var dir: File

    private suspend fun PipelineHarness.writtenNight(daysAgo: Int = 1): EstimatedNightEntity {
        repository.addNight(OHEALTH, nightStart(daysAgo))
        sync.run(inBackground = false)
        return ledger.allNights().single { it.status == NightStatus.WRITTEN && it.sessionStartMs == nightStart(daysAgo) }
    }

    private fun <T> success(result: UseCaseResult<T>): T =
        when (result) {
            is UseCaseResult.Success -> result.value
            else -> fail("expected a success, got $result")
        }

    @Test
    fun `inspect re-estimates the written night with per-epoch posteriors and writes nothing`() =
        harnessTest(dir) { h ->
            val night = h.writtenNight()
            val writes = h.repository.callCount(Operation.WRITE)
            val own = h.repository.ownSessions()

            val inspection = success(h.inspector.inspect(night.clientRecordId))

            val estimate = checkNotNull(inspection.estimate)
            assertEquals(8 * 60, estimate.perEpoch.size, "one result per 60-s epoch of the 8-hour session")
            assertTrue(estimate.perEpoch.all { abs(it.posterior.toList().sum() - 1.0) < 1e-9 }, "posteriors are distributions")
            assertEquals(listOf(FakeNightEstimator.FEATURE_NAME), estimate.featureNames)
            assertEquals(night.stages, StageMapping.toLedgerStages(StageMapping.toHcStages(estimate.stages, awakeAsLight = false)))
            assertFalse(inspection.writtenIsStale)
            assertEquals(h.pipeline.currentParamsHash(h.settings.current, h.repository.granted), inspection.paramsHash)
            assertEquals(writes, h.repository.callCount(Operation.WRITE), "inspection never writes")
            assertEquals(own, h.repository.ownSessions())
        }

    @Test
    fun `a written night counts as stale when its re-estimate reads other inputs`() =
        harnessTest(dir) { h ->
            val night = h.writtenNight()
            assertFalse(success(h.inspector.inspect(night.clientRecordId)).writtenIsStale)

            h.settings.set { it.copy(sources = it.sources.copy(hrSources = HrSourcesMode.ANY)) }
            assertTrue(success(h.inspector.inspect(night.clientRecordId)).writtenIsStale, "heart rate from any origin")

            h.settings.set { it.copy(sources = it.sources.copy(hrSources = HrSourcesMode.SAME_AS_SLEEP)) }
            assertFalse(success(h.inspector.inspect(night.clientRecordId)).writtenIsStale)

            h.repository.revoke(HealthPermissions.READ_OXYGEN_SATURATION)
            assertTrue(success(h.inspector.inspect(night.clientRecordId)).writtenIsStale, "SpO2 is no longer read")
        }

    @Test
    fun `the input is read again exactly like the pipeline read it, with the gaps between merged fragments`() =
        harnessTest(dir) { h ->
            val first = h.repository.addNight(OHEALTH, nightStart(1), durationMs = 3 * HOUR_MS)
            val second = h.repository.addNight(OHEALTH, first.endMs + 20 * MINUTE_MS, durationMs = 4 * HOUR_MS)
            h.sync.run(inBackground = false)
            val night = h.ledger.allNights().single()

            val inspection = success(h.inspector.inspect(night.clientRecordId))

            assertEquals(listOf(TimeSpan(first.endMs, second.startMs)), inspection.input.forcedAwake)
            assertEquals(h.estimator.inputs.first(), inspection.input, "the same input as the pipeline's estimate")
        }

    @Test
    fun `compare with RULES runs RULES on the same input and measures the agreement`() =
        harnessTest(dir) { h ->
            val night = h.writtenNight()

            val comparison = success(h.inspector.compareWith(night.clientRecordId))

            assertEquals(EstimatorType.HYBRID, comparison.primary.estimator)
            assertEquals(EstimatorType.RULES, comparison.other.estimator)
            assertEquals(comparison.primary.perEpoch.size, comparison.other.perEpoch.size)
            val agreement = comparison.agreement
            assertEquals(8 * 60 * 2L, agreement.confusion.total, "30-s chunks over the whole night")
            val accuracy = checkNotNull(agreement.accuracy)
            assertTrue(accuracy > 0.5 && accuracy < 1.0, "RULES disagrees around stage changes only: $accuracy")
            assertNotNull(agreement.kappa)
            assertEquals(480.0, agreement.primaryMinutes.run { awake + light + deep + rem }, 1e-6)
        }

    @Test
    fun `CSV export has one row per epoch and a dated file name`() =
        harnessTest(dir) { h ->
            val night = h.writtenNight()

            val export = success(h.inspector.exportCsv(night.clientRecordId))

            val lines = export.content.trimEnd('\n').split('\n')
            assertEquals(8 * 60 + 1, lines.size)
            assertTrue(lines.first().contains(FakeNightEstimator.FEATURE_NAME))
            assertTrue(lines.first().endsWith("p_awake,p_light,p_deep,p_rem,stage,mode"))
            assertTrue(lines.drop(1).all { it.endsWith(",NIGHT") })
            assertEquals("pulsestages-20261011-2300.csv", export.fileName)
        }

    @Test
    fun `a night that is no longer estimable with the current settings reports why`() =
        harnessTest(dir) { h ->
            val start = nightStart(1)
            h.repository.addNight(OHEALTH, start, withHeartRate = false)
            h.repository.addHeartRate(OHEALTH, start - 30 * MINUTE_MS, start + 5 * HOUR_MS, bpm = cyclingBpm(start))
            h.sync.run(inBackground = false)
            val night = h.ledger.allNights().single()
            assertEquals(NightStatus.WRITTEN, night.status)
            h.settings.set {
                it.copy(
                    algo = it.algo.copy(preprocess = it.algo.preprocess.copy(minCoverage = 0.95, rulesMinCoverage = 0.9)),
                )
            }

            val inspection = success(h.inspector.inspect(night.clientRecordId))

            assertEquals(null, inspection.estimate)
            assertTrue(inspection.writtenIsStale, "the parameters changed since the night was written")
            val notEstimated = assertInstanceOf(UseCaseResult.NotEstimated::class.java, h.inspector.compareWith(inspection))
            assertEquals(NotEstimatedReason.LOW_COVERAGE, notEstimated.outcome.reason)
            assertInstanceOf(UseCaseResult.NotEstimated::class.java, h.inspector.exportCsv(inspection))
        }

    @Test
    fun `an unknown night, missing permissions, unavailable Health Connect and quota errors are results, not crashes`() =
        harnessTest(dir) { h ->
            val night = h.writtenNight()

            assertEquals(UseCaseResult.NotFound, h.inspector.inspect("ps:unknown"))

            h.repository.failOn(Operation.READ_HEART_RATE, HealthDataException.QuotaExceeded("quota"))
            val quota = assertInstanceOf(UseCaseResult.Failed::class.java, h.inspector.inspect(night.clientRecordId))
            assertTrue(quota.retryable)

            h.repository.revoke(HealthPermissions.READ_HEART_RATE)
            val revoked = assertInstanceOf(UseCaseResult.Blocked::class.java, h.inspector.inspect(night.clientRecordId))
            assertEquals(BlockedReason.PERMISSION_MISSING, revoked.reason)

            h.repository.grant(HealthPermissions.READ_HEART_RATE)
            h.repository.failOn(Operation.READ_STEPS, HealthDataException.PermissionDenied("revoked mid-read"))
            val midRead = assertInstanceOf(UseCaseResult.Blocked::class.java, h.inspector.compareWith(night.clientRecordId))
            assertEquals(BlockedReason.PERMISSION_MISSING, midRead.reason)

            h.repository.availability = HcAvailability.PROVIDER_UPDATE_REQUIRED
            val unavailable = assertInstanceOf(UseCaseResult.Blocked::class.java, h.inspector.exportCsv(night.clientRecordId))
            assertEquals(BlockedReason.HEALTH_CONNECT_UNAVAILABLE, unavailable.reason)
        }
}
