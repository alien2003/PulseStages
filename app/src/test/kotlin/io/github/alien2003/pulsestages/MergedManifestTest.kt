package io.github.alien2003.pulsestages

import io.github.alien2003.pulsestages.data.hc.HealthPermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
        forEachManifest { variant, manifest ->
            val requested = manifest.requestedPermissions()
            assertFalse(INTERNET in requested, "$variant merged manifest requests $INTERNET: $requested")
        }
    }

    @Test
    fun `default WorkManager initializer is removed so HiltWorkerFactory is used`() {
        forEachManifest { variant, manifest ->
            val metaDataNames = manifest.elements("meta-data").map { it.androidAttr("name") }
            assertFalse(WORK_MANAGER_INITIALIZER in metaDataNames, "$variant merged manifest still contains $WORK_MANAGER_INITIALIZER")
        }
    }

    @Test
    fun `every health permission the app requests at runtime is declared, plus the foreground-service permissions`() {
        val expected =
            HealthPermissions.REQUIRED + HealthPermissions.OPTIONAL + HealthPermissions.SPECIAL +
                setOf(FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS)
        assertEquals(9, (HealthPermissions.REQUIRED + HealthPermissions.OPTIONAL + HealthPermissions.SPECIAL).size)
        forEachManifest { variant, manifest ->
            val requested = manifest.requestedPermissions()
            val missing = expected - requested
            assertTrue(missing.isEmpty(), "$variant merged manifest lacks $missing")
            // The health permissions must be declared under <manifest>, not inside <application>.
            manifest
                .elements("uses-permission")
                .filter { it.androidAttr("name").startsWith("android.permission.health.") }
                .forEach { assertEquals("manifest", it.parentNode.nodeName, "$variant: ${it.androidAttr("name")} outside <manifest>") }
        }
    }

    @Test
    fun `Health Connect APK is visible through queries`() {
        forEachManifest { variant, manifest ->
            val queried =
                manifest
                    .elements("queries")
                    .flatMap { it.children("package") }
                    .map { it.androidAttr("name") }
            assertTrue(HEALTH_CONNECT_PACKAGE in queried, "$variant: <queries> lacks $HEALTH_CONNECT_PACKAGE: $queried")
        }
    }

    @Test
    fun `the apps that wrote Health Connect data are visible through queries, without QUERY_ALL_PACKAGES`() {
        forEachManifest { variant, manifest ->
            val queries = manifest.elements("queries")
            val packages = queries.flatMap { it.children("package") }.map { it.androidAttr("name") }
            assertTrue(OHEALTH_PACKAGE in packages, "$variant: <queries> lacks the default source $OHEALTH_PACKAGE: $packages")
            val intents =
                queries.flatMap { it.children("intent") }.map { intent ->
                    intent.children("action").map { it.androidAttr("name") }.toSet() to
                        intent.children("category").map { it.androidAttr("name") }.toSet()
                }
            assertTrue(
                (setOf(SHOW_RATIONALE) to emptySet<String>()) in intents,
                "$variant: no <intent> query for $SHOW_RATIONALE: $intents",
            )
            assertTrue(
                (setOf(VIEW_PERMISSION_USAGE) to setOf(HEALTH_PERMISSIONS_CATEGORY)) in intents,
                "$variant: no <intent> query for $VIEW_PERMISSION_USAGE + $HEALTH_PERMISSIONS_CATEGORY: $intents",
            )
            assertFalse(QUERY_ALL_PACKAGES in manifest.requestedPermissions(), "$variant requests $QUERY_ALL_PACKAGES")
        }
    }

    @Test
    fun `the main activity is resized by the keyboard so that imePadding works`() {
        forEachManifest { variant, manifest ->
            val activity = manifest.elements("activity").firstOrNull { qualified(it.androidAttr("name")) == MAIN_ACTIVITY }
            assertNotNull(activity, "$variant: $MAIN_ACTIVITY missing")
            assertEquals("adjustResize", checkNotNull(activity).androidAttr("windowSoftInputMode"), variant)
        }
    }

    @Test
    fun `rationale activity handles ACTION_SHOW_PERMISSIONS_RATIONALE`() {
        forEachManifest { variant, manifest ->
            val activity = manifest.elements("activity").firstOrNull { it.androidAttr("name") == RATIONALE_ACTIVITY }
            assertNotNull(activity, "$variant: $RATIONALE_ACTIVITY missing")
            checkNotNull(activity)
            assertEquals("true", activity.androidAttr("exported"), "$variant: rationale activity must be exported")
            assertTrue(SHOW_RATIONALE in activity.intentActions(), "$variant: rationale activity lacks $SHOW_RATIONALE")
        }
    }

    @Test
    fun `Android 14+ activity-alias shows the rationale for VIEW_PERMISSION_USAGE with HEALTH_PERMISSIONS`() {
        forEachManifest { variant, manifest ->
            val alias =
                manifest.elements("activity-alias").firstOrNull { qualified(it.androidAttr("targetActivity")) == RATIONALE_ACTIVITY }
            assertNotNull(alias, "$variant: no activity-alias targets $RATIONALE_ACTIVITY")
            checkNotNull(alias)
            assertEquals("true", alias.androidAttr("exported"), "$variant: alias must be exported")
            assertEquals(START_VIEW_PERMISSION_USAGE, alias.androidAttr("permission"), "$variant: alias permission")
            assertTrue(VIEW_PERMISSION_USAGE in alias.intentActions(), "$variant: alias lacks $VIEW_PERMISSION_USAGE")
            assertTrue(HEALTH_PERMISSIONS_CATEGORY in alias.intentCategories(), "$variant: alias lacks $HEALTH_PERMISSIONS_CATEGORY")
        }
    }

    @Test
    fun `WorkManager foreground service has the dataSync type`() {
        forEachManifest { variant, manifest ->
            val service = manifest.elements("service").firstOrNull { it.androidAttr("name") == SYSTEM_FOREGROUND_SERVICE }
            assertNotNull(service, "$variant: $SYSTEM_FOREGROUND_SERVICE missing")
            assertEquals("dataSync", checkNotNull(service).androidAttr("foregroundServiceType"), variant)
        }
    }

    @Test
    fun `FileProvider is private, grants uri permissions and uses the applicationId authority`() {
        forEachManifest { variant, manifest ->
            val provider = manifest.elements("provider").firstOrNull { it.androidAttr("name") == FILE_PROVIDER }
            assertNotNull(provider, "$variant: $FILE_PROVIDER missing")
            checkNotNull(provider)
            assertEquals("$APPLICATION_ID.fileprovider", provider.androidAttr("authorities"), variant)
            assertEquals("false", provider.androidAttr("exported"), variant)
            assertEquals("true", provider.androidAttr("grantUriPermissions"), variant)
            val paths = provider.children("meta-data").map { it.androidAttr("name") }
            assertTrue(FILE_PROVIDER_PATHS in paths, "$variant: FileProvider lacks $FILE_PROVIDER_PATHS")
        }
    }

    @Test
    fun `the demo-mode entry exists in debug builds only`() {
        val activities =
            mergedManifests.mapValues { (_, file) ->
                parse(file).elements("activity").map { qualified(it.androidAttr("name")) }
            }
        assertTrue(DEMO_ACTIVITY in activities.getValue("debug"), "debug lacks $DEMO_ACTIVITY")
        assertFalse(DEMO_ACTIVITY in activities.getValue("release"), "release must not contain $DEMO_ACTIVITY")
    }

    private fun forEachManifest(check: (String, Document) -> Unit) {
        assertTrue(mergedManifests.isNotEmpty(), "no merged manifests passed to the test")
        mergedManifests.forEach { (variant, file) -> check(variant, parse(file)) }
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

    private fun Element.children(tagName: String): List<Element> {
        val nodes = getElementsByTagName(tagName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.androidAttr(name: String): String = getAttributeNS(ANDROID_NS, name)

    private fun Element.intentActions(): Set<String> =
        children("intent-filter").flatMap { filter -> filter.children("action").map { it.androidAttr("name") } }.toSet()

    private fun Element.intentCategories(): Set<String> =
        children("intent-filter").flatMap { filter -> filter.children("category").map { it.androidAttr("name") } }.toSet()

    private fun Document.requestedPermissions(): Set<String> =
        (elements("uses-permission") + elements("uses-permission-sdk-23"))
            .map { it.androidAttr("name") }
            .toSet()

    private fun qualified(className: String): String = if (className.startsWith(".")) APPLICATION_ID + className else className

    private companion object {
        const val PROPERTY_PREFIX = "pulsestages.mergedManifest."
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val APPLICATION_ID = "io.github.alien2003.pulsestages"
        const val INTERNET = "android.permission.INTERNET"
        const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
        const val FOREGROUND_SERVICE_DATA_SYNC = "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val WORK_MANAGER_INITIALIZER = "androidx.work.WorkManagerInitializer"
        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
        const val OHEALTH_PACKAGE = "com.heytap.health.international"
        const val QUERY_ALL_PACKAGES = "android.permission.QUERY_ALL_PACKAGES"
        const val MAIN_ACTIVITY = "$APPLICATION_ID.MainActivity"
        const val RATIONALE_ACTIVITY = "$APPLICATION_ID.PermissionsRationaleActivity"
        const val DEMO_ACTIVITY = "$APPLICATION_ID.demo.DemoActivity"
        const val SHOW_RATIONALE = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
        const val VIEW_PERMISSION_USAGE = "android.intent.action.VIEW_PERMISSION_USAGE"
        const val HEALTH_PERMISSIONS_CATEGORY = "android.intent.category.HEALTH_PERMISSIONS"
        const val START_VIEW_PERMISSION_USAGE = "android.permission.START_VIEW_PERMISSION_USAGE"
        const val SYSTEM_FOREGROUND_SERVICE = "androidx.work.impl.foreground.SystemForegroundService"
        const val FILE_PROVIDER = "androidx.core.content.FileProvider"
        const val FILE_PROVIDER_PATHS = "android.support.FILE_PROVIDER_PATHS"
        val REQUIRED_VARIANTS = setOf("debug", "release")
    }
}
