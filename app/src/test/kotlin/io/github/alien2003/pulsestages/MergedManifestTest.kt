package io.github.alien2003.pulsestages

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks the MERGED manifests (debug and release) produced by AGP. The paths are passed in by app/build.gradle.kts
 * from the Variant API artifact SingleArtifact.MERGED_MANIFEST, so the test task depends on manifest merging.
 */
class MergedManifestTest {
    private val mergedManifests: Map<String, File> =
        System
            .getProperties()
            .stringPropertyNames()
            .filter { it.startsWith(PROPERTY_PREFIX) }
            .associate { it.removePrefix(PROPERTY_PREFIX) to File(checkNotNull(System.getProperty(it))) }

    @Test
    fun `merged manifests are provided for debug and release`() {
        assertTrue(mergedManifests.keys.containsAll(REQUIRED_VARIANTS), "expected $REQUIRED_VARIANTS, got ${mergedManifests.keys}")
        mergedManifests.forEach { (variant, file) -> assertTrue(file.isFile, "$variant merged manifest missing: $file") }
    }

    @Test
    fun `merged manifests do not request the INTERNET permission`() {
        assertTrue(mergedManifests.isNotEmpty(), "no merged manifests passed to the test")
        mergedManifests.forEach { (variant, file) ->
            val requested = parse(file).requestedPermissions()
            assertFalse(INTERNET in requested, "$variant merged manifest requests $INTERNET: $requested")
        }
    }

    @Test
    fun `default WorkManager initializer is removed so HiltWorkerFactory is used`() {
        mergedManifests.forEach { (variant, file) ->
            val metaDataNames =
                parse(file)
                    .elements("meta-data")
                    .map { it.getAttributeNS(ANDROID_NS, "name") }
            assertFalse(WORK_MANAGER_INITIALIZER in metaDataNames, "$variant merged manifest still contains $WORK_MANAGER_INITIALIZER")
        }
    }

    private fun parse(file: File): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)

    private fun Document.elements(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Document.requestedPermissions(): Set<String> =
        (elements("uses-permission") + elements("uses-permission-sdk-23"))
            .map { it.getAttributeNS(ANDROID_NS, "name") }
            .toSet()

    private companion object {
        const val PROPERTY_PREFIX = "pulsestages.mergedManifest."
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val INTERNET = "android.permission.INTERNET"
        const val WORK_MANAGER_INITIALIZER = "androidx.work.WorkManagerInitializer"
        val REQUIRED_VARIANTS = setOf("debug", "release")
    }
}
