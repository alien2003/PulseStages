package io.github.alien2003.pulsestages.integration

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.SavedStateHandle
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.algo.ALGO_VERSION
import io.github.alien2003.pulsestages.algo.model.EstimatorType
import io.github.alien2003.pulsestages.algo.model.WarningCode
import io.github.alien2003.pulsestages.algo.params.AlgoParams
import io.github.alien2003.pulsestages.data.hc.BackgroundRuleHealthRepository
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.di.DataHcBindingsModule
import io.github.alien2003.pulsestages.data.hc.di.OwnPackageName
import io.github.alien2003.pulsestages.data.hc.di.PlatformHealthRepository
import io.github.alien2003.pulsestages.data.hc.fake.FakeHealthRepository
import io.github.alien2003.pulsestages.data.hc.pipeline.DefaultTitleProvider
import io.github.alien2003.pulsestages.data.hc.pipeline.MyDataEraser
import io.github.alien2003.pulsestages.data.hc.pipeline.NightEstimator
import io.github.alien2003.pulsestages.data.hc.pipeline.NightKeys
import io.github.alien2003.pulsestages.data.hc.pipeline.NightPipeline
import io.github.alien2003.pulsestages.data.hc.pipeline.OutputOptions
import io.github.alien2003.pulsestages.data.hc.pipeline.ResourceDefaultTitleProvider
import io.github.alien2003.pulsestages.data.hc.pipeline.SourceScanner
import io.github.alien2003.pulsestages.data.hc.testutil.DAY_MS
import io.github.alien2003.pulsestages.data.hc.testutil.HOUR_MS
import io.github.alien2003.pulsestages.data.hc.testutil.MINUTE_MS
import io.github.alien2003.pulsestages.data.hc.testutil.OHEALTH
import io.github.alien2003.pulsestages.data.hc.testutil.OTHER_APP
import io.github.alien2003.pulsestages.data.hc.testutil.THIRD_APP
import io.github.alien2003.pulsestages.data.hc.testutil.addNight
import io.github.alien2003.pulsestages.data.hc.testutil.addSyntheticNight
import io.github.alien2003.pulsestages.data.hc.testutil.cyclingBpm
import io.github.alien2003.pulsestages.data.hc.testutil.hcStagesOf
import io.github.alien2003.pulsestages.data.hc.usecase.CalibrationOutcome
import io.github.alien2003.pulsestages.data.hc.usecase.HomeStatusAggregator
import io.github.alien2003.pulsestages.data.hc.usecase.NightInspection
import io.github.alien2003.pulsestages.data.hc.usecase.ReferenceSearch
import io.github.alien2003.pulsestages.data.hc.usecase.UseCaseResult
import io.github.alien2003.pulsestages.data.local.db.BackfillStateDao
import io.github.alien2003.pulsestages.data.local.db.BackfillStatus
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.NightStatus
import io.github.alien2003.pulsestages.data.local.db.PulseStagesDatabase
import io.github.alien2003.pulsestages.data.local.di.ApplicationScope
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.HrSourcesMode
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.platform.AppInfoResolver
import io.github.alien2003.pulsestages.source.HealthConnectReferenceSource
import io.github.alien2003.pulsestages.source.LedgerNightsSource
import io.github.alien2003.pulsestages.source.LedgerStaleNightsSource
import io.github.alien2003.pulsestages.source.NightsSource
import io.github.alien2003.pulsestages.source.ReferenceSource
import io.github.alien2003.pulsestages.source.ScreenSources
import io.github.alien2003.pulsestages.source.StaleNightsSource
import io.github.alien2003.pulsestages.ui.home.HomeViewModel
import io.github.alien2003.pulsestages.ui.sources.SourcesViewModel
import io.github.alien2003.pulsestages.work.estimate.SleepStagerNightEstimator
import io.github.alien2003.pulsestages.work.run.OutcomeKind
import io.github.alien2003.pulsestages.work.run.WorkResults
import io.github.alien2003.pulsestages.work.schedule.WorkRequests
import io.github.alien2003.pulsestages.work.schedule.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's real Hilt graph with ONLY the platform Health Connect client replaced by the in-memory
 * [FakeHealthRepository] (bound as @PlatformHealthRepository behind the real [BackgroundRuleHealthRepository], exactly
 * like DataHcBindingsModule binds HealthConnectRepository). Everything else is production: WorkManagerWorkScheduler on
 * a test WorkManager configured with the injected HiltWorkerFactory, the workers, runners, NewNightsSync,
 * NightPipeline, SleepStagerNightEstimator with the bundled model from the app's assets, Room, DataStore, the
 * ViewModels and the screen sources. The test drives the UI actions in order and checks their effect in "Health
 * Connect" (the fake) and in the ledger.
 */
@HiltAndroidTest
@UninstallModules(DataHcBindingsModule::class)
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class EndToEndWiringTest {
    /** DataHcBindingsModule with the fake as the platform repository. */
    @Module
    @InstallIn(SingletonComponent::class)
    object FakePlatformModule {
        /** One fake Health Connect for the whole graph. */
        @Provides
        @Singleton
        fun provideFake(
            @ApplicationContext context: Context,
        ): FakeHealthRepository = FakeHealthRepository(ownPackage = context.packageName, now = System::currentTimeMillis)

        /** The fake in place of HealthConnectRepository. */
        @Provides
        @PlatformHealthRepository
        fun providePlatform(fake: FakeHealthRepository): HealthRepository = fake

        /** As in production: the background rule in front of the platform repository. */
        @Provides
        fun provideRepository(implementation: BackgroundRuleHealthRepository): HealthRepository = implementation

        /** As in production. */
        @Provides
        fun provideTitles(implementation: ResourceDefaultTitleProvider): DefaultTitleProvider = implementation
    }

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var fake: FakeHealthRepository

    @Inject lateinit var repository: HealthRepository

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var scheduler: WorkScheduler

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var nightDao: EstimatedNightDao

    @Inject lateinit var backfillDao: BackfillStateDao

    @Inject lateinit var homeStatus: HomeStatusAggregator

    @Inject lateinit var eraser: MyDataEraser

    @Inject lateinit var scanner: SourceScanner

    @Inject lateinit var appInfo: AppInfoResolver

    @Inject lateinit var logger: AppLogger

    @Inject lateinit var screenSources: ScreenSources

    @Inject lateinit var staleSource: StaleNightsSource

    @Inject lateinit var estimator: NightEstimator

    @Inject lateinit var pipeline: NightPipeline

    @Inject
    @field:OwnPackageName
    lateinit var ownPackage: String

    @Inject lateinit var database: PulseStagesDatabase

    @Inject
    @field:ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Inject
    @field:ApplicationContext
    lateinit var context: Context

    private lateinit var workManager: WorkManager
    private lateinit var nightsSource: NightsSource
    private lateinit var referenceSource: ReferenceSource
    private lateinit var home: HomeViewModel
    private lateinit var sources: SourcesViewModel
    private val now = System.currentTimeMillis()

    /** clientRecordIds of the nights the steps create. */
    private val ids = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        hiltRule.inject()
        val config =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .setExecutor(SynchronousExecutor())
                .setMinimumLoggingLevel(Log.INFO)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        home = HomeViewModel(homeStatus, scheduler, eraser, appInfo, logger, SavedStateHandle())
        // The screens' sources as the ViewModels get them (demo mode, debug builds only, is off).
        nightsSource = runBlocking { screenSources.nights.first() }
        referenceSource = runBlocking { screenSources.references.first() }
    }

    @After
    fun tearDown() {
        database.close()
        applicationScope.cancel()
    }

    @Test
    fun uiActionsReachHealthConnectThroughTheRealGraph() {
        assertTrue(repository is BackgroundRuleHealthRepository)
        assertTrue(estimator is SleepStagerNightEstimator)
        assertTrue(nightsSource is LedgerNightsSource)
        assertTrue(referenceSource is HealthConnectReferenceSource)
        assertTrue(staleSource is LedgerStaleNightsSource)
        assertEquals(context.packageName, ownPackage)

        estimateNewWritesOneSessionIdempotently()
        sourcesScreenChangesWhatTheNextSyncUses()
        hrSourcesAnyMakesNightsStaleWithoutRecomputingThem()
        settingsChangeMakesNightsStaleAndRecomputeStaleRewritesThem()
        nightScreenRecomputesExportsAndDeletes()
        compareCalibratesAndTheHashFollowsThePersonalModel()
        deleteMySessionsThenBackfillThenRecomputeAll()
    }

    /** "Estimate new" → WorkScheduler → EstimateNewNightsWorker → NewNightsSync → NightPipeline → write. */
    private fun estimateNewWritesOneSessionIdempotently() {
        val night = fake.addNight(OHEALTH, now - DAY_MS - 10 * HOUR_MS)
        val first = runAndAwait(WorkRequests.Names.NOW) { home.estimateNow() }
        assertEquals(WorkInfo.State.SUCCEEDED, first.state)
        assertEquals(OutcomeKind.COMPLETED, WorkResults.parse(first.outputData)?.kind)
        val id = NightKeys.clientRecordId(listOf(night.uuid))
        ids["A"] = id
        val own = fake.ownSessions().single()
        assertEquals(id, own.clientRecordId)
        assertEquals(ALGO_VERSION * VERSION_FACTOR, own.clientRecordVersion)
        val estimatorType = AlgoParams().estimator
        assertTrue(own.notes, own.notes.orEmpty().contains("\"algo\":\"$estimatorType\""))
        val row = runBlocking { checkNotNull(nightDao.findByClientRecordId(id)) }
        assertEquals(NightStatus.WRITTEN, row.status)
        assertEquals(estimatorType, row.estimator)
        if (estimatorType == EstimatorType.HYBRID) {
            assertNotNull("HYBRID ran with the bundled model from the app assets", row.modelId)
            assertTrue(row.warnings.toString(), WarningCode.HYBRID_MODEL_UNAVAILABLE !in row.warnings)
        }

        runAndAwait(WorkRequests.Names.NOW) { home.estimateNow() }
        assertEquals("a second run writes nothing new", 1, fake.ownSessions().size)
        assertEquals(ALGO_VERSION * VERSION_FACTOR, fake.ownSessions().single().clientRecordVersion)
    }

    /** Sources screen: enabling OTHER_APP and moving it to the top changes the sources of the next sync. */
    private fun sourcesScreenChangesWhatTheNextSyncUses() {
        val nightB = fake.addNight(OTHER_APP, now - 2 * DAY_MS - 10 * HOUR_MS)
        // The screen opens now (it scans Health Connect when it is created).
        sources = SourcesViewModel(scanner, settings, appInfo, logger, ownPackage)
        idleUntil("scan") {
            !sources.state.value.loading &&
                sources.state.value.items
                    .any { it.packageName == OTHER_APP }
        }
        sources.setEnabled(OTHER_APP, true)
        idleUntil("enable stored") { OTHER_APP in currentSettings().sources.sourcePackages }
        sources.moveUp(OTHER_APP)
        idleUntil("priority stored") { currentSettings().sources.sourcePriority.firstOrNull() == OTHER_APP }
        // A night recorded by both sources (OTHER_APP has priority now) and an OTHER_APP night without its own HR.
        val duplicateOhealth = fake.addNight(OHEALTH, now - 3 * DAY_MS - 10 * HOUR_MS)
        val duplicateOther = fake.addNight(OTHER_APP, now - 3 * DAY_MS - 10 * HOUR_MS + 20 * MINUTE_MS)
        val nightDStart = now - 4 * DAY_MS - 10 * HOUR_MS
        val nightD = fake.addNight(OTHER_APP, nightDStart, withHeartRate = false)
        fake.addHeartRate(OHEALTH, nightDStart - 30 * MINUTE_MS, nightDStart + 8 * HOUR_MS + 30 * MINUTE_MS, bpm = cyclingBpm(nightDStart))

        runAndAwait(WorkRequests.Names.NOW) { home.estimateNow() }
        ids["B"] = NightKeys.clientRecordId(listOf(nightB.uuid))
        ids["dupOther"] = NightKeys.clientRecordId(listOf(duplicateOther.uuid))
        ids["D"] = NightKeys.clientRecordId(listOf(nightD.uuid))
        val own = fake.ownSessions().map { it.clientRecordId }.toSet()
        assertEquals(setOf(id("A"), id("B"), id("dupOther")), own)
        assertTrue("lower-priority duplicate not written", NightKeys.clientRecordId(listOf(duplicateOhealth.uuid)) !in own)
        assertEquals(NightStatus.NOT_ESTIMATED, runBlocking { nightDao.findByClientRecordId(id("D")) }?.status)
    }

    /** hrSources = any: the next sync estimates D from OHealth's heart rate; the others become stale, not recomputed. */
    private fun hrSourcesAnyMakesNightsStaleWithoutRecomputingThem() {
        assertEquals(0, runBlocking { staleSource.staleNights().first() }.count)
        sources.setHrSourcesAny(true)
        idleUntil("hrSources stored") { currentSettings().sources.hrSources == HrSourcesMode.ANY }
        idleUntil("stale count follows hrSources") { runBlocking { staleSource.staleNights().first().count == 3 } }
        runAndAwait(WorkRequests.Names.NOW) { home.estimateNow() }
        assertEquals(NightStatus.WRITTEN, runBlocking { nightDao.findByClientRecordId(id("D")) }?.status)
        assertEquals(ALGO_VERSION * VERSION_FACTOR, fake.ownSessions().first { it.clientRecordId == id("A") }.clientRecordVersion)
    }

    /** An algorithm parameter changes the params hash and the stale counter; "recompute stale" rewrites the nights. */
    private fun settingsChangeMakesNightsStaleAndRecomputeStaleRewritesThem() {
        val hashBefore = runBlocking { staleSource.staleNights().first().paramsHash }
        runBlocking { settings.update { it.copy(algo = it.algo.copy(preprocess = it.algo.preprocess.copy(smoothWindowEpochs = 7))) } }
        idleUntil("stale count follows the params") { runBlocking { staleSource.staleNights().first().count == 4 } }
        assertNotEquals(hashBefore, runBlocking { staleSource.staleNights().first().paramsHash })
        runAndAwait(WorkRequests.Names.RECOMPUTE_ALL) { staleSource.recomputeStale() }
        assertEquals(0, runBlocking { staleSource.staleNights().first().count })
        assertEquals(ALGO_VERSION * VERSION_FACTOR + 1, fake.ownSessions().first { it.clientRecordId == id("A") }.clientRecordVersion)
        assertEquals(4, fake.ownSessions().size)
    }

    /** Night screen: recompute one night, compare with RULES, export CSV, delete my session. */
    private fun nightScreenRecomputesExportsAndDeletes() {
        runAndAwait(WorkRequests.Names.recomputeNight(id("A"))) { runBlocking { nightsSource.recomputeNight(id("A")) } }
        assertEquals(ALGO_VERSION * VERSION_FACTOR + 2, fake.ownSessions().first { it.clientRecordId == id("A") }.clientRecordVersion)
        val inspection = runBlocking { nightsSource.inspect(id("A")) }
        assertTrue("$inspection", inspection is UseCaseResult.Success)
        val inspected = (inspection as UseCaseResult.Success<NightInspection>).value
        val rules = runBlocking { nightsSource.compareWithRules(inspected) }
        assertTrue("$rules", rules is UseCaseResult.Success && rules.value.other.estimator == EstimatorType.RULES)
        val csv = nightsSource.exportCsv(inspected)
        assertTrue(
            "$csv",
            csv is UseCaseResult.Success &&
                csv.value.content
                    .lineSequence()
                    .first()
                    .startsWith("epoch"),
        )
        runBlocking { nightsSource.deleteNight(id("B")) }
        assertTrue(fake.ownSessions().none { it.clientRecordId == id("B") })
        assertEquals(NightStatus.DELETED, runBlocking { nightDao.findByClientRecordId(id("B")) }?.status)
        runAndAwait(WorkRequests.Names.NOW) { home.estimateNow() }
        assertTrue("a deleted night is not re-created by a sync", fake.ownSessions().none { it.clientRecordId == id("B") })
    }

    /** Compare: reference nights → comparison → calibration; the active personal model is part of the params hash. */
    private fun compareCalibratesAndTheHashFollowsThePersonalModel() {
        val references =
            (0 until REFERENCE_NIGHTS).map { i ->
                fake.addSyntheticNight(
                    THIRD_APP,
                    now - (12 + i) * DAY_MS - 10 * HOUR_MS,
                    seed = 100L + i,
                    stages = { hcStagesOf(it.truth) },
                )
            }
        val search = runBlocking { referenceSource.findReferenceNights(now - 30 * DAY_MS, now) }
        assertTrue("$search", search is UseCaseResult.Success)
        val referenceNights = (search as UseCaseResult.Success<ReferenceSearch>).value.nights
        assertEquals(references.size, referenceNights.size)
        assertEquals(references.size, runBlocking { referenceSource.compare(referenceNights) }.scoredNights)
        val current = currentSettings()
        val granted = runBlocking { repository.grantedPermissions() }
        val globalHash = runBlocking { pipeline.currentParamsHash(current, granted) }
        val calibration = runBlocking { referenceSource.calibrate(referenceNights) }
        assertTrue("$calibration", calibration is CalibrationOutcome.Calibrated)
        val personalHash = runBlocking { pipeline.currentParamsHash(current, granted) }
        assertNotEquals("the active personal model is part of the hash", globalHash, personalHash)
        assertEquals(personalHash, runBlocking { estimator.paramsHash(current.algo, OutputOptions.hashExtra(current, granted)) })
        idleUntil("calibration makes the nights stale") { runBlocking { staleSource.staleNights().first().count == 3 } }
        runBlocking { referenceSource.resetToGlobal() }
        assertEquals(globalHash, runBlocking { pipeline.currentParamsHash(current, granted) })
    }

    /** "Delete my sessions" (confirmed) removes every own session; a backfill writes them again; "Recompute all". */
    private fun deleteMySessionsThenBackfillThenRecomputeAll() {
        home.requestDeleteAll()
        home.confirmDeleteAll()
        idleUntil("erased") { !home.state.value.erasing && fake.ownSessions().isEmpty() }
        val backfill = runAndAwait(WorkRequests.Names.BACKFILL) { home.startBackfill() }
        assertEquals(WorkInfo.State.SUCCEEDED, backfill.state)
        val state = runBlocking { checkNotNull(backfillDao.get()) }
        assertEquals(BackfillStatus.DONE, state.status)
        assertEquals(state.windowsTotal, state.windowsDone)
        assertTrue(state.windowsTotal > 0)
        assertEquals(setOf(id("A"), id("B"), id("dupOther"), id("D")), fake.ownSessions().map { it.clientRecordId }.toSet())

        val versionA = fake.ownSessions().first { it.clientRecordId == id("A") }.clientRecordVersion
        val all =
            runAndAwait(WorkRequests.Names.RECOMPUTE_ALL) {
                home.requestRecomputeAll()
                home.confirmRecomputeAll()
            }
        assertEquals(WorkInfo.State.SUCCEEDED, all.state)
        assertEquals(versionA + 1, fake.ownSessions().first { it.clientRecordId == id("A") }.clientRecordVersion)
    }

    private fun id(name: String): String = checkNotNull(ids[name]) { "night $name was not created yet" }

    private fun currentSettings() = runBlocking { settings.settings.first() }

    /** Runs [action] and waits until a new work of the unique work [name] has finished. */
    private fun runAndAwait(
        name: String,
        action: () -> Unit,
    ): WorkInfo {
        val before =
            workManager
                .getWorkInfosForUniqueWork(name)
                .get()
                .map { it.id }
                .toSet()
        action()
        var result: WorkInfo? = null
        idleUntil("work $name") {
            result = workManager.getWorkInfosForUniqueWork(name).get().firstOrNull { it.id !in before && it.state.isFinished }
            result != null
        }
        return checkNotNull(result)
    }

    private fun idleUntil(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private companion object {
        const val VERSION_FACTOR = 1000L
        const val REFERENCE_NIGHTS = 4
        const val TIMEOUT_MS = 60_000L
        const val POLL_MS = 20L
    }
}
