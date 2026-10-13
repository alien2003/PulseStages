// detekt treats only src/test as tests; this is the test source set of the debug variant (the demo code is debug-only).
@file:Suppress("FunctionNaming")

package io.github.alien2003.pulsestages.demo

import android.app.Application
import android.content.Context
import io.github.alien2003.pulsestages.testutil.clear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The demo-mode switch of debug builds: off by default, kept in its own preferences file, flipped by its ViewModel. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DemoModeStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun theSwitchIsOffByDefaultAndSurvivesARestart() {
        val store = DemoModeStore(context)
        assertFalse(store.enabled.value)

        store.setEnabled(true)
        assertTrue(store.enabled.value)
        assertTrue("read back after a restart", DemoModeStore(context).enabled.value)

        store.setEnabled(false)
        assertFalse(store.enabled.value)
        assertFalse("read back after a restart", DemoModeStore(context).enabled.value)
    }

    @Test
    fun theSwitchHasItsOwnPreferencesFile() {
        DemoModeStore(context).setEnabled(true)
        val preferences = context.getSharedPreferences(DemoModeStore.PREFERENCES, Context.MODE_PRIVATE)
        assertEquals(mapOf("enabled" to true), preferences.all)
    }

    @Test
    fun theSettingsCardFlipsTheSwitch() {
        val store = DemoModeStore(context)
        val viewModel = DemoModeViewModel(store)
        assertFalse(viewModel.enabled.value)

        viewModel.setEnabled(true)
        assertTrue(store.enabled.value)
        assertTrue(viewModel.enabled.value)

        viewModel.setEnabled(false)
        assertFalse(store.enabled.value)
        assertFalse(viewModel.enabled.value)
        clear(viewModel)
    }
}
