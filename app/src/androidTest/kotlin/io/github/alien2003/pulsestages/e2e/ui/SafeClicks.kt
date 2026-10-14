package io.github.alien2003.pulsestages.e2e.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import io.github.alien2003.pulsestages.e2e.safety.SafetyViolation
import java.util.concurrent.atomic.AtomicInteger

/**
 * The only way the UI tour clicks: before the click the node's own texts and content descriptions are checked against
 * the labels of every action that writes, deletes, schedules work, changes a setting, exports, shares or leaves the app
 * ([FORBIDDEN], resolved in the installed app's language), and switches, checkboxes and text fields are refused unless
 * the caller allows a toggle explicitly (only the debug build's demo-mode row). A refused click throws
 * [SafetyViolation] (an Error: it fails the test and is never swallowed) instead of clicking.
 */
object SafeClicks {
    /** Names of the strings of every action the tour must never trigger. */
    val FORBIDDEN: List<String> =
        listOf(
            // Home: work, deletion, permissions, other apps.
            "home_action_estimate",
            "home_action_backfill",
            "home_action_backfill_resume",
            "home_action_recompute_all",
            "home_action_recompute_stale",
            "home_action_delete_all",
            "home_action_grant",
            "home_action_grant_history",
            "home_action_grant_background",
            "home_action_open_hc",
            "home_action_open_hc_settings",
            "home_action_install_hc",
            "home_action_allow_notifications",
            "home_action_notification_settings",
            // Dialog confirmations and the backfill's cancel.
            "action_delete",
            "action_cancel",
            "action_ok",
            "action_retry",
            "action_refresh",
            // Night detail.
            "night_action_recompute",
            "night_action_delete",
            "night_action_export",
            // Compare.
            "compare_action_calibrate",
            "compare_action_reset",
            // Settings and sources.
            "settings_action_export",
            "settings_action_import",
            "settings_action_reset",
            "settings_action_reset_emissions",
            "settings_stale_recompute",
            "sources_hr_any_title",
            "sources_manual_add",
            "cd_remove_source",
            "cd_move_up",
            "cd_move_down",
            // Log.
            "log_action_share",
        )

    /** Clicks refused so far. */
    val refused: AtomicInteger = AtomicInteger(0)

    private val labels: List<Pair<String, Boolean>> by lazy {
        FORBIDDEN.map { name -> AppText.prefix(name) to AppText.isFormat(name) }.filter { it.first.isNotBlank() }
    }

    /** Clicks [node] through its click action after the checks; [allowToggle] permits a switch row. */
    fun click(
        node: SemanticsNodeInteraction,
        allowToggle: Boolean = false,
    ) {
        check(node.fetchSemanticsNode(), allowToggle)
        node.performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Throws [SafetyViolation] when clicking [node] could trigger a forbidden action. */
    fun check(
        node: SemanticsNode,
        allowToggle: Boolean,
    ) {
        val config = node.config
        val texts =
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        val hit =
            texts.firstNotNullOfOrNull { text ->
                labels.firstOrNull { (label, format) -> if (format) text.startsWith(label) else text == label }?.first
            }
        val reason =
            when {
                hit != null -> "it is the forbidden action \"$hit\""
                config.getOrNull(SemanticsActions.SetText) != null -> "it is a text field"
                !allowToggle && config.getOrNull(SemanticsProperties.ToggleableState) != null -> "it is a switch or checkbox"
                config.getOrNull(SemanticsActions.OnClick) == null -> "it has no click action"
                else -> null
            }
        if (reason != null) {
            refused.incrementAndGet()
            throw SafetyViolation("refusing to click $texts: $reason")
        }
    }
}
