package io.github.alien2003.pulsestages.platform

import io.github.alien2003.pulsestages.data.local.di.ApplicationScope
import io.github.alien2003.pulsestages.work.schedule.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the background work scheduled for the lifetime of the process: started from `Application.onCreate`, it makes
 * WorkManager's periodic check and daily run match the schedule settings now (WorkScheduler.ensureScheduled) and again
 * after every change of those settings (WorkScheduler.keepScheduled). Runs in the application scope; calling [start]
 * again while it runs does nothing.
 */
@Singleton
class BackgroundSchedule
    @Inject
    constructor(
        private val scheduler: WorkScheduler,
        @param:ApplicationScope private val scope: CoroutineScope,
    ) {
        private var job: Job? = null

        /** Starts following the schedule settings (idempotent). */
        @Synchronized
        fun start() {
            if (job?.isActive == true) return
            job = scope.launch { scheduler.keepScheduled() }
        }
    }
