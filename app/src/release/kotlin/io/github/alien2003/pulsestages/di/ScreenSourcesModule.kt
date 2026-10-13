package io.github.alien2003.pulsestages.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.source.HealthConnectReferenceSource
import io.github.alien2003.pulsestages.source.LedgerNightsSource
import io.github.alien2003.pulsestages.source.NightsSource
import io.github.alien2003.pulsestages.source.ReferenceSource

/**
 * Data of the Nights and Compare screens in release builds: always the real ledger and Health Connect (the debug build
 * has its own module that can switch to synthetic demo data; release builds contain no demo code).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScreenSourcesModule {
    /** Nights from the ledger and Health Connect. */
    @Binds
    abstract fun bindNightsSource(implementation: LedgerNightsSource): NightsSource

    /** Reference nights from Health Connect. */
    @Binds
    abstract fun bindReferenceSource(implementation: HealthConnectReferenceSource): ReferenceSource
}
