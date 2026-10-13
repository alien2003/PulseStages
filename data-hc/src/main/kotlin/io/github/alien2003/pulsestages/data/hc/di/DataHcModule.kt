package io.github.alien2003.pulsestages.data.hc.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.data.hc.DelaySleeper
import io.github.alien2003.pulsestages.data.hc.HealthConnectRepository
import io.github.alien2003.pulsestages.data.hc.HealthRepository
import io.github.alien2003.pulsestages.data.hc.RetryPolicy
import io.github.alien2003.pulsestages.data.hc.Sleeper
import io.github.alien2003.pulsestages.data.hc.pipeline.DefaultTitleProvider
import io.github.alien2003.pulsestages.data.hc.pipeline.ResourceDefaultTitleProvider
import io.github.alien2003.pulsestages.data.hc.pipeline.SystemZoneOffsetProvider
import io.github.alien2003.pulsestages.data.hc.pipeline.ZoneOffsetProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Provides the Health Connect layer: our package name, the dispatchers, the retry policy and sleeper of the
 * repository, the zone-offset fallback and the default title. The pipeline classes are constructor-injected; the
 * [io.github.alien2003.pulsestages.data.hc.pipeline.NightEstimator] binding (an adapter over core-algo's
 * `SleepStager`) must be provided by :work or :app.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataHcModule {
    /** This app's package name (data origin of our own sessions). */
    @Provides
    @OwnPackageName
    fun provideOwnPackageName(
        @ApplicationContext context: Context,
    ): String = context.packageName

    /** Health Connect calls run on the IO dispatcher. */
    @Suppress("InjectDispatcher") // This module is the injection point.
    @Provides
    @HealthConnectDispatcher
    fun provideHealthConnectDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** Stage estimation is CPU-bound. */
    @Suppress("InjectDispatcher") // This module is the injection point.
    @Provides
    @EstimationDispatcher
    fun provideEstimationDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /** Exponential backoff 1, 2, 4, 8, 16 s for quota / transient Health Connect errors. */
    @Provides
    fun provideRetryPolicy(): RetryPolicy = RetryPolicy.DEFAULT

    /** Real delays. */
    @Provides
    fun provideSleeper(): Sleeper = DelaySleeper

    /** Fallback zone offsets of the device's default zone. */
    @Provides
    fun provideZoneOffsetProvider(): ZoneOffsetProvider = SystemZoneOffsetProvider
}

/** Interface bindings of the Health Connect layer. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataHcBindingsModule {
    /** The real Health Connect repository. */
    @Binds
    abstract fun bindHealthRepository(implementation: HealthConnectRepository): HealthRepository

    /** The localized default title from resources. */
    @Binds
    abstract fun bindDefaultTitleProvider(implementation: ResourceDefaultTitleProvider): DefaultTitleProvider
}
