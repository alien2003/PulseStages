package io.github.alien2003.pulsestages

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.alien2003.pulsestages.platform.BackgroundSchedule
import javax.inject.Inject

/**
 * Application class: the Hilt component root, the WorkManager configuration with the Hilt worker factory, and the
 * start of the background schedule (periodic and daily runs follow the schedule settings from app start on).
 */
@HiltAndroidApp
class PulseStagesApp :
    Application(),
    Configuration.Provider {
    /** Creates @HiltWorker workers with their injected dependencies. */
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** Keeps WorkManager's periodic and daily runs in line with the schedule settings. */
    @Inject
    lateinit var backgroundSchedule: BackgroundSchedule

    override fun onCreate() {
        super.onCreate()
        backgroundSchedule.start()
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setWorkerFactory(workerFactory)
                .build()
}
