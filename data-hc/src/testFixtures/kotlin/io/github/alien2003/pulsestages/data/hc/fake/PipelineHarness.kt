package io.github.alien2003.pulsestages.data.hc.fake

import io.github.alien2003.pulsestages.data.hc.pipeline.BackfillEngine
import io.github.alien2003.pulsestages.data.hc.pipeline.DefaultTitleProvider
import io.github.alien2003.pulsestages.data.hc.pipeline.HealthAccessGuard
import io.github.alien2003.pulsestages.data.hc.pipeline.MyDataEraser
import io.github.alien2003.pulsestages.data.hc.pipeline.NewNightsSync
import io.github.alien2003.pulsestages.data.hc.pipeline.NightCandidateBuilder
import io.github.alien2003.pulsestages.data.hc.pipeline.NightLedger
import io.github.alien2003.pulsestages.data.hc.pipeline.NightPipeline
import io.github.alien2003.pulsestages.data.hc.pipeline.NightRecomputer
import io.github.alien2003.pulsestages.data.hc.pipeline.NightWindowReader
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceScanner
import io.github.alien2003.pulsestages.data.hc.pipeline.ZoneOffsetProvider
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * The whole Health Connect pipeline wired to test doubles: [FakeHealthRepository], [InMemoryLedger],
 * [FakeNightEstimator], [FakeSettingsRepository], [RecordingLogger], a [TestClock] and a real [SyncStateStore] on a
 * file in [directory] (its DataStore runs in [scope], e.g. `runTest`'s `backgroundScope`).
 */
class PipelineHarness(
    scope: CoroutineScope,
    directory: File,
    /** Dispatcher for estimation (pass the test dispatcher to stay in virtual time). */
    estimationDispatcher: CoroutineDispatcher,
    /** Initial time. */
    startMs: Long = DEFAULT_START_MS,
    /** Our package name. */
    val ownPackage: String = FakeHealthRepository.DEFAULT_OWN_PACKAGE,
    /** Fallback zone offset (seconds) for sessions without offsets. */
    fallbackZoneOffsetSec: Int = 0,
) {
    /** The clock. */
    val clock: TestClock = TestClock(startMs)

    /** The fake Health Connect. */
    val repository: FakeHealthRepository = FakeHealthRepository(ownPackage) { clock.millis() }

    /** The in-memory ledger. */
    val ledger: InMemoryLedger = InMemoryLedger()

    /** The fake estimator. */
    val estimator: FakeNightEstimator = FakeNightEstimator()

    /** Settings. */
    val settings: FakeSettingsRepository = FakeSettingsRepository()

    /** Log lines. */
    val logger: RecordingLogger = RecordingLogger()

    /** Changes token and run state. */
    val syncState: SyncStateStore =
        SyncStateStore(SyncStateStore.createDataStore(scope) { File(directory, "${SyncStateStore.FILE_NAME}.preferences_pb") }, clock)

    /** Session → night builder. */
    val builder: NightCandidateBuilder = NightCandidateBuilder(ownPackage, ZoneOffsetProvider { fallbackZoneOffsetSec })

    /** Window reader. */
    val windowReader: NightWindowReader = NightWindowReader(repository, builder, ownPackage)

    /** Access guard. */
    val guard: HealthAccessGuard = HealthAccessGuard(repository)

    /** The ledger DAOs as the pipeline sees them. */
    val nightLedger: NightLedger = NightLedger(ledger.nightDao, ledger.sourceDao, ledger.baselineDao)

    /** The pipeline. */
    val pipeline: NightPipeline =
        NightPipeline(
            repository = repository,
            estimator = estimator,
            ledger = nightLedger,
            titles = DefaultTitleProvider { DEFAULT_TITLE },
            logger = logger,
            clock = clock,
            estimationDispatcher = estimationDispatcher,
        )

    /** Changes-API sync. */
    val sync: NewNightsSync = NewNightsSync(repository, settings, syncState, windowReader, pipeline, logger, clock)

    /** Backfill. */
    val backfill: BackfillEngine = BackfillEngine(repository, settings, ledger.backfillDao, windowReader, pipeline, logger, clock)

    /** Sources scan. */
    val scanner: SourceScanner = SourceScanner(repository, settings, clock, ownPackage)

    /** "Delete all my sessions". */
    val eraser: MyDataEraser = MyDataEraser(repository, ledger.nightDao, backfill, logger, clock)

    /** Explicit recompute. */
    val recomputer: NightRecomputer = NightRecomputer(settings, ledger.nightDao, windowReader, pipeline, guard, logger)

    /** Defaults. */
    companion object {
        /** 2026-10-13T00:00:00Z. */
        const val DEFAULT_START_MS: Long = 1_791_849_600_000L

        /** Title template the harness uses as the localized default. */
        const val DEFAULT_TITLE: String = "Sleep stages from heart rate (estimate)"
    }
}
