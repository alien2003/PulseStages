// detekt treats only src/test as tests; this is the test source set of the debug variant (the demo code is debug-only).
@file:Suppress("FunctionNaming")

package io.github.alien2003.pulsestages.demo

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import io.github.alien2003.pulsestages.data.local.db.PulseStagesDatabase
import io.github.alien2003.pulsestages.data.local.di.ApplicationScope
import io.github.alien2003.pulsestages.data.local.settings.SettingsRepository
import io.github.alien2003.pulsestages.source.HealthConnectReferenceSource
import io.github.alien2003.pulsestages.source.LedgerNightsSource
import io.github.alien2003.pulsestages.source.ScreenSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/**
 * The debug build's Hilt graph (the production modules, debug variant): the screens' sources follow the demo-mode
 * store at runtime, and the switch is not a setting: switching it leaves the settings and their export alone (and so
 * the params hash, which is computed from them).
 */
@HiltAndroidTest
@Config(application = HiltTestApplication::class)
@RunWith(RobolectricTestRunner::class)
class DemoModeGraphTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var store: DemoModeStore

    @Inject lateinit var screenSources: ScreenSources

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var database: PulseStagesDatabase

    @Inject
    @field:ApplicationScope
    lateinit var applicationScope: CoroutineScope

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @After
    fun tearDown() {
        database.close()
        applicationScope.cancel()
    }

    @Test
    fun theStoreSwapsTheScreensSourcesAtRuntime() =
        runBlocking {
            assertFalse("demo mode is off by default", store.enabled.value)
            assertTrue(screenSources.nights.first() is LedgerNightsSource)
            assertTrue(screenSources.references.first() is HealthConnectReferenceSource)
            val before = settings.settings.first()
            val exported = settings.exportJson(before)
            assertFalse("the settings export has no demo flag", exported.contains("demo", ignoreCase = true))

            store.setEnabled(true)
            assertTrue(screenSources.nights.first() is DemoNightsSource)
            assertTrue(screenSources.references.first() is DemoReferenceSource)
            assertEquals("demo mode is not a setting", before, settings.settings.first())
            assertEquals("demo mode is not exported", exported, settings.exportJson(settings.settings.first()))

            store.setEnabled(false)
            assertTrue(screenSources.nights.first() is LedgerNightsSource)
            assertTrue(screenSources.references.first() is HealthConnectReferenceSource)
        }
}
