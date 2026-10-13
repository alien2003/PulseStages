package io.github.alien2003.pulsestages.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.demo.DemoMode
import io.github.alien2003.pulsestages.demo.DemoNightsSource
import io.github.alien2003.pulsestages.demo.DemoReferenceSource
import io.github.alien2003.pulsestages.source.HealthConnectReferenceSource
import io.github.alien2003.pulsestages.source.LedgerNightsSource
import io.github.alien2003.pulsestages.source.NightsSource
import io.github.alien2003.pulsestages.source.ReferenceSource
import javax.inject.Provider

/**
 * Data of the Nights and Compare screens in debug builds: the real ledger and Health Connect, or synthetic demo data
 * while demo mode is on ([DemoMode], docs/SCREENSHOTS.md). The choice is made each time a ViewModel is created (the
 * bindings are unscoped); the demo sources themselves are singletons that keep their nights in memory. The release
 * build binds the real sources only and contains none of this code.
 */
@Module
@InstallIn(SingletonComponent::class)
object ScreenSourcesModule {
    /** Nights: demo or ledger. */
    @Provides
    fun provideNightsSource(
        @ApplicationContext context: Context,
        production: Provider<LedgerNightsSource>,
        demo: Provider<DemoNightsSource>,
    ): NightsSource = if (DemoMode.isEnabled(context)) demo.get() else production.get()

    /** Reference nights: demo or Health Connect. */
    @Provides
    fun provideReferenceSource(
        @ApplicationContext context: Context,
        production: Provider<HealthConnectReferenceSource>,
        demo: Provider<DemoReferenceSource>,
    ): ReferenceSource = if (DemoMode.isEnabled(context)) demo.get() else production.get()
}
