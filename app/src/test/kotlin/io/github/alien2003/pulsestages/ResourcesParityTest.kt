package io.github.alien2003.pulsestages

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every user-visible string exists in English (values/) and Russian (values-ru/), in every source set of the app,
 * and the Russian plurals have the forms Russian needs (one, few, many, other).
 */
class ResourcesParityTest {
    private val sourceSets = listOf("main", "debug")

    private fun resources(
        sourceSet: String,
        qualifier: String,
    ): List<Element> {
        val dir = File("src/$sourceSet/res/values$qualifier")
        val files = dir.listFiles { file -> file.extension == "xml" }.orEmpty()
        return files.flatMap { file ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val children = document.documentElement.childNodes
            (0 until children.length).map { children.item(it) }.filterIsInstance<Element>()
        }
    }

    private fun names(
        elements: List<Element>,
        tag: String,
    ): Set<String> =
        elements
            .filter { it.tagName == tag && it.getAttribute("translatable") != "false" }
            .map { it.getAttribute("name") }
            .toSet()

    @Test
    fun `every string and plural exists in English and Russian`() {
        sourceSets.forEach { sourceSet ->
            val english = resources(sourceSet, "")
            val russian = resources(sourceSet, "-ru")
            assertTrue(english.isNotEmpty(), "$sourceSet has strings")
            listOf("string", "plurals").forEach { tag ->
                assertEquals(names(english, tag), names(russian, tag), "$sourceSet <$tag> en vs ru")
            }
        }
    }

    @Test
    fun `Russian plurals have the one, few, many and other forms`() {
        sourceSets.forEach { sourceSet ->
            resources(sourceSet, "-ru").filter { it.tagName == "plurals" }.forEach { plural ->
                val items = plural.getElementsByTagName("item")
                val quantities = (0 until items.length).map { (items.item(it) as Element).getAttribute("quantity") }.toSet()
                assertEquals(setOf("one", "few", "many", "other"), quantities, plural.getAttribute("name"))
            }
        }
    }
}
