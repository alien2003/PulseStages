package io.github.alien2003.pulsestages.e2e.support

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.alien2003.pulsestages.demo.DemoModeStore
import io.github.alien2003.pulsestages.e2e.E2e
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

/**
 * The debug build's demo-mode switch as it is persisted ([DemoModeStore], its own preferences file). The UI tour
 * switches it with the real switch in Settings and back; [restore] puts back the exact previous value (removing the key
 * when there was none), so the next start of the app reads what it read before the run. [DemoFlagBackup] keeps the
 * previous value on disk while the switch may be flipped, so that a crash or a force-stop of the tour cannot leave it
 * flipped: the runner's cleanup step restores it.
 *
 * @property present the key existed.
 * @property enabled its value (false when absent, as the app reads it).
 */
@Serializable
data class DemoFlag(
    val present: Boolean,
    val enabled: Boolean,
) {
    /** Reading and restoring. */
    companion object {
        /** The key DemoModeStore stores the switch under. */
        private const val KEY = "enabled"

        private val preferences: SharedPreferences
            get() = E2e.app.getSharedPreferences(DemoModeStore.PREFERENCES, Context.MODE_PRIVATE)

        /** The persisted value now. */
        fun read(): DemoFlag {
            val prefs = preferences
            return DemoFlag(prefs.contains(KEY), prefs.getBoolean(KEY, false))
        }

        /** Writes [previous] back exactly (synchronously) unless it is already what is stored; fails when it did not stick. */
        fun restore(previous: DemoFlag) {
            if (read() == previous) return
            preferences.edit(commit = true) {
                if (previous.present) putBoolean(KEY, previous.enabled) else remove(KEY)
            }
            check(read() == previous) { "the demo-mode flag could not be restored to $previous (it is ${read()})" }
        }
    }
}

/**
 * The demo-mode flag as it was before the UI tour, in `e2e/demo-flag-before.json` while the tour may have it flipped.
 * Written synchronously before the first toggle and deleted only after a verified restore; [restoreIfPresent] (the
 * cleanup step, and the next run's first steps) puts it back after a crash, a timeout or an interrupt.
 */
object DemoFlagBackup {
    /** File name inside the e2e directory; kept when a run clears the directory. */
    const val FILE_NAME: String = "demo-flag-before.json"

    private val file: File get() = E2e.file(FILE_NAME)

    /** Records [flag] (the value before the tour), synchronously on disk. */
    fun save(flag: DemoFlag) {
        check(!file.exists()) { "a backup of the demo-mode flag exists already: restore it first" }
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(E2e.json.encodeToString(DemoFlag.serializer(), flag).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        check(temporary.renameTo(file)) { "the backup of the demo-mode flag could not be written" }
    }

    /** The recorded value, or null without a backup. */
    fun read(): DemoFlag? = if (file.isFile) E2e.json.decodeFromString(DemoFlag.serializer(), file.readText()) else null

    /** Restores the recorded value (when there is one), deletes the backup and returns the value; null without a backup. */
    fun restoreIfPresent(): DemoFlag? {
        val previous = read() ?: return null
        DemoFlag.restore(previous)
        file.delete()
        return previous
    }

    /** Deletes the backup once [DemoFlag.read] equals [restored]. */
    fun deleteAfterRestore(restored: DemoFlag) {
        if (DemoFlag.read() == restored) file.delete()
    }
}
