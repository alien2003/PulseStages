package io.github.alien2003.pulsestages.data.hc.pipeline

import androidx.room.Room
import io.github.alien2003.pulsestages.data.hc.HcStage
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.FakeNightEstimator
import io.github.alien2003.pulsestages.data.hc.fake.FakeSettingsRepository
import io.github.alien2003.pulsestages.data.hc.fake.InMemoryLedger
import io.github.alien2003.pulsestages.data.hc.fake.PipelineHarness
import io.github.alien2003.pulsestages.data.hc.fake.RecordingLogger
import io.github.alien2003.pulsestages.data.hc.fake.TestClock
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.NOON
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.nightStart
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.PulseStagesDatabase
import io.github.alien2003.pulsestages.data.local.db.SourceSessionEntity
import io.github.alien2003.pulsestages.data.local.settings.OverwritePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Differential test: the same pipeline scenario on the real Room ledger (Robolectric, Android SQLite) and on
 * [InMemoryLedger] must leave identical ledgers and identical Health Connect contents. This is what justifies running
 * every other pipeline test on the in-memory ledger.
 */
@RunWith(RobolectricTestRunner::class)
class RoomLedgerCrossCheckTest {
    private lateinit var db: PulseStagesDatabase

    @Before
    fun openDatabase() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PulseStagesDatabase::class.java).build()
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    /** One environment: everything is fake except the ledger. */
    private class Env(
        val ledger: NightLedger,
        dispatcher: CoroutineDispatcher,
    ) {
        val clock = TestClock(NOON)
        val repository = FakeHealthRepository { clock.millis() }
        val settings = FakeSettingsRepository()
        private val builder = NightCandidateBuilder(repository.ownPackage) { 0 }
        val windowReader = NightWindowReader(repository, builder, repository.ownPackage)
        val pipeline =
            NightPipeline(
                repository,
                FakeNightEstimator(),
                ledger,
                { PipelineHarness.DEFAULT_TITLE },
                RecordingLogger(),
                clock,
                dispatcher,
            )
    }

    private suspend fun Env.process(trigger: PipelineTrigger = PipelineTrigger.NEW_NIGHTS): PipelineReport {
        val current = settings.current
        return pipeline.process(windowReader.nightsInWindow(NOON - 10 * DAY, NOON, current), current, trigger, repository.granted.toSet())
    }

    /** The scenario; returns the reports of every step. */
    private suspend fun Env.scenario(): List<PipelineReport> {
        settings.set {
            it.copy(sources = it.sources.copy(sourcePackages = listOf(OHEALTH, OTHER_APP), sourcePriority = listOf(OHEALTH, OTHER_APP)))
        }
        val fifth = repository.addNight(OHEALTH, nightStart(5))
        val first = repository.addNight(OHEALTH, nightStart(4), durationMs = 3 * HOUR_MS)
        repository.addNight(OHEALTH, nightStart(4) + 3 * HOUR_MS + 15 * MINUTE_MS, durationMs = 4 * HOUR_MS)
        repository.addNight(OHEALTH, nightStart(3) + 14 * HOUR_MS, durationMs = 40 * MINUTE_MS)
        repository.addNight(OHEALTH, nightStart(3), stages = listOf(HcStage(nightStart(3), nightStart(3) + HOUR_MS, 5)))
        repository.addNight(OTHER_APP, nightStart(5) + 10 * MINUTE_MS, durationMs = 7 * HOUR_MS)
        repository.addNight(OHEALTH, nightStart(2), withHeartRate = false)
        val single = repository.addNight(OHEALTH, nightStart(1), durationMs = 5 * HOUR_MS)

        val reports = mutableListOf(process(), process())
        repository.addNight(OHEALTH, nightStart(1) + 5 * HOUR_MS + 20 * MINUTE_MS, durationMs = 2 * HOUR_MS)
        reports += process()
        settings.set { it.copy(algo = it.algo.copy(preprocess = it.algo.preprocess.copy(smoothWindowEpochs = 7))) }
        reports += process()
        reports += process(PipelineTrigger.RECOMPUTE)
        settings.set { it.copy(write = it.write.copy(overwritePolicy = OverwritePolicy.RECOMPUTE_ALL)) }
        reports += process()

        val deleted = listOf(fifth, first, single).map { it.uuid }
        deleted.forEach(repository::deleteSleepSession)
        val deletion = pipeline.applyDeletions(deleted)
        var rebuilt = CandidateSet.EMPTY
        deletion.toRebuild.forEach { rebuilt = rebuilt + windowReader.nightsAround(it.sessionStartMs, it.sessionEndMs, settings.current) }
        reports += pipeline.process(rebuilt, settings.current, PipelineTrigger.NEW_NIGHTS, repository.granted.toSet())
        pipeline.retireRemnants(deletion.toRebuild)
        return reports
    }

    private data class Snapshot(
        val reports: List<PipelineReport>,
        val nights: List<String>,
        val sources: List<SourceSessionEntity>,
        val baselines: List<String>,
        val own: List<String>,
    )

    private suspend fun Env.snapshot(
        reports: List<PipelineReport>,
        nightDao: EstimatedNightDao,
    ): Snapshot {
        val cids = nightDao.findStartingIn(0, Long.MAX_VALUE).map { it.clientRecordId }
        val nights =
            nightDao.findStartingIn(0, Long.MAX_VALUE).map { n ->
                listOf(
                    n.clientRecordId,
                    n.status,
                    n.statusReason,
                    n.hcRecordId,
                    n.clientRecordVersion,
                    n.recomputeCount,
                    n.sourceUuids,
                    nightDao.sourceUuidsOf(n.clientRecordId),
                    n.stages.size,
                    n.paramsHash,
                    n.awakeMin + n.lightMin + n.deepMin + n.remMin + n.unknownMin,
                ).joinToString("|")
            }
        val uuids = repository.allSessions().map { it.uuid }
        val sources = ledger.sources.findByUuids(uuids)
        val baselines =
            cids.mapNotNull { cid ->
                ledger.baselines.find(cid)?.let { "${it.clientRecordId}|${it.nadirBpm}|${it.sessionEndMs}" }
            }
        val own =
            repository.ownSessions().map {
                "${it.clientRecordId}|${it.clientRecordVersion}|${it.stages.size}|${it.startMs}|${it.endMs}"
            }
        return Snapshot(reports, nights, sources, baselines, own)
    }

    @Test
    fun `the pipeline leaves the same ledger on Room and on the in-memory ledger`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val room = Env(NightLedger(db.estimatedNightDao(), db.sourceSessionDao(), db.nightBaselineDao()), dispatcher)
            val memoryLedger = InMemoryLedger()
            val memory = Env(NightLedger(memoryLedger.nightDao, memoryLedger.sourceDao, memoryLedger.baselineDao), dispatcher)

            val onRoom = room.snapshot(room.scenario(), db.estimatedNightDao())
            val inMemory = memory.snapshot(memory.scenario(), memoryLedger.nightDao)

            assertTrue("the scenario wrote nights", onRoom.own.isNotEmpty())
            assertTrue("the scenario superseded nights", onRoom.nights.any { "SUPERSEDED" in it })
            assertTrue("the scenario removed a night", onRoom.nights.any { "SOURCE_DELETED" in it })
            assertEquals(onRoom.reports, inMemory.reports)
            assertEquals(onRoom.nights, inMemory.nights)
            assertEquals(onRoom.sources, inMemory.sources)
            assertEquals(onRoom.baselines, inMemory.baselines)
            assertEquals(onRoom.own, inMemory.own)
        }

    private companion object {
        const val DAY = 24 * HOUR_MS
    }
}
