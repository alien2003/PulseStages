package io.github.alien2003.pulsestages

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import java.util.IllegalFormatException
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every string, plural and string-array item of every module that contains `%` is safe to resolve:
 *
 * - a text that is formatted (the default) is a valid java.util.Formatter pattern with at least one argument, so
 *   `getString(id, args)` cannot throw and a literal percent sign is written `%%`;
 * - a text with `%` but no argument (a unit "%") is marked `formatted="false"`, and no code passes format arguments to
 *   it (it is read with `getString(id)` / `stringResource(id)` / `UiText.of(id)`, which do not format).
 *
 * `Resources.getString(id, *emptyArray())` still runs `String.format`; a lone `%` resolved that way crashed the
 * Settings screen (UnknownFormatConversionException).
 */
class ResourceFormatsTest {
    /** One text of a resource file. */
    private data class ResText(
        val where: String,
        val name: String,
        val text: String,
        val formatted: Boolean,
    )

    private val root: File = File("..").canonicalFile

    private val modules: List<File> =
        root.listFiles { file -> file.isDirectory && File(file, "build.gradle.kts").isFile }.orEmpty().sortedBy { it.name }

    /** Every text in `<module>/src/<sourceSet>/res/values*` of every module. */
    private val texts: List<ResText> =
        modules.flatMap { module ->
            File(module, "src")
                .listFiles()
                .orEmpty()
                .flatMap { sourceSet -> File(sourceSet, "res").listFiles { dir -> dir.name.startsWith("values") }.orEmpty().toList() }
                .flatMap { dir -> dir.listFiles { file -> file.extension == "xml" }.orEmpty().toList() }
                .flatMap { file -> textsOf(file, "${module.name}/${file.relativeTo(module).invariantSeparatorsPath}") }
        }

    private fun textsOf(
        file: File,
        where: String,
    ): List<ResText> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val children = document.documentElement.childNodes
        return (0 until children.length).map { children.item(it) }.filterIsInstance<Element>().flatMap { element ->
            val name = element.getAttribute("name")
            val formatted = element.getAttribute("formatted") != "false"
            when (element.tagName) {
                "string" -> {
                    listOf(ResText(where, name, unescape(element.textContent), formatted))
                }

                "plurals", "string-array" -> {
                    val items = element.getElementsByTagName("item")
                    (0 until items.length).map { i ->
                        val item = items.item(i) as Element
                        val label = item.getAttribute("quantity").ifEmpty { i.toString() }
                        ResText(where, "$name[$label]", unescape(item.textContent), formatted && item.getAttribute("formatted") != "false")
                    }
                }

                else -> {
                    emptyList()
                }
            }
        }
    }

    @Test
    fun `the scan sees the resources of every module`() {
        val wheres = texts.map { it.where.substringBefore("/res/") }.toSet()
        listOf("app/src/main", "app/src/debug", "work/src/main", "data-hc/src/main").forEach { assertTrue(it in wheres, "$it in $wheres") }
        assertTrue(texts.any { it.name == "format_percent" && it.formatted }, "format_percent is scanned")
        assertTrue(texts.any { it.name == "unit_percent" && !it.formatted }, "unit_percent is scanned")
    }

    @Test
    fun `every formatted text with a percent sign is a valid format pattern with an argument`() {
        val problems =
            texts.filter { it.formatted && '%' in it.text }.mapNotNull { text ->
                val specifiers = SPECIFIER.findAll(text.text).toList()
                val arguments = specifiers.count { it.groupValues[CONVERSION] !in NO_ARGUMENT }
                when {
                    arguments == 0 -> {
                        "${text.where} ${text.name}: \"${text.text}\" has '%' but no argument; " +
                            "mark it formatted=\"false\" (and write a single '%')"
                    }

                    else -> {
                        formatProblem(text.text, specifiers)?.let { "${text.where} ${text.name}: \"${text.text}\": $it" }
                    }
                }
            }
        assertEquals(emptyList<String>(), problems)
    }

    /** Formats [pattern] with arguments of the types its [specifiers] need; null when it formats. */
    private fun formatProblem(
        pattern: String,
        specifiers: List<MatchResult>,
    ): String? {
        val arguments = mutableMapOf<Int, Any>()
        var ordinary = 0
        var previous = 0
        specifiers.forEach { specifier ->
            val conversion = specifier.groupValues[CONVERSION]
            if (conversion in NO_ARGUMENT) return@forEach
            val explicit = specifier.groupValues[INDEX].removeSuffix("$").toIntOrNull()
            val index =
                when {
                    explicit != null -> explicit
                    '<' in specifier.groupValues[FLAGS] -> previous
                    else -> ++ordinary
                }
            previous = index
            arguments[index] = sampleArgument(conversion, dateTime = specifier.groupValues[DATE_TIME].isNotEmpty())
        }
        val array = Array<Any>((arguments.keys.maxOrNull() ?: 0)) { arguments[it + 1] ?: "x" }
        return try {
            String.format(Locale.ROOT, pattern, *array)
            null
        } catch (e: IllegalFormatException) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun sampleArgument(
        conversion: String,
        dateTime: Boolean,
    ): Any =
        when {
            dateTime -> 0L
            conversion in "doxX" -> 1
            conversion in "eEfgGaA" -> 1.0
            conversion in "cC" -> 'x'
            conversion in "bB" -> true
            else -> "x"
        }

    @Test
    fun `no text has a percent sign that is not part of a format specifier`() {
        // A '%' followed by something Formatter does not know ("% ", "%-", end of text) in a formatted text.
        val problems =
            texts
                .filter { it.formatted && '%' in it.text }
                .filter { '%' in SPECIFIER.replace(it.text, "") }
                .map { "${it.where} ${it.name}: \"${it.text}\"" }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `texts marked formatted false are never given format arguments`() {
        val unformatted = texts.filter { !it.formatted }.map { it.name.substringBefore('[') }.toSet()
        assertTrue(unformatted.isNotEmpty(), "unit_percent is formatted=\"false\"")
        val sources =
            modules.flatMap { module ->
                File(module, "src").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
            }
        assertTrue(sources.any { it.name == "SettingsCatalog.kt" }, "the app's sources are scanned")
        val problems =
            sources.flatMap { file ->
                val content = file.readText()
                unformatted.flatMap { name ->
                    formatCall(name).findAll(content).map { match ->
                        val line = content.take(match.range.first).count { it == '\n' } + 1
                        "${file.relativeTo(root).invariantSeparatorsPath}:$line formats R.string.$name: ${match.value}"
                    }
                }
            }
        assertEquals(emptyList<String>(), problems)
    }

    /** A call that formats the resource [name]: its id followed by an argument (also across lines). */
    private fun formatCall(name: String): Regex =
        Regex(
            """\b(stringResource|getString|getQuantityString|pluralStringResource|UiText\.of|UiText\.Res|UiText\.plural)""" +
                """\(\s*(\w+\.)*R\.(string|plurals)\.$name\s*,""",
        )

    /** The text as aapt2 compiles it, for what matters here (escaped quotes and specials, `\n`, `\uXXXX`). */
    private fun unescape(raw: String): String {
        val text = raw.trim().removeSurrounding("\"")
        return ESCAPE.replace(text) { match ->
            val escaped = match.groupValues[1]
            when {
                escaped.startsWith("u") -> {
                    escaped
                        .drop(1)
                        .toInt(HEX)
                        .toChar()
                        .toString()
                }

                escaped == "n" -> {
                    "\n"
                }

                escaped == "t" -> {
                    "\t"
                }

                else -> {
                    escaped
                }
            }
        }
    }

    private companion object {
        const val HEX = 16
        const val INDEX = 1
        const val FLAGS = 2
        const val DATE_TIME = 5
        const val CONVERSION = 6

        /** java.util.Formatter's format specifier (its own regular expression). */
        val SPECIFIER = Regex("""%(\d+\$)?([-#+ 0,(<]*)?(\d+)?(\.\d+)?([tT])?([a-zA-Z%])""")

        /** Conversions without an argument. */
        val NO_ARGUMENT = setOf("%", "n")

        val ESCAPE = Regex("""\\(u[0-9a-fA-F]{4}|.)""")
    }
}
