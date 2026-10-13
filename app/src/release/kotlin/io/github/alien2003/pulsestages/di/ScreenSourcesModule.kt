package io.github.alien2003.pulsestages.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.source.RealScreenSources
import io.github.alien2003.pulsestages.source.ScreenSources

/**
 * Data of the Nights and Compare screens in release builds: always the real ledger and Health Connect (the debug build
 * has its own module that can switch to synthetic demo data at runtime; release builds contain no demo code).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ScreenSourcesModule {
    /** The ledger and Health Connect. */
    @Binds
    abstract fun bindScreenSources(implementation: RealScreenSources): ScreenSources
}
