package io.github.alien2003.pulsestages.e2e.support

import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.alien2003.pulsestages.e2e.E2e
import java.util.Collections

/**
 * Keeps the app's real background work out of the suite's processes. Every `am instrument` starts the installed app's
 * process, and the app's `Application.onCreate` keeps its WorkManager schedule; with the real WorkManager that would
 * reschedule the app's jobs after the force-stop that started the instrumentation and could run an overdue worker of
 * the app inside the test process, with the app's unguarded Health Connect repository. So [install] (called by
 * [io.github.alien2003.pulsestages.e2e.E2eRunner] before the app's `onCreate`) replaces WorkManager in this process by
 * work-testing's test WorkManager:
 * - its database is in memory: the app's WorkManager database is never opened, and what the app's start-up code
 *   enqueues stays in this process;
 * - its only scheduler is the test scheduler: nothing is handed to JobScheduler or AlarmManager;
 * - its default process is one that never exists, so WorkManager's default-process tasks (the force-stop check, which
 *   would reconcile or cancel the app's JobScheduler jobs, and the listener that enables or disables the app's
 *   RescheduleReceiver) never run;
 * - its worker factory never creates a worker of the app: any work the test scheduler starts gets a worker that does
 *   nothing and fails (its class name is recorded in [refusedWorkers]).
 * The app's own JobScheduler jobs were cancelled by the force-stop of `am instrument`; the runner opens the app once at
 * the end, and the app's real WorkManager registers them again from its database.
 */
object E2eWorkManager {
    /** The default process WorkManager is told about: no process of the app has this name. */
    const val NO_DEFAULT_PROCESS: String = "${E2e.APP_PACKAGE}:e2e-no-workmanager"

    @Volatile
    private var installed = false

    private val refused: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Class names of the workers this process's WorkManager asked for (none of them ran). */
    fun refusedWorkers(): List<String> = synchronized(refused) { refused.toList() }

    /** Installs the in-memory WorkManager; fails when WorkManager was initialized before (then nothing may run). */
    fun install(context: Context) {
        check(!WorkManager.isInitialized()) { "WorkManager was initialized before the suite could replace it: refusing to run" }
        val configuration =
            Configuration
                .Builder()
                .setWorkerFactory(RefusingWorkerFactory)
                .setDefaultProcessName(NO_DEFAULT_PROCESS)
                .setMinimumLoggingLevel(Log.INFO)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            configuration,
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        installed = true
    }

    /** Fails unless [install] ran in this process (every test checks it through [E2eStatusRule]). */
    fun requireInstalled() {
        check(installed && WorkManager.isInitialized()) {
            "the in-memory WorkManager is not installed: run the suite with io.github.alien2003.pulsestages.e2e.E2eRunner"
        }
    }

    private object RefusingWorkerFactory : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker {
            refused += workerClassName
            Log.w(E2e.TAG, "the suite's WorkManager does not run the app's worker $workerClassName")
            return RefusedWorker(appContext, workerParameters)
        }
    }

    /** Stands in for any worker of the app: does nothing and fails. */
    private class RefusedWorker(
        context: Context,
        parameters: WorkerParameters,
    ) : Worker(context, parameters) {
        override fun doWork(): Result = Result.failure()
    }
}
