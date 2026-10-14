package io.github.alien2003.pulsestages.e2e.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeTestRule
import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.e2e.safety.SafetyViolation
import io.github.alien2003.pulsestages.ui.common.resolve
import io.github.alien2003.pulsestages.ui.settings.SettingsCatalog
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the UI tour may click. Nothing else is ever clicked: [SafeClicks] refuses every node that does not match the
 * kind it is clicked as.
 */
sealed interface Allowed {
    /** A navigation tab: Role.Tab with exactly the label [name] (one of [SafeClicks.TABS]). */
    data class Tab(
        val name: String,
    ) : Allowed

    /** A button or row whose text or content description is exactly the label [name] (one of [SafeClicks.LABELS]). */
    data class Label(
        val name: String,
    ) : Allowed

    /** The option [name] of the Nights filter (one of [SafeClicks.FILTER_OPTIONS]) inside the group `nights_filter_label`. */
    data class FilterOption(
        val name: String,
    ) : Allowed

    /** The expand or collapse button of the settings group titled [title] (one of the settings catalog's titles). */
    data class SectionToggle(
        val title: String,
    ) : Allowed

    /** A row of the Nights list (inside a lazy list) that shows [date], the date of one of the app's own records. */
    data class NightRow(
        val date: String,
    ) : Allowed

    /** The debug build's demo-mode switch (a toggleable row titled `demo_mode_title`). */
    data object DemoSwitch : Allowed
}

/**
 * The only way the UI tour clicks. The UI tour drives the REAL app with its own Hilt graph: its ViewModels hold the
 * app's unguarded Health Connect repository, eraser and work scheduler, so this check is the barrier between the tour
 * and every action that writes. It is an ALLOW-list: a node is clicked only when it matches the [Allowed] kind it is
 * clicked as (tabs, a fixed set of labels, the Nights filter, the settings groups' expand buttons, a night row, the
 * demo-mode switch), with every text looked up by resource name in the installed app's language. On top of that the
 * deny-list refuses every node that shows the label of an action that writes, deletes, schedules work, changes a
 * setting, exports, shares or leaves the app ([FORBIDDEN]), every text field, slider, dropdown, checkbox, switch and
 * radio button except where the kind expects one, and every selectable node outside the tabs, the Nights filter and
 * the night rows. The click runs the OnClick action of exactly the node that was checked, on the UI thread, and only
 * while that node is still attached; the matcher is never resolved again. A refused click throws [SafetyViolation]
 * (an Error: it fails the test and is never swallowed) instead of clicking.
 */
object SafeClicks {
    /** The tabs the tour opens. */
    val TABS: Set<String> = setOf("tab_home", "tab_nights", "tab_compare", "tab_settings")

    /** Labels of the buttons and rows the tour presses (none of them writes; see E2eResourceNamesTest). */
    val LABELS: Set<String> =
        setOf(
            "home_action_privacy",
            "action_close",
            "night_epoch_inspect",
            "cd_next_epoch",
            "night_action_compare_rules",
            "action_back",
            "sources_title",
            "log_title",
        )

    /** The options of the Nights filter (they only change what the list shows; nothing is persisted). */
    val FILTER_OPTIONS: Set<String> = setOf("nights_filter_all", "nights_filter_nights", "nights_filter_naps")

    /** Names of the strings of every action the tour must never trigger (refused even where a kind would match). */
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

    /** Roles that are refused unless the kind of click expects them. */
    private val CHOICE_ROLES: Set<Role> = setOf(Role.Checkbox, Role.Switch, Role.RadioButton, Role.DropdownList, Role.ValuePicker)

    /** Clicks refused so far. */
    val refused: AtomicInteger = AtomicInteger(0)

    private val forbiddenLabels: List<Pair<String, Boolean>> by lazy {
        FORBIDDEN.map { name -> AppText.prefix(name) to AppText.isFormat(name) }.filter { it.first.isNotBlank() }
    }

    private val sectionTitles: Set<String> by lazy { SettingsCatalog.sections.mapTo(HashSet()) { it.title.resolve(E2e.app.resources) } }

    /**
     * Clicks the node [target] resolves to, as [allowed]: the node is fetched ONCE, checked, and its own OnClick action
     * is run on the UI thread while it is still attached.
     */
    fun click(
        compose: ComposeTestRule,
        target: SemanticsNodeInteraction,
        allowed: Allowed,
    ) {
        val node = target.fetchSemanticsNode("the node to click as $allowed")
        check(node, allowed)
        val action = node.config.getOrNull(SemanticsActions.OnClick)?.action
        val clicked =
            compose.runOnUiThread {
                // The checked node itself: when a recomposition removed it in between, nothing is clicked.
                if (action != null && node.layoutInfo.isAttached) action() else null
            }
        if (clicked == null) refuse(texts(node.config), allowed, "it left the screen between the check and the click")
    }

    /** Throws [SafetyViolation] unless clicking [node] as [allowed] is permitted. */
    fun check(
        node: SemanticsNode,
        allowed: Allowed,
    ) {
        val config = node.config
        val texts = texts(config)
        val reason = forbidden(texts, config, allowed) ?: notAllowed(node, texts, config, allowed)
        if (reason != null) refuse(texts, allowed, reason)
    }

    private fun refuse(
        texts: List<String>,
        allowed: Allowed,
        reason: String,
    ): Nothing {
        refused.incrementAndGet()
        throw SafetyViolation("refusing to click $texts as $allowed: $reason")
    }

    private fun texts(config: SemanticsConfiguration): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    /** The deny-list: applies to every click, whatever kind it is. */
    private fun forbidden(
        texts: List<String>,
        config: SemanticsConfiguration,
        allowed: Allowed,
    ): String? {
        val role = config.getOrNull(SemanticsProperties.Role)
        val hit =
            texts.firstNotNullOfOrNull { text ->
                forbiddenLabels.firstOrNull { (label, format) -> if (format) text.startsWith(label) else text == label }?.first
            }
        if (hit != null) return "it is the forbidden action \"$hit\""
        val choiceExpected =
            (allowed is Allowed.FilterOption && role == Role.RadioButton) || (allowed is Allowed.DemoSwitch && role == Role.Switch)
        val mayBeSelected = allowed is Allowed.Tab || allowed is Allowed.FilterOption || allowed is Allowed.NightRow
        return firstBroken(
            "it has no click action" to { SemanticsActions.OnClick !in config },
            "it is a text field" to { SemanticsActions.SetText in config || SemanticsProperties.EditableText in config },
            "it is a slider" to { SemanticsActions.SetProgress in config },
            "it is a switch or checkbox" to { SemanticsProperties.ToggleableState in config && allowed !is Allowed.DemoSwitch },
            "its role is $role" to { role in CHOICE_ROLES && !choiceExpected },
            "it is a tab" to { role == Role.Tab && allowed !is Allowed.Tab },
            "it is a selectable choice" to { SemanticsProperties.Selected in config && !mayBeSelected },
        )
    }

    /** The allow-list: the node must be what [allowed] says. */
    private fun notAllowed(
        node: SemanticsNode,
        texts: List<String>,
        config: SemanticsConfiguration,
        allowed: Allowed,
    ): String? {
        val role = config.getOrNull(SemanticsProperties.Role)
        return when (allowed) {
            is Allowed.Tab -> {
                firstBroken(
                    "${allowed.name} is not one of the tour's tabs" to { allowed.name !in TABS },
                    "it is not a tab" to { role != Role.Tab },
                    "it is not the tab ${allowed.name}" to { AppText.string(allowed.name) !in texts },
                )
            }

            is Allowed.Label -> {
                firstBroken(
                    "${allowed.name} is not one of the tour's labels" to { allowed.name !in LABELS },
                    "it is not labelled ${allowed.name}" to { AppText.string(allowed.name) !in texts },
                )
            }

            is Allowed.FilterOption -> {
                firstBroken(
                    "${allowed.name} is not an option of the Nights filter" to { allowed.name !in FILTER_OPTIONS },
                    "it is not the option ${allowed.name}" to { AppText.string(allowed.name) !in texts },
                    "it is not inside the Nights filter" to {
                        !hasAncestor(node) { AppText.string("nights_filter_label") in it.contentDescriptions() }
                    },
                )
            }

            is Allowed.SectionToggle -> {
                val labels = setOf(AppText.string("cd_expand_section", allowed.title), AppText.string("cd_collapse_section", allowed.title))
                firstBroken(
                    "\"${allowed.title}\" is not the title of a settings group" to { allowed.title !in sectionTitles },
                    "it is not the expand or collapse button of \"${allowed.title}\"" to { texts.none { it in labels } },
                )
            }

            is Allowed.NightRow -> {
                firstBroken(
                    "it does not show the date ${allowed.date}" to { allowed.date !in texts },
                    "its role is $role" to { role != null && role != Role.Button },
                    "it is not in a list" to { !hasAncestor(node) { SemanticsActions.ScrollToIndex in it.config } },
                )
            }

            Allowed.DemoSwitch -> {
                firstBroken(
                    "it is not a switch" to { SemanticsProperties.ToggleableState !in config },
                    "it is not the demo-mode switch" to { texts.none { it.contains(AppText.string("demo_mode_title")) } },
                )
            }
        }
    }

    /** The reason of the first rule that is broken, or null. */
    private fun firstBroken(vararg rules: Pair<String, () -> Boolean>): String? = rules.firstOrNull { (_, broken) -> broken() }?.first

    private fun SemanticsNode.contentDescriptions(): List<String> = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun hasAncestor(
        node: SemanticsNode,
        predicate: (SemanticsNode) -> Boolean,
    ): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (predicate(parent)) return true
            parent = parent.parent
        }
        return false
    }
}
