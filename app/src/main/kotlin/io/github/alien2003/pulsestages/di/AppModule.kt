package io.github.alien2003.pulsestages.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.platform.AppInfoResolver
import io.github.alien2003.pulsestages.platform.AppVersionSource
import io.github.alien2003.pulsestages.platform.ContentResolverDocumentStore
import io.github.alien2003.pulsestages.platform.DocumentStore
import io.github.alien2003.pulsestages.platform.FileProviderShareableFileWriter
import io.github.alien2003.pulsestages.platform.IoDispatcher
import io.github.alien2003.pulsestages.platform.PackageManagerAppInfoResolver
import io.github.alien2003.pulsestages.platform.PackageManagerAppVersionSource
import io.github.alien2003.pulsestages.platform.ShareableFileWriter
import io.github.alien2003.pulsestages.source.LedgerStaleNightsSource
import io.github.alien2003.pulsestages.source.StaleNightsSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.time.ZoneId

/** Providers of the app module: the I/O dispatcher and the device time zone. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Blocking I/O runs on [Dispatchers.IO]. */
    @Suppress("InjectDispatcher") // This module is the injection point.
    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** The device time zone, read when a ViewModel is created (used to format local times in exported text). */
    @Provides
    fun provideZoneId(): ZoneId = ZoneId.systemDefault()
}

/** Interface bindings of the app module's platform services. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    /** Storage Access Framework documents. */
    @Binds
    abstract fun bindDocumentStore(implementation: ContentResolverDocumentStore): DocumentStore

    /** FileProvider-backed shareable files. */
    @Binds
    abstract fun bindShareableFileWriter(implementation: FileProviderShareableFileWriter): ShareableFileWriter

    /** PackageManager-backed app labels and icons. */
    @Binds
    abstract fun bindAppInfoResolver(implementation: PackageManagerAppInfoResolver): AppInfoResolver

    /** Stale nights from the ledger, recomputed through WorkManager. */
    @Binds
    abstract fun bindStaleNightsSource(implementation: LedgerStaleNightsSource): StaleNightsSource

    /** The installed version name from PackageManager (About card). */
    @Binds
    abstract fun bindAppVersionSource(implementation: PackageManagerAppVersionSource): AppVersionSource
}
