package io.github.alien2003.pulsestages.work.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.data.hc.pipeline.NightEstimator
import io.github.alien2003.pulsestages.data.hc.usecase.HybridModelProvider
import io.github.alien2003.pulsestages.work.estimate.AndroidModelAssetSource
import io.github.alien2003.pulsestages.work.estimate.BundledHybridModelProvider
import io.github.alien2003.pulsestages.work.estimate.ModelAssetSource
import io.github.alien2003.pulsestages.work.estimate.SleepStagerNightEstimator
import io.github.alien2003.pulsestages.work.notify.SleepAsAndroidNotifications
import io.github.alien2003.pulsestages.work.run.RetryLimits
import io.github.alien2003.pulsestages.work.saa.BroadcastSyncRequestSender
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidFollowUps
import io.github.alien2003.pulsestages.work.saa.SleepAsAndroidNotices
import io.github.alien2003.pulsestages.work.saa.SyncRequestSender
import io.github.alien2003.pulsestages.work.saa.WorkManagerSleepAsAndroidFollowUps
import io.github.alien2003.pulsestages.work.schedule.LocalZone
import io.github.alien2003.pulsestages.work.schedule.SystemLocalZone
import io.github.alien2003.pulsestages.work.schedule.WorkManagerWorkScheduler
import io.github.alien2003.pulsestages.work.schedule.WorkScheduler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/** Qualifies the dispatcher for blocking file I/O of this module (reading the model asset). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WorkIoDispatcher

/**
 * Interface bindings of the background-work layer: the production [NightEstimator] (core-algo's SleepStager with the
 * bundled hybrid model and the active personal model), the bundled-model provider, the WorkManager scheduler and the
 * sync requests to Sleep as Android (a broadcast, the follow-up as a work, the notice of a changed night as a
 * notification).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class WorkBindingsModule {
    /** The SleepStager adapter. */
    @Binds
    abstract fun bindNightEstimator(implementation: SleepStagerNightEstimator): NightEstimator

    /** The hybrid model bundled in the app's assets. */
    @Binds
    abstract fun bindHybridModelProvider(implementation: BundledHybridModelProvider): HybridModelProvider

    /** WorkManager scheduling. */
    @Binds
    abstract fun bindWorkScheduler(implementation: WorkManagerWorkScheduler): WorkScheduler

    /** Sleep as Android's sync request as a broadcast. */
    @Binds
    abstract fun bindSyncRequestSender(implementation: BroadcastSyncRequestSender): SyncRequestSender

    /** The work of the sync request to Sleep as Android (its checks and its follow-up) as a WorkManager work. */
    @Binds
    abstract fun bindSleepAsAndroidFollowUps(implementation: WorkManagerSleepAsAndroidFollowUps): SleepAsAndroidFollowUps

    /** The notice of a night that changed after Sleep as Android was asked to sync, as a notification. */
    @Binds
    abstract fun bindSleepAsAndroidNotices(implementation: SleepAsAndroidNotifications): SleepAsAndroidNotices
}

/** Values of the background-work layer. */
@Module
@InstallIn(SingletonComponent::class)
object WorkModule {
    /** The app's assets. */
    @Provides
    fun provideModelAssetSource(
        @ApplicationContext context: Context,
    ): ModelAssetSource = AndroidModelAssetSource(context.assets)

    /** Blocking file I/O runs on the IO dispatcher. */
    @Suppress("InjectDispatcher") // This module is the injection point.
    @Provides
    @WorkIoDispatcher
    fun provideWorkIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** Default retry limits of the workers. */
    @Provides
    fun provideRetryLimits(): RetryLimits = RetryLimits()

    /** The device's time zone. */
    @Provides
    fun provideLocalZone(): LocalZone = SystemLocalZone
}
