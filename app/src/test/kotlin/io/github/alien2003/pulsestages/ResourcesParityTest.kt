package io.github.alien2003.pulsestages

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every user-visible string exists in English (values/) and Russian (values-ru/), in every source set of every module
 * that has Android resources (:app, :work, :data-hc, ...), with the same format placeholders, and the plurals have
 * the forms each language needs (English one and other; Russian one, few, many and other).
 */
class ResourcesParityTest {
    /** One `src/<sourceSet>/res` directory of a module. */
    private data class ResDir(
        val label: String,
        val dir: File,
    )

    /** The res directories of every module of the build (the test runs in the :app module directory). */
    private val resDirs: List<ResDir> =
        File("..")
            .canonicalFile
            .listFiles { file -> file.isDirectory && File(file, "build.gradle.kts").isFile }
            .orEmpty()
            .flatMap { module ->
                File(module, "src")
                    .listFiles { file -> File(file, "res").isDirectory }
                    .orEmpty()
                    .map { sourceSet -> ResDir("${module.name}/${sourceSet.name}", File(sourceSet, "res")) }
            }.filter { File(it.dir, "values").isDirectory || File(it.dir, "values-ru").isDirectory }
            .sortedBy { it.label }

    private fun resources(
        res: ResDir,
        qualifier: String,
    ): List<Element> {
        val files = File(res.dir, "values$qualifier").listFiles { file -> file.extension == "xml" }.orEmpty()
        return files.flatMap { file ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val children = document.documentElement.childNodes
            (0 until children.length).map { children.item(it) }.filterIsInstance<Element>()
        }
    }

    private fun translatable(
        elements: List<Element>,
        tag: String,
    ): Map<String, Element> =
        elements
            .filter { it.tagName == tag && it.getAttribute("translatable") != "false" }
            .associateBy { it.getAttribute("name") }

    /** The format placeholders of [text] (`%s`, `%1$d`, `%.1f`, ...), sorted; `%%` is not one. */
    private fun placeholders(text: String): List<String> =
        PLACEHOLDER
            .findAll(text.replace("%%", ""))
            .map { it.value }
            .sorted()
            .toList()

    private fun quantities(plural: Element): Map<String, String> {
        val items = plural.getElementsByTagName("item")
        return (0 until items.length).map { items.item(it) as Element }.associate { it.getAttribute("quantity") to it.textContent }
    }

    @Test
    fun `every module with resources is checked`() {
        val labels = resDirs.map { it.label }
        listOf("app/main", "app/debug", "work/main", "data-hc/main").forEach { assertTrue(it in labels, "$it in $labels") }
    }

    @Test
    fun `every string and plural exists in English and Russian`() {
        resDirs.forEach { res ->
            val english = resources(res, "")
            val russian = resources(res, "-ru")
            assertTrue(english.isNotEmpty(), "${res.label} has English strings")
            listOf("string", "plurals", "string-array").forEach { tag ->
                assertEquals(translatable(english, tag).keys, translatable(russian, tag).keys, "${res.label} <$tag> en vs ru")
            }
        }
    }

    @Test
    fun `English and Russian strings have the same format placeholders`() {
        resDirs.forEach { res ->
            val russian = resources(res, "-ru")
            translatable(resources(res, ""), "string").forEach { (name, english) ->
                val translation = translatable(russian, "string")[name] ?: return@forEach
                assertEquals(placeholders(english.textContent), placeholders(translation.textContent), "${res.label} string $name")
            }
            val russianPlurals = translatable(russian, "plurals")
            translatable(resources(res, ""), "plurals").forEach { (name, english) ->
                val englishForms = quantities(english).values.map(::placeholders).toSet()
                val russianForms = russianPlurals[name]?.let { quantities(it).values.map(::placeholders).toSet() } ?: return@forEach
                assertEquals(englishForms, russianForms, "${res.label} plurals $name")
            }
        }
    }

    @Test
    fun `plurals have the forms of their language`() {
        resDirs.forEach { res ->
            translatable(resources(res, ""), "plurals").forEach { (name, plural) ->
                assertTrue(quantities(plural).keys.containsAll(setOf("one", "other")), "${res.label} en $name")
            }
            translatable(resources(res, "-ru"), "plurals").forEach { (name, plural) ->
                assertEquals(setOf("one", "few", "many", "other"), quantities(plural).keys, "${res.label} ru $name")
            }
        }
    }

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[a-zA-Z]""")
    }
}
