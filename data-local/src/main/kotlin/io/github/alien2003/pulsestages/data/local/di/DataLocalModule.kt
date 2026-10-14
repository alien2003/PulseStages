package io.github.alien2003.pulsestages.data.local.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.dataStoreFile
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.data.local.db.BackfillStateDao
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.LedgerMigrations
import io.github.alien2003.pulsestages.data.local.db.LogDao
import io.github.alien2003.pulsestages.data.local.db.NightBaselineDao
import io.github.alien2003.pulsestages.data.local.db.PersonalModelDao
import io.github.alien2003.pulsestages.data.local.db.PulseStagesDatabase
import io.github.alien2003.pulsestages.data.local.db.SourceSessionDao
import io.github.alien2003.pulsestages.data.local.log.AndroidLogcatSink
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.log.RoomAppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.AppSettingsDataStoreFactory
import io.github.alien2003.pulsestages.data.local.settings.DataStoreSettingsRepository
import io.github.alien2003.pulsestages.data.local.settings.DebugSettings
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.data.local.sync.SyncStatePreferences
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Clock
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifies the application-lifetime [CoroutineScope] (SupervisorJob + Dispatchers.Default). It owns background
 * work that must outlive a screen or a worker but not the process: the logger's writer and the DataStores.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * Provides the local data layer: the settings and sync-state DataStores, the [SettingsRepository] (see
 * [DataLocalBindingsModule]), the [AppLogger], the application [CoroutineScope] and the wall [Clock] (UTC); the Room
 * ledger comes from [DatabaseModule]. Other modules inject these and must not provide their own [Clock] or
 * [ApplicationScope] scope.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataLocalModule {
    private const val TAG = "DataLocal"

    /** The application scope; uncaught failures of its children are reported to logcat instead of crashing. */
    @Suppress("InjectDispatcher") // This module is the injection point.
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope {
        val handler =
            CoroutineExceptionHandler { _, throwable ->
                AndroidLogcatSink.write(
                    LogLevel.ERROR,
                    "[$TAG] uncaught failure in the application scope\n${throwable.stackTraceToString()}",
                )
            }
        return CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }

    /** Wall clock in UTC; everything that needs "now" injects it so tests can fix time. */
    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()

    /** The typed settings DataStore; a corrupted file is reset to defaults and logged. */
    @Provides
    @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
        logger: Lazy<AppLogger>,
    ): DataStore<AppSettings> =
        AppSettingsDataStoreFactory.create(
            scope = ioChildScope(scope),
            onCorruption = { exception ->
                logger.get().e(TAG, "Settings file was unreadable and has been reset to defaults", throwable = exception)
            },
            produceFile = { context.dataStoreFile(AppSettingsDataStoreFactory.FILE_NAME) },
        )

    /** The Preferences DataStore behind [SyncStateStore]. */
    @Provides
    @Singleton
    @SyncStatePreferences
    fun provideSyncStateDataStore(
        @ApplicationContext context: Context,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<Preferences> =
        SyncStateStore.createDataStore(ioChildScope(scope)) { context.preferencesDataStoreFile(SyncStateStore.FILE_NAME) }

    /** The Room-backed logger, following `DebugSettings.logLevel`. */
    @Provides
    @Singleton
    fun provideAppLogger(
        logDao: LogDao,
        settingsRepository: SettingsRepository,
        @ApplicationScope scope: CoroutineScope,
        clock: Clock,
    ): AppLogger {
        val minLevel: StateFlow<LogLevel> =
            settingsRepository.settings
                .map { it.debug.logLevel }
                .catch { failure ->
                    AndroidLogcatSink.write(
                        LogLevel.WARN,
                        "[$TAG] cannot read the log level, keeping the last one\n${failure.stackTraceToString()}",
                    )
                }.stateIn(scope, SharingStarted.Eagerly, DebugSettings().logLevel)
        return RoomAppLogger(logDao, minLevel, scope, clock, AndroidLogcatSink)
    }

    /** A child of [parent] running on the IO dispatcher (DataStore does blocking file I/O on its scope). */
    @Suppress("InjectDispatcher") // This module is the injection point.
    private fun ioChildScope(parent: CoroutineScope): CoroutineScope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO)
}

/** The Room ledger and its DAOs. */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** The Room ledger. */
    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): PulseStagesDatabase =
        Room
            .databaseBuilder(context, PulseStagesDatabase::class.java, PulseStagesDatabase.FILE_NAME)
            .apply { LedgerMigrations.ALL.forEach { addMigrations(it) } }
            .build()

    /** DAO of `estimated_nights` / `night_sources`. */
    @Provides
    fun provideEstimatedNightDao(database: PulseStagesDatabase): EstimatedNightDao = database.estimatedNightDao()

    /** DAO of `source_sessions`. */
    @Provides
    fun provideSourceSessionDao(database: PulseStagesDatabase): SourceSessionDao = database.sourceSessionDao()

    /** DAO of `night_baselines`. */
    @Provides
    fun provideNightBaselineDao(database: PulseStagesDatabase): NightBaselineDao = database.nightBaselineDao()

    /** DAO of `log_entries`. */
    @Provides
    fun provideLogDao(database: PulseStagesDatabase): LogDao = database.logDao()

    /** DAO of `backfill_state`. */
    @Provides
    fun provideBackfillStateDao(database: PulseStagesDatabase): BackfillStateDao = database.backfillStateDao()

    /** DAO of `personal_models`. */
    @Provides
    fun providePersonalModelDao(database: PulseStagesDatabase): PersonalModelDao = database.personalModelDao()
}

/** Interface bindings of the local data layer. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataLocalBindingsModule {
    /** [SettingsRepository] backed by the settings DataStore. */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(implementation: DataStoreSettingsRepository): SettingsRepository
}
