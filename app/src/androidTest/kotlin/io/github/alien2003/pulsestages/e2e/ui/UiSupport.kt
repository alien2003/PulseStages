package io.github.alien2003.pulsestages.e2e.ui

import android.app.KeyguardManager
import android.app.UiAutomation
import android.graphics.Bitmap
import android.os.PowerManager
import android.os.SystemClock
import io.github.alien2003.pulsestages.e2e.E2e
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.FileOutputStream

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
    /** Instrumentation argument: how long to wait for the phone to be unlocked (the runner's --unlock-timeout). */
    const val ARG_UNLOCK_TIMEOUT_SEC: String = "e2eUnlockTimeoutSec"

    private const val DEFAULT_UNLOCK_TIMEOUT_SEC = 300
    private const val POLL_MS = 2_000L
    private const val ANNOUNCE_EVERY_MS = 20_000L
    private const val SECOND_MS = 1_000L

    /**
     * Returns once the screen is on and the phone unlocked; until then asks for it (streamed to the runner) for up to
     * `-e e2eUnlockTimeoutSec` seconds, then fails with a clear message. The suite never unlocks the phone itself.
     */
    fun awaitUnlockedScreen() {
        val power = E2e.app.getSystemService(PowerManager::class.java)
        val keyguard = E2e.app.getSystemService(KeyguardManager::class.java)
        val timeoutMs = E2e.intArg(ARG_UNLOCK_TIMEOUT_SEC, DEFAULT_UNLOCK_TIMEOUT_SEC).coerceAtLeast(0) * SECOND_MS
        val started = SystemClock.elapsedRealtime()
        var announcedAt = Long.MIN_VALUE
        while (!power.isInteractive || keyguard.isKeyguardLocked) {
            val waited = SystemClock.elapsedRealtime() - started
            if (waited >= timeoutMs) break
            if (announcedAt == Long.MIN_VALUE || waited - announcedAt >= ANNOUNCE_EVERY_MS) {
                announcedAt = waited
                E2e.progress(
                    "the phone is locked or its screen is off: UNLOCK IT NOW (PIN, pattern, fingerprint or face) and leave it on " +
                        "(waiting ${(timeoutMs - waited) / SECOND_MS} s more)",
                )
            }
            SystemClock.sleep(POLL_MS)
        }
        assertTrue("the screen is off: unlock the phone and keep it on", power.isInteractive)
        assertFalse("the phone is locked: unlock it (PIN, pattern, fingerprint or face) and leave it on", keyguard.isKeyguardLocked)
    }
}
