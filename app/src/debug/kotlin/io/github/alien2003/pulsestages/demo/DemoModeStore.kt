package io.github.alien2003.pulsestages.demo

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The demo-mode switch of debug builds (Settings → "Debug build", docs/SCREENSHOTS.md). A per-device debug convenience
 * kept in its own private preferences file, so that it survives a restart: it is not a setting (not part of
 * AppSettings, the settings export or the params hash), and release builds do not have it.
 */
@Singleton
class DemoModeStore(
    private val preferences: SharedPreferences,
) {
    /** Reads the switch from the app's private preferences file [PREFERENCES]. */
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE))

    private val mutableEnabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))

    /** Whether the Nights, night detail and Compare screens show synthetic demo data. */
    val enabled: StateFlow<Boolean> = mutableEnabled.asStateFlow()

    /** Switches demo mode on or off; the screens follow at once. */
    fun setEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_ENABLED, enabled) }
        mutableEnabled.value = enabled
    }

    /** Constants. */
    companion object {
        /** Name of the preferences file. */
        const val PREFERENCES: String = "pulsestages_demo_mode"

        private const val KEY_ENABLED = "enabled"
    }
}
