package io.github.alien2003.pulsestages.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.demo.DemoModeStore
import io.github.alien2003.pulsestages.demo.DemoNightsSource
import io.github.alien2003.pulsestages.demo.DemoReferenceSource
import io.github.alien2003.pulsestages.demo.DemoScreenSources
import io.github.alien2003.pulsestages.source.HealthConnectReferenceSource
import io.github.alien2003.pulsestages.source.LedgerNightsSource
import io.github.alien2003.pulsestages.source.ScreenSources
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Data of the Nights and Compare screens in debug builds: the real ledger and Health Connect, or synthetic demo data
 * while demo mode is on ([DemoModeStore], switched in Settings, see docs/SCREENSHOTS.md). The screens follow the switch
 * at runtime. The release build binds the real sources only and contains none of this code.
 */
@Module
@InstallIn(SingletonComponent::class)
object ScreenSourcesModule {
    /** Real or demo data, following the demo-mode switch. */
    @Provides
    @Singleton
    fun provideScreenSources(
        demoMode: DemoModeStore,
        ledger: Provider<LedgerNightsSource>,
        healthConnect: Provider<HealthConnectReferenceSource>,
        demoNights: Provider<DemoNightsSource>,
        demoReferences: Provider<DemoReferenceSource>,
    ): ScreenSources =
        DemoScreenSources(
            enabled = demoMode.enabled,
            realNights = { ledger.get() },
            realReferences = { healthConnect.get() },
            demoNights = { demoNights.get() },
            demoReferences = { demoReferences.get() },
        )
}
