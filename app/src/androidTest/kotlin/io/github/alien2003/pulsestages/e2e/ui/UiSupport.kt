package io.github.alien2003.pulsestages.e2e.ui

import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.graphics.Bitmap
import android.os.PowerManager
import androidx.core.content.edit
import io.github.alien2003.pulsestages.demo.DemoModeStore
import io.github.alien2003.pulsestages.e2e.E2e
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.FileOutputStream

/**
 * The debug build's demo-mode switch as it is persisted ([DemoModeStore], its own preferences file). The UI tour
 * switches it with the real switch in Settings and back; [restore] puts back the exact previous value in a `finally`
 * (removing the key when there was none), so the next start of the app reads what it read before the run.
 *
 * @property present the key existed.
 * @property enabled its value (false when absent, as the app reads it).
 */
data class DemoFlag(
    val present: Boolean,
    val enabled: Boolean,
) {
    /** Reading and restoring. */
    companion object {
        /** The key DemoModeStore stores the switch under. */
        private const val KEY = "enabled"

        private val preferences get() = E2e.app.getSharedPreferences(DemoModeStore.PREFERENCES, Context.MODE_PRIVATE)

        /** The persisted value now. */
        fun read(): DemoFlag {
            val prefs = preferences
            return DemoFlag(prefs.contains(KEY), prefs.getBoolean(KEY, false))
        }

        /** Writes [previous] back exactly (synchronously). */
        fun restore(previous: DemoFlag) {
            preferences.edit(commit = true) {
                if (previous.present) putBoolean(KEY, previous.enabled) else remove(KEY)
            }
        }
    }
}

/** Screenshots of the whole screen (UiAutomation) into `e2e/ui/`. */
object Screenshots {
    private var index = 0

    /** Names of the screenshots taken so far. */
    val taken: MutableList<String> = mutableListOf()

    /** Takes a screenshot named [name]. */
    fun take(name: String) {
        E2e.instrumentation.waitForIdleSync()
        // Connected without suppressing the phone's accessibility services (the default would switch them off while
        // the instrumentation runs).
        val bitmap = E2e.instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        if (bitmap == null) {
            E2e.progress("screenshot $name: not available")
            return
        }
        index++
        val file = E2e.file("ui/%02d-%s.png".format(index, name))
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        bitmap.recycle()
        taken += file.name
    }

    private const val PNG_QUALITY = 100
}

/** What the UI tour needs from the phone. */
object UiPreconditions {
    /** Fails with a clear message unless the screen is on and the phone unlocked (the runner waits for that). */
    fun requireUnlockedScreen() {
        val power = E2e.app.getSystemService(PowerManager::class.java)
        val keyguard = E2e.app.getSystemService(KeyguardManager::class.java)
        assertTrue("the screen is off: unlock the phone and keep it on", power.isInteractive)
        assertFalse("the phone is locked: unlock it (PIN, pattern, fingerprint or face) and leave it on", keyguard.isKeyguardLocked)
    }
}
