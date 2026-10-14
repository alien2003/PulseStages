// detekt treats only src/test as tests; this is the test source set of the debug variant (the demo code is debug-only).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.github.alien2003.pulsestages.screenshots

import android.content.Intent
import android.content.res.Resources
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.toSize
import io.github.alien2003.pulsestages.ui.TopLevelDestination
import io.github.alien2003.pulsestages.ui.theme.PulseStagesTheme
import org.junit.Assert.assertEquals
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.util.Locale
import java.util.TimeZone

/**
 * The language, font scale and window of a screen test. [apply] it before the activity is created (Robolectric
 * qualifiers and font scale, the default locale that java.time formatting uses, UTC) and [restore] it afterwards.
 *
 * @property language "en" or "ru".
 * @property fontScale the system font scale (2.0 is the largest Android offers).
 * @property wide a window of the medium width class (the inner screen of a foldable): two-pane and two-column layouts.
 */
class ScreenConfig(
    val language: String,
    val fontScale: Float = 1f,
    val wide: Boolean = false,
) {
    /** The locale of [language]. */
    val locale: Locale = if (language == "ru") Locale.forLanguageTag("ru-RU") else Locale.US

    private var previousLocale: Locale? = null
    private var previousZone: TimeZone? = null

    /** Configures Robolectric and the JVM defaults. */
    fun apply() {
        previousLocale = Locale.getDefault()
        previousZone = TimeZone.getDefault()
        Locale.setDefault(locale)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val window = if (wide) "-w800dp-h700dp-land" else ""
        RuntimeEnvironment.setQualifiers("+${locale.language}-r${locale.country}$window")
        RuntimeEnvironment.setFontScale(fontScale)
    }

    /** Fails unless [resources] (of the shown activity) have this language, font scale and window width. */
    fun assertAppliedTo(resources: Resources) {
        val configuration = resources.configuration
        assertEquals("language", language, configuration.locales[0].language)
        assertEquals("font scale", fontScale, configuration.fontScale, 0.001f)
        assertEquals("wide window", wide, configuration.screenWidthDp >= WIDE_DP)
    }

    /** Restores the JVM defaults. */
    fun restore() {
        previousLocale?.let(Locale::setDefault)
        previousZone?.let(TimeZone::setDefault)
    }

    override fun toString(): String =
        listOfNotNull(
            language,
            "font x$fontScale".takeIf {
                fontScale != 1f
            },
            "wide".takeIf { wide },
        ).joinToString(", ")

    /** The configurations of the render tests. */
    companion object {
        private const val WIDE_DP = 600

        /** English and Russian (the two languages of the app). */
        val LANGUAGES: List<ScreenConfig> = listOf(ScreenConfig("en"), ScreenConfig("ru"))

        /** [LANGUAGES], plus the largest font scale and the wide window. */
        val ALL: List<ScreenConfig> =
            LANGUAGES +
                listOf(
                    ScreenConfig("en", fontScale = 2f),
                    ScreenConfig("ru", fontScale = 2f),
                    ScreenConfig("ru", wide = true),
                    ScreenConfig("en", fontScale = 2f, wide = true),
                )
    }
}

/**
 * Hosts the screens of the Robolectric screen tests (ScreenshotsTest and the render smoke tests): a ComponentActivity
 * showing the given content in [PulseStagesTheme] (light, no dynamic colour), optionally inside the app shell of
 * PulseStagesNavHost. An exception thrown while a screen is composed, measured or drawn fails the test.
 */
class ScreenHost(
    private val compose: ComposeTestRule,
) {
    private var controller: ActivityController<out ComponentActivity>? = null

    /** The resources of the shown activity (its locale and font scale). */
    val resources: Resources get() = checkNotNull(controller) { "nothing is shown" }.get().resources

    /** Shows [content] in a new activity, inside the app shell with [tab] selected (no shell when [tab] is null). */
    fun show(
        tab: TopLevelDestination? = null,
        content: @Composable () -> Unit,
    ) {
        close()
        val created = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = created
        created.get().setContent {
            PulseStagesTheme(darkTheme = false, dynamicColor = false) {
                if (tab == null) content() else AppShell(tab, content)
            }
        }
        idle()
    }

    /**
     * Shows each of [variants] in turn in one activity ([content] draws the current one), checks the texts of each and
     * runs [check] on it; for a variant with [lazyItems] > 0 the first lazy list on screen is scrolled through that
     * many items, so that every item is composed.
     */
    fun <S> showEach(
        tab: TopLevelDestination?,
        variants: List<Pair<String, S>>,
        lazyItems: (S) -> Int = { 0 },
        check: (name: String, variant: S) -> Unit = { _, _ -> },
        content: @Composable (S) -> Unit,
    ) {
        val current = mutableStateOf(variants.first().second)
        show(tab) { content(current.value) }
        variants.forEach { (name, variant) ->
            compose.runOnIdle { current.value = variant }
            idle()
            assertTexts(name)
            check(name, variant)
            scrollLazyList(name, lazyItems(variant))
        }
    }

    /** Scrolls the first lazy list on screen to each of its first [count] items and back, checking the texts. */
    fun scrollLazyList(
        where: String,
        count: Int,
    ) {
        if (count <= 0) return
        val list = compose.onAllNodes(hasScrollToIndexAction()).onFirst()
        repeat(count) { index ->
            list.performScrollToIndex(index)
            idle()
            assertTexts("$where, item $index")
        }
        list.performScrollToIndex(0)
        idle()
    }

    /** Starts [activity] (which sets its own content) with [intent]. */
    fun <A : ComponentActivity> launch(
        activity: Class<A>,
        intent: Intent? = null,
    ): A {
        close()
        val created = Robolectric.buildActivity(activity, intent).setup()
        controller = created
        idle()
        return created.get()
    }

    /** Runs the main looper and Compose until [ready] holds (ViewModels load on background dispatchers). */
    fun waitUntil(ready: () -> Boolean) {
        compose.waitUntil(timeoutMillis = TIMEOUT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            ready()
        }
        compose.waitForIdle()
    }

    /** Runs the main looper and Compose until both are idle. */
    fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    /**
     * No text, editable text, content or state description on screen (popups and dialogs included) still has a format
     * placeholder such as `%1$s`: a text resource shown without its arguments. And in every row of segmented buttons the
     * segments have one height and no label wraps, is cut or leaves its segment: a label that does not fit (a
     * translation, the largest font) must make the row fall back to radio buttons.
     */
    fun assertTexts(where: String) {
        val texts = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().flatMap { it.allTexts() }
        assertEquals(
            "$where: texts with an unfilled format placeholder",
            emptyList<String>(),
            texts.filter { PLACEHOLDER.containsMatchIn(it) },
        )
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(
            "$where: segmented-button labels that do not fit their segment",
            emptyList<String>(),
            roots.flatMap { it.segmentedRows() }.flatMap { it.segmentProblems() },
        )
    }

    /**
     * Rows of segmented buttons below this node: nodes with two or more radio-button children side by side (a vertical
     * list of radio buttons is no such row: its labels may wrap).
     */
    private fun SemanticsNode.segmentedRows(): List<SemanticsNode> {
        val radios = children.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
        val sideBySide = radios.size >= 2 && radios.map { it.positionInRoot.y }.distinct().size == 1
        return listOfNotNull(takeIf { sideBySide }) + children.flatMap { it.segmentedRows() }
    }

    /** Segments of this row whose height differs from the others', and labels that wrap, are cut or leave their segment. */
    private fun SemanticsNode.segmentProblems(): List<String> {
        val segments = children.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
        val heights = segments.map { it.size.height }
        val uneven = heights.max() - heights.min() > 1
        return listOfNotNull("segments of different heights $heights".takeIf { uneven }) + segments.flatMap { it.segmentLabelProblems() }
    }

    /** Labels of this segmented button that wrap, are cut or leave the button's bounds. */
    private fun SemanticsNode.segmentLabelProblems(): List<String> {
        // Unclipped geometry: a row partly scrolled out of view is still checked as it is laid out.
        val button = unclippedBounds()
        return descendantsWithText().mapNotNull { node ->
            val label = node.config[SemanticsProperties.Text].joinToString { it.text }
            val layouts = mutableListOf<TextLayoutResult>()
            node.config
                .getOrNull(SemanticsActions.GetTextLayoutResult)
                ?.action
                ?.invoke(layouts)
            val layout = layouts.firstOrNull()
            val bounds = node.unclippedBounds()
            when {
                layout != null && layout.lineCount > 1 -> {
                    "\"$label\" wraps to ${layout.lineCount} lines"
                }

                // Cut to one line: the label's single-line width exceeds the width it was given.
                layout != null && layout.multiParagraph.intrinsics.maxIntrinsicWidth > layout.size.width + EDGE_TOLERANCE_PX -> {
                    "\"$label\" is cut"
                }

                bounds.left < button.left - EDGE_TOLERANCE_PX || bounds.right > button.right + EDGE_TOLERANCE_PX -> {
                    "\"$label\" ($bounds) leaves its segment ($button)"
                }

                else -> {
                    null
                }
            }
        }
    }

    private fun SemanticsNode.unclippedBounds(): Rect = Rect(positionInRoot, size.toSize())

    private fun SemanticsNode.descendantsWithText(): List<SemanticsNode> =
        children.flatMap { child -> listOfNotNull(child.takeIf { SemanticsProperties.Text in it.config }) + child.descendantsWithText() }

    private fun SemanticsNode.allTexts(): List<String> {
        val own =
            config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                listOfNotNull(config.getOrNull(SemanticsProperties.EditableText)?.text) +
                config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                listOfNotNull(config.getOrNull(SemanticsProperties.StateDescription))
        return own + children.flatMap { it.allTexts() }
    }

    /** Destroys the current activity. */
    fun close() {
        controller?.pause()?.stop()?.destroy()
        controller = null
    }

    private companion object {
        const val TIMEOUT_MS = 120_000L

        /** Rounding of the layout: a label may end this far outside its segment. */
        const val EDGE_TOLERANCE_PX = 1f

        /** A java.util.Formatter conversion (`%s`, `%1$d`, `%.2f`); "50 %" is not one. */
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[a-zA-Z]""")
    }
}

/** The app shell of PulseStagesNavHost: the screen above the four-tab navigation bar. */
@Composable
fun AppShell(
    selected: TopLevelDestination,
    content: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { tab ->
                    val label = stringResource(tab.label)
                    NavigationBarItem(
                        selected = tab == selected,
                        onClick = {},
                        icon = { Icon(painter = painterResource(tab.icon), contentDescription = label) },
                        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
            content()
        }
    }
}
