package io.github.alien2003.pulsestages.demo

import android.content.Context
import androidx.core.content.edit

/**
 * The demo-mode switch of debug builds (docs/SCREENSHOTS.md). Stored in the app's private preferences so that it
 * survives process death; read when a ViewModel of the Nights or Compare screen is created.
 */
object DemoMode {
    /** Boolean intent extra of [DemoActivity]: true switches demo mode on, false off; absent toggles. */
    const val EXTRA_ENABLED: String = "enabled"

    private const val PREFERENCES = "pulsestages_demo_mode"
    private const val KEY_ENABLED = "enabled"

    /** Whether the Nights and Compare screens show synthetic demo data. */
    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_ENABLED, false)

    /** Switches demo mode on or off. */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        preferences(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
