package io.github.alien2003.pulsestages.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.github.alien2003.pulsestages.MainActivity
import io.github.alien2003.pulsestages.R

/**
 * Debug builds only: switches demo mode ([DemoMode]) and restarts the app's task, so that every screen is created
 * again with the selected data. Without the [DemoMode.EXTRA_ENABLED] extra (the launcher entry) it toggles the mode.
 * Shows no UI of its own.
 */
class DemoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val enabled =
            if (intent.hasExtra(DemoMode.EXTRA_ENABLED)) {
                intent.getBooleanExtra(DemoMode.EXTRA_ENABLED, true)
            } else {
                !DemoMode.isEnabled(this)
            }
        DemoMode.setEnabled(this, enabled)
        Toast.makeText(this, if (enabled) R.string.demo_toast_on else R.string.demo_toast_off, Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
