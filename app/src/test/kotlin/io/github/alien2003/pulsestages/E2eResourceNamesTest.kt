package io.github.alien2003.pulsestages

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The on-device UI tour (src/androidTest, `e2e/ui/`) looks up the installed app's texts by resource NAME at run time,
 * including the labels of the actions it must never press. This checks at build time that every such name exists as a
 * string of the debug build (main + debug resources), so a renamed string cannot silently weaken the tour or its
 * refusal list: the names are the snake_case string literals of the tour's sources. It also checks that the refusal
 * list covers every writing action and that the allow-list of what the tour may press holds none of them.
 */
class E2eResourceNamesTest {
    private val sources: List<File> =
        File("src/androidTest/kotlin/io/github/alien2003/pulsestages/e2e/ui")
            .listFiles { file -> file.extension == "kt" }
            .orEmpty()
            .sortedBy { it.name }

    private val strings: Set<String> =
        listOf("src/main/res/values", "src/debug/res/values")
            .flatMap { dir ->
                File(dir).listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { file ->
                    val children =
                        DocumentBuilderFactory
                            .newInstance()
                            .newDocumentBuilder()
                            .parse(file)
                            .documentElement.childNodes
                    (0 until children.length)
                        .map { children.item(it) }
                        .filterIsInstance<Element>()
                        .filter { it.tagName == "string" }
                        .map { it.getAttribute("name") }
                }
            }.toSet()

    @Test
    fun everyResourceNameOfTheUiTourExists() {
        assertTrue(sources.size >= 3, "the UI tour sources were not found: $sources")
        assertTrue(strings.size > 100, "the app's strings were not found")
        val names = sources.flatMap { file -> NAME.findAll(file.readText()).map { file.name to it.groupValues[1] } }.distinct()
        assertTrue(names.size > 40, "only ${names.size} resource names found in the UI tour")
        val missing = names.filter { (_, name) -> name !in strings }
        assertTrue(missing.isEmpty(), "string resources used by the UI tour do not exist: $missing")
    }

    @Test
    fun theRefusalListCoversEveryWritingAction() {
        val clicks = File("src/androidTest/kotlin/io/github/alien2003/pulsestages/e2e/ui/SafeClicks.kt").readText()
        val writing =
            strings.filter { name ->
                WRITING_ACTIONS.any { it.matches(name) }
            }
        val unlisted = writing.filter { "\"$it\"" !in clicks }
        assertTrue(
            unlisted.isEmpty(),
            "actions that write, delete, schedule or export but are missing from SafeClicks.FORBIDDEN: $unlisted",
        )
    }

    @Test
    fun theAllowListHoldsNoWritingAction() {
        val clicks = File("src/androidTest/kotlin/io/github/alien2003/pulsestages/e2e/ui/SafeClicks.kt").readText()
        val allowed = ALLOW_LISTS.flatMap { list -> namesIn(clicks, "val $list: Set<String> =\\s*setOf\\(([^)]*)\\)") }
        assertTrue(allowed.size >= 15, "the allow-list of SafeClicks was not found: $allowed")
        val forbidden = namesIn(clicks, "val FORBIDDEN: List<String> =\\s*listOf\\(([^)]*)\\)")
        assertTrue(forbidden.size > 30, "the deny-list of SafeClicks was not found: $forbidden")
        val writing = allowed.filter { name -> name in forbidden || WRITING_ACTIONS.any { it.matches(name) } }
        assertTrue(writing.isEmpty(), "SafeClicks allows clicks on actions that write, delete, schedule or export: $writing")
    }

    /** The resource names in the first group of [pattern] in [source]. */
    private fun namesIn(
        source: String,
        pattern: String,
    ): List<String> {
        val block =
            Regex(pattern)
                .find(source)
                ?.groupValues
                ?.get(1)
                .orEmpty()
        return NAME.findAll(block).map { it.groupValues[1] }.toList()
    }

    private companion object {
        /** The allow-lists of SafeClicks: tabs, labels and the Nights filter's options. */
        val ALLOW_LISTS = listOf("TABS", "LABELS", "FILTER_OPTIONS")

        /** A string literal that is a resource name: snake_case with at least one underscore. */
        val NAME = Regex("\"([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\"")

        /** Names of the strings of actions the tour must never press. */
        val WRITING_ACTIONS =
            listOf(
                Regex("home_action_(estimate|backfill.*|recompute.*|delete.*|grant.*|open_hc.*|install_hc)"),
                Regex("home_action_(allow_notifications|notification_settings)"),
                Regex("night_action_(recompute|delete|export)"),
                Regex("compare_action_(calibrate|reset)"),
                Regex("settings_action_(export|import|reset.*)"),
                Regex("log_action_share"),
                Regex("sources_manual_add"),
            )
    }
}
