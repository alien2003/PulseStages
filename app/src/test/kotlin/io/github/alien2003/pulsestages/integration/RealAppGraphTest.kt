package io.github.alien2003.pulsestages.integration

import android.content.Context
import androidx.health.connect.client.PermissionController
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.alien2003.pulsestages.PulseStagesApp
import io.github.alien2003.pulsestages.algo.hybrid.HybridModel
import io.github.alien2003.pulsestages.data.hc.BackgroundRuleHealthRepository
import io.github.alien2003.pulsestages.data.hc.HealthConnectRepository
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.data.hc.pipeline.NewNightsSync
import io.github.alien2003.pulsestages.data.hc.pipeline.NightPipeline
import io.github.alien2003.pulsestages.data.local.settings.ScheduleSettings
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.work.estimate.AndroidModelAssetSource
import io.github.alien2003.pulsestages.work.estimate.BundledHybridModelProvider
import io.github.alien2003.pulsestages.work.estimate.SleepStagerNightEstimator
import io.github.alien2003.pulsestages.work.run.EstimateNewNightsRunner
import io.github.alien2003.pulsestages.work.run.RecomputeRequest
import io.github.alien2003.pulsestages.work.schedule.WorkManagerWorkScheduler
import io.github.alien2003.pulsestages.work.schedule.WorkRequests
import io.github.alien2003.pulsestages.work.worker.BackfillWorker
import io.github.alien2003.pulsestages.work.worker.EstimateNewNightsWorker
import io.github.alien2003.pulsestages.work.worker.RecomputeWorker
import io.github.alien2003.pulsestages.work.worker.WorkerInputs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * The PRODUCTION Hilt graph of [PulseStagesApp] (no test modules), started like the real app: Application.onCreate
 * starts keepScheduled, which enqueues the periodic and the daily work; WorkManager is configured with the injected
 * HiltWorkerFactory and that factory itself (not WorkManager's reflective fallback, which cannot build these workers)
 * creates every worker; the worker chain reaches BackgroundRuleHealthRepository over the real HealthConnectRepository
 * and SleepStagerNightEstimator with the bundled model read through the app's AssetManager; the app logger mirrors to
 * logcat with the tag "PulseStages".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = PulseStagesApp::class)
class RealAppGraphTest {
    private val app: PulseStagesApp = RuntimeEnvironment.getApplication() as PulseStagesApp

    @Test
    fun startupSchedulesPeriodicAndDailyWorkThroughTheHiltConfiguredWorkManager() {
        val workManager = WorkManager.getInstance(app)
        val periodic = awaitUnfinished(workManager, WorkRequests.Names.PERIODIC)
        val daily = awaitUnfinished(workManager, WorkRequests.Names.DAILY)
        val defaults = ScheduleSettings()
        assertTrue(periodic.tags.toString(), WorkRequests.periodicTag(defaults) in periodic.tags)
        assertTrue(daily.tags.toString(), daily.tags.any { it.startsWith("schedule:daily:${defaults.dailyTimeMinutes}min") })
        assertTrue(periodic.constraints.requiresBatteryNotLow())
        assertTrue(daily.constraints.requiresBatteryNotLow())
        assertEquals(EstimateNewNightsWorker::class.java.name, workerClassOf(workManager, periodic))

        val factory = app.workManagerConfiguration.workerFactory
        assertTrue("worker factory is ${factory.javaClass}", factory is HiltWorkerFactory)
        assertTrue(factory === app.workerFactory)
        val scheduler = field<Any>(app.backgroundSchedule, "scheduler")
        assertTrue(scheduler is WorkManagerWorkScheduler)

        // keepScheduled (started in onCreate) follows a schedule change in the settings.
        val settings = field<SettingsRepository>(scheduler, "settingsRepository")
        runBlocking { settings.update { it.copy(schedule = it.schedule.copy(periodicHours = 3, runOnlyWhenNotLowBattery = false)) } }
        val changed = ScheduleSettings(periodicHours = 3, runOnlyWhenNotLowBattery = false)
        val updated = awaitUnfinished(workManager, WorkRequests.Names.PERIODIC) { WorkRequests.periodicTag(changed) in it.tags }
        assertEquals(false, updated.constraints.requiresBatteryNotLow())
        assertEquals(3 * 60 * 60 * 1000L, updated.periodicityInfo?.repeatIntervalMillis)
    }

    @Test
    fun theGrantButtonRequestsExactlyTheRequestablePermissions() {
        val requested = HealthPermissions.requestable { true }
        val intent = PermissionController.createRequestPermissionResultContract().createIntent(app, requested)
        val extras = checkNotNull(intent.extras)
        val names = extras.keySet().flatMap { key -> extras.getStringArray(key)?.toList().orEmpty() }.toSet()
        assertEquals(requested, names)
        assertEquals(9, requested.size)
    }

    @Test
    fun hiltWorkerFactoryCreatesEveryWorkerAndTheChainReachesTheRealRepositoryAndBundledModel() {
        ShadowLog.clear()
        val context: Context = app
        val created = mutableListOf<ListenableWorker?>()
        val recording =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? = app.workerFactory.createWorker(appContext, workerClassName, workerParameters).also { created += it }
            }
        val estimate = TestListenableWorkerBuilder.from(context, EstimateNewNightsWorker::class.java).setWorkerFactory(recording).build()
        TestListenableWorkerBuilder.from(context, BackfillWorker::class.java).setWorkerFactory(recording).build()
        TestListenableWorkerBuilder
            .from(context, RecomputeWorker::class.java)
            .setWorkerFactory(recording)
            .setInputData(WorkerInputs.recompute(RecomputeRequest.Nights(listOf("ps:x"))))
            .build()
        assertEquals(3, created.size)
        assertTrue("HiltWorkerFactory returned null for some worker: $created", created.all { it != null })

        val runner = field<EstimateNewNightsRunner>(estimate, "runner")
        val sync = field<NewNightsSync>(runner, "sync")
        val pipeline = field<NightPipeline>(sync, "pipeline")
        val repository = field<Any>(pipeline, "repository")
        assertTrue("pipeline repository is ${repository.javaClass}", repository is BackgroundRuleHealthRepository)
        assertTrue(field<Any>(repository, "delegate") is HealthConnectRepository)
        assertTrue(field<Any>(sync, "repository") is BackgroundRuleHealthRepository)

        val estimator = field<Any>(pipeline, "estimator")
        assertTrue("estimator is ${estimator.javaClass}", estimator is SleepStagerNightEstimator)
        val provider = field<BundledHybridModelProvider>(estimator, "hybridModels")
        assertTrue(field<Any>(provider, "source") is AndroidModelAssetSource)
        val model: HybridModel? = runBlocking { provider.hybridModel() }
        assertNotNull("the bundled model was not loaded from the app's assets", model)
        assertNull(checkNotNull(model).compatibilityProblem())
        val onDisk = HybridModel.parse(File(ASSET_FILE).readText())
        assertEquals(onDisk.modelId, model.modelId)
        assertEquals(onDisk.fingerprint(), model.fingerprint())

        val lines = ShadowLog.getLogsForTag("PulseStages").map { it.msg }
        assertTrue(lines.toString(), lines.any { it.contains("hybrid model ${model.modelId} loaded") })
    }

    private fun awaitUnfinished(
        workManager: WorkManager,
        name: String,
        matches: (WorkInfo) -> Boolean = { true },
    ): WorkInfo {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            workManager
                .getWorkInfosForUniqueWork(name)
                .get()
                .firstOrNull { !it.state.isFinished && matches(it) }
                ?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("$name was not enqueued as expected")
    }

    private fun workerClassOf(
        workManager: WorkManager,
        info: WorkInfo,
    ): String {
        val impl = workManager as WorkManagerImpl
        return checkNotNull(impl.workDatabase.workSpecDao().getWorkSpec(info.id.toString())).workerClassName
    }

    private companion object {
        val ASSET_FILE: String = File("src/main/assets/models/hybrid_model.json").absolutePath
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 50L
    }
}

/** The value of the field [name] of [owner] (declared in its class or a superclass): the wiring is not public API. */
@Suppress("UNCHECKED_CAST")
internal fun <T> field(
    owner: Any,
    name: String,
): T {
    var type: Class<*>? = owner.javaClass
    while (type != null) {
        type.declaredFields.firstOrNull { it.name == name }?.let {
            it.isAccessible = true
            return it.get(owner) as T
        }
        type = type.superclass
    }
    throw AssertionError("no field $name in ${owner.javaClass}")
}
