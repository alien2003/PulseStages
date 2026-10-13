package io.github.alien2003.pulsestages.data.local.di

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.github.alien2003.pulsestages.data.local.db.BackfillStateDao
import io.github.alien2003.pulsestages.data.local.db.EstimatedNightDao
import io.github.alien2003.pulsestages.data.local.db.LogDao
import io.github.alien2003.pulsestages.data.local.db.NightBaselineDao
import io.github.alien2003.pulsestages.data.local.db.PersonalModelDao
import io.github.alien2003.pulsestages.data.local.db.PulseStagesDatabase
import io.github.alien2003.pulsestages.data.local.db.SourceSessionDao
import io.github.alien2003.pulsestages.data.local.log.AppLogger
import io.github.alien2003.pulsestages.data.local.settings.AppSettings
import io.github.alien2003.pulsestages.data.local.settings.LogLevel
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.data.local.settings.SettingsUpdateResult
import io.github.alien2003.pulsestages.data.local.sync.SyncStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import javax.inject.Inject

/** Builds the module's real Hilt graph (file-backed database and DataStores in Robolectric's app directory). */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class DataLocalModuleTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var syncStateStore: SyncStateStore

    @Inject lateinit var logger: AppLogger

    @Inject lateinit var database: PulseStagesDatabase

    @Inject lateinit var estimatedNightDao: EstimatedNightDao

    @Inject lateinit var sourceSessionDao: SourceSessionDao

    @Inject lateinit var nightBaselineDao: NightBaselineDao

    @Inject lateinit var logDao: LogDao

    @Inject lateinit var backfillStateDao: BackfillStateDao

    @Inject lateinit var personalModelDao: PersonalModelDao

    @Inject lateinit var clock: Clock

    @Inject
    @field:ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        database.close()
        applicationScope.cancel()
    }

    @Test
    fun graphProvidesWorkingSingletons() =
        runBlocking {
            assertEquals(AppSettings(), settingsRepository.settings.first())
            assertNotNull(estimatedNightDao)
            assertNotNull(sourceSessionDao)
            assertNotNull(nightBaselineDao)
            assertNotNull(backfillStateDao)
            assertNotNull(personalModelDao)
            assertTrue(clock.millis() > 0)

            // The logger follows the stored log level.
            val update = settingsRepository.update { it.copy(debug = it.debug.copy(logLevel = LogLevel.DEBUG)) }
            assertTrue(update is SettingsUpdateResult.Applied)
            settingsRepository.settings.first { it.debug.logLevel == LogLevel.DEBUG }
            awaitLevelPropagation()
            logger.d("Test", "debug line after the level change")
            logger.flush()
            assertEquals(listOf("debug line after the level change"), logDao.getAll().map { it.message })

            syncStateStore.saveNewToken(listOf("com.example.a"), "token")
            assertEquals("token", syncStateStore.tokenFor(listOf("com.example.a"))?.token)
        }

    /** The logger's level follows the settings asynchronously (on the application scope); wait until DEBUG passes. */
    private suspend fun awaitLevelPropagation() {
        repeat(POLL_ATTEMPTS) {
            logger.d("Probe", "probe")
            logger.flush()
            if (logDao.count() > 0) {
                logDao.clear()
                return
            }
            delay(POLL_DELAY_MS)
        }
    }

    private companion object {
        const val POLL_ATTEMPTS = 100
        const val POLL_DELAY_MS = 20L
    }
}
