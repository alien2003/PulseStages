package io.github.alien2003.pulsestages.e2e.ui

import android.os.SystemClock
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import io.github.alien2003.pulsestages.e2e.E2e
import io.github.alien2003.pulsestages.e2e.safety.OwnRecord
import io.github.alien2003.pulsestages.ui.common.formatDate
import io.github.alien2003.pulsestages.ui.common.localDateTime
import io.github.alien2003.pulsestages.ui.common.resolve
import io.github.alien2003.pulsestages.ui.settings.SettingsCatalog
import java.time.ZoneId

/**
 * The read-only tour of every screen of the real app ([io.github.alien2003.pulsestages.e2e.ReadOnlyUiTourE2eTest]).
 * Every click goes through [SafeClicks]; texts come from the installed app ([AppText]).
 */
class UiTour(
    private val compose: ComposeTestRule,
    private val appRecords: List<OwnRecord>,
) {
    /** What each step found. */
    val log: MutableList<String> = mutableListOf()

    private val resources = E2e.app.resources

    /** Home: Health Connect available, every permission granted, the counters. */
    fun home() {
        waitFor(hasText(AppText.string("hc_status_available")), "Health Connect status")
        val required = HealthPermissions.REQUIRED.size
        waitFor(hasText(AppText.string("home_permissions_required", required, required)), "all required permissions granted")
        val granted =
            compose
                .onAllNodes(
                    hasText(AppText.string("home_permission_granted")),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .size
        check(granted >= GRANTED_LINES) { "only $granted permission lines say granted (optional, history and background expected)" }
        Screenshots.take("home")
        compose.onNode(hasText(AppText.string("home_counters_title"))).performScrollTo()
        Screenshots.take("home-counters")
        log += "home: Health Connect available, $required/$required required permissions, $granted lines granted"
    }

    /** The privacy page (PermissionsRationaleActivity) from Home, closed again. */
    fun rationale() {
        val privacy = compose.onNode(hasText(AppText.string("home_action_privacy")) and hasClickAction())
        privacy.performScrollTo()
        SafeClicks.click(privacy)
        waitFor(hasText(AppText.string("rationale_intro")), "the privacy page")
        Screenshots.take("rationale")
        SafeClicks.click(compose.onNode(hasContentDescription(AppText.string("action_close")) and hasClickAction()))
        waitGone(hasText(AppText.string("rationale_intro")), "the privacy page closing")
        log += "rationale: opened and closed"
    }

    /** Nights: the app's nights are listed; the filter shows naps, nights and all. */
    fun nights() {
        tab("tab_nights")
        waitForAny(
            listOf(hasContentDescription(AppText.string("nights_filter_label")), hasText(AppText.string("nights_empty_title"))),
            "the nights",
        )
        Screenshots.take("nights")
        appRecords.forEach { record -> scrollListTo(hasText(dateOf(record))) }
        log += "nights: ${appRecords.size} night(s) of the app found in the list"
        if (appRecords.isEmpty()) return
        listOf("nights_filter_naps", "nights_filter_nights", "nights_filter_all").forEach { name ->
            val option =
                hasText(AppText.string(name)) and hasClickAction() and
                    hasAnyAncestor(hasContentDescription(AppText.string("nights_filter_label")))
            SafeClicks.click(compose.onNode(option))
            compose.waitForIdle()
            Screenshots.take("nights-${name.substringAfterLast('_')}")
        }
        log += "nights: filters naps, nights, all"
    }

    /** One night: its detail, the epoch panel, the comparison with RULES. */
    fun nightDetail() {
        val night = appRecords.maxByOrNull { it.startMs } ?: return
        val date = dateOf(night)
        val row = scrollListTo(hasText(date) and hasClickAction())
        SafeClicks.click(row)
        // Two sessions may share a date (a night and a nap): the detail shows one of them.
        val sameDate = appRecords.filter { dateOf(it) == date }.mapNotNull { it.clientRecordId }
        waitForAny(sameDate.map { hasText(it) }, "the night detail")
        Screenshots.take("night-detail")
        val inspect = hasText(AppText.string("night_epoch_inspect")) and hasClickAction()
        waitFor(inspect, "the re-estimate of the night", INSPECTION_TIMEOUT_MS)
        compose.onNode(inspect).performScrollTo()
        SafeClicks.click(compose.onNode(inspect))
        waitFor(hasText(AppText.string("night_epoch_posteriors")), "the epoch panel")
        SafeClicks.click(compose.onNode(hasContentDescription(AppText.string("cd_next_epoch")) and hasClickAction()))
        compose.waitForIdle()
        Screenshots.take("night-epoch")
        SafeClicks.click(compose.onNode(hasText(AppText.string("action_close")) and hasClickAction()))
        waitGone(hasText(AppText.string("night_epoch_posteriors")), "the epoch panel closing")
        val compare = compose.onNode(hasText(AppText.string("night_action_compare_rules")) and hasClickAction())
        compare.performScrollTo()
        SafeClicks.click(compare)
        waitFor(hasText(AppText.string("night_compare_title")), "the comparison with RULES", INSPECTION_TIMEOUT_MS)
        compose.onNode(hasText(AppText.string("night_compare_title"))).performScrollTo()
        Screenshots.take("night-compare-rules")
        val back = compose.onAllNodes(hasContentDescription(AppText.string("action_back")) and hasClickAction()).fetchSemanticsNodes()
        if (back.isNotEmpty()) {
            SafeClicks.click(
                compose.onAllNodes(hasContentDescription(AppText.string("action_back")) and hasClickAction()).onFirst(),
            )
        }
        log += "night detail: epoch panel and comparison with RULES shown (${if (back.isEmpty()) "two panes" else "own screen"})"
    }

    /** Compare: the reference nights are searched and scored (nothing is calibrated). */
    fun compare() {
        tab("tab_compare")
        val ready =
            listOf(
                hasText(AppText.string("compare_empty_title")),
                hasText(AppText.string("compare_kappa")),
                hasText(AppText.string("compare_error_generic")),
                hasContentDescription(AppText.string("cd_error")),
            )
        waitForAny(ready, "the Compare tab", COMPARE_TIMEOUT_MS)
        Screenshots.take("compare")
        log += "compare: rendered"
    }

    /** Settings: every group expanded and collapsed again, Sources and Log opened, the About card. */
    fun settings() {
        tab("tab_settings")
        waitFor(hasText(AppText.string("settings_actions_title")), "the settings")
        Screenshots.take("settings")
        val grid = compose.onNode(hasScrollToIndexAction())
        SettingsCatalog.sections.forEachIndexed { index, section ->
            grid.performScrollToIndex(index + 1)
            val title = section.title.resolve(resources)
            val expand = hasContentDescription(AppText.string("cd_expand_section", title)) and hasClickAction()
            val collapse = hasContentDescription(AppText.string("cd_collapse_section", title)) and hasClickAction()
            val expandedBefore = compose.onAllNodes(collapse).fetchSemanticsNodes().isNotEmpty()
            if (!expandedBefore) {
                SafeClicks.click(compose.onNode(expand))
                waitFor(collapse, "group ${section.id} expanded")
            }
            grid.performScrollToIndex(index + 1)
            Screenshots.take("settings-${section.id}")
            if (!expandedBefore) {
                SafeClicks.click(compose.onNode(collapse))
                waitFor(expand, "group ${section.id} collapsed")
            }
        }
        log += "settings: ${SettingsCatalog.sections.size} groups expanded and restored"
        openFromSettings("sources_title", hasText(AppText.string("sources_priority_hint")), "sources")
        openFromSettings("log_title", hasContentDescription(AppText.string("log_action_share")), "log")
        grid.performScrollToIndex(SettingsCatalog.sections.size + 2)
        waitFor(hasText(AppText.string("about_title")), "the About card")
        Screenshots.take("settings-about")
    }

    /** Demo mode on (Nights shows synthetic nights) and back to [previous]. */
    fun demoMode(previous: DemoFlag) {
        val row = { isToggleable() and hasText(AppText.string("demo_mode_title"), substring = true) }
        val grid = { compose.onNode(hasScrollToIndexAction()) }
        tab("tab_settings")
        grid().performScrollToIndex(SettingsCatalog.sections.size + 1)
        if (previous.enabled) compose.onNode(row()).assertIsOn() else compose.onNode(row()).assertIsOff()
        SafeClicks.click(compose.onNode(row()), allowToggle = true)
        if (previous.enabled) compose.onNode(row()).assertIsOff() else compose.onNode(row()).assertIsOn()
        tab("tab_nights")
        val banner = hasText(AppText.string("demo_banner"))
        if (previous.enabled) waitGone(banner, "the real nights") else waitFor(banner, "the synthetic nights")
        Screenshots.take(if (previous.enabled) "nights-real" else "nights-demo")
        tab("tab_settings")
        grid().performScrollToIndex(SettingsCatalog.sections.size + 1)
        SafeClicks.click(compose.onNode(row()), allowToggle = true)
        if (previous.enabled) compose.onNode(row()).assertIsOn() else compose.onNode(row()).assertIsOff()
        log += "demo mode: switched to ${!previous.enabled} and back to ${previous.enabled}"
    }

    /** Back to Home. */
    fun backHome() {
        tab("tab_home")
        waitFor(hasText(AppText.string("hc_status_available")), "Home")
        Screenshots.take("home-end")
    }

    private fun openFromSettings(
        name: String,
        loaded: SemanticsMatcher,
        shot: String,
    ) {
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        SafeClicks.click(compose.onAllNodes(hasText(AppText.string(name)) and hasClickAction()).onFirst())
        waitFor(loaded, "the $shot screen")
        val progress = SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate)
        runCatching { compose.waitUntil(LOAD_TIMEOUT_MS) { count(progress) == 0 } }
        Screenshots.take(shot)
        SafeClicks.click(compose.onNode(hasContentDescription(AppText.string("action_back")) and hasClickAction()))
        waitFor(hasText(AppText.string("settings_actions_title")), "the settings again")
        log += "$shot: loaded"
    }

    private fun tab(name: String) {
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(AppText.string(name))
        SafeClicks.click(compose.onNode(tab))
        compose.waitForIdle()
    }

    /** Scrolls the lazy list that contains [matcher] (in two panes there may be two lists) to it. */
    private fun scrollListTo(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val lists = compose.onAllNodes(hasScrollToNodeAction())
        val count = lists.fetchSemanticsNodes().size
        for (index in 0 until count) {
            val scrolled = runCatching { lists[index].performScrollToNode(matcher) }.isSuccess
            if (scrolled) return compose.onAllNodes(matcher).onFirst()
        }
        compose.onAllNodes(matcher).onFirst().assertExistsOrFail("no list shows $matcher")
        return compose.onAllNodes(matcher).onFirst()
    }

    private fun SemanticsNodeInteraction.assertExistsOrFail(message: String) {
        check(runCatching { fetchSemanticsNode() }.isSuccess) { message }
    }

    private fun waitFor(
        matcher: SemanticsMatcher,
        what: String,
        timeoutMs: Long = LOAD_TIMEOUT_MS,
    ) = waitForAny(listOf(matcher), what, timeoutMs)

    private fun waitForAny(
        matchers: List<SemanticsMatcher>,
        what: String,
        timeoutMs: Long = LOAD_TIMEOUT_MS,
    ) {
        val started = SystemClock.elapsedRealtime()
        try {
            compose.waitUntil(timeoutMs) { matchers.any { count(it) > 0 } }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("$what did not appear within ${timeoutMs / SECOND_MS} s", e)
        }
        E2e.progress("ui: $what (${SystemClock.elapsedRealtime() - started} ms)")
    }

    private fun waitGone(
        matcher: SemanticsMatcher,
        what: String,
    ) {
        try {
            compose.waitUntil(LOAD_TIMEOUT_MS) { count(matcher) == 0 }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("$what did not happen within ${LOAD_TIMEOUT_MS / SECOND_MS} s", e)
        }
    }

    /** Nodes matching [matcher] now; -1 while no compose hierarchy is there (an activity is just starting or closing). */
    private fun count(matcher: SemanticsMatcher): Int =
        runCatching { compose.onAllNodes(matcher).fetchSemanticsNodes().size }.getOrDefault(-1)

    /** The date the Nights list shows for [record] (the list formats the ledger night the same way). */
    private fun dateOf(record: OwnRecord): String {
        val offset = record.startZoneOffsetSec
        val local = if (offset != null) localDateTime(record.startMs, offset) else localDateTime(record.startMs, ZoneId.systemDefault())
        return local.formatDate()
    }

    private companion object {
        /** Lines that say "Granted": three optional channels, history and background. */
        const val GRANTED_LINES = 5
        const val LOAD_TIMEOUT_MS = 60_000L
        const val INSPECTION_TIMEOUT_MS = 120_000L
        const val COMPARE_TIMEOUT_MS = 300_000L
        const val SECOND_MS = 1000L
    }
}
