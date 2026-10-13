import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Declaring the Kotlin plugins here puts KGP 2.4.21 on the shared build classpath, which upgrades the
    // KGP 2.2.10 runtime dependency of AGP 9's built-in Kotlin (see AGP 9.0 release notes).
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.android.junit) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

val ktlintVersion: String = libs.versions.ktlint.get()
val detektVersion: String = libs.versions.detekt.get()
val detektKotlinVersion: String = libs.versions.detektKotlin.get()
val jvmTargetVersion: String = libs.versions.jvmTarget.get()
val detektConfigFile = layout.projectDirectory.file("config/detekt/detekt.yml")

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<KtlintExtension> {
        version.set(ktlintVersion)
        android.set(false)
        filter {
            exclude { element -> element.file.invariantSeparatorsPath.contains("/build/") }
        }
    }
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<DetektExtension> {
        toolVersion = detektVersion
        buildUponDefaultConfig = true
        allRules = false
        parallel = true
        config.setFrom(detektConfigFile)
        // Every source set (main, test, androidTest, ...); detekt skips build/ by default.
        source.setFrom(files("src"))
    }
    tasks.withType<Detekt>().configureEach {
        jvmTarget = jvmTargetVersion
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(false)
            txt.required.set(false)
            md.required.set(false)
        }
    }
    // detekt 1.23.8 embeds the Kotlin 2.0.21 compiler; keep its tool classpath on exactly that version even
    // though the build itself uses Kotlin 2.4 (documented in detekt's "Gradle runtime dependencies" section).
    configurations.matching { it.name == "detekt" }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion(detektKotlinVersion)
            }
        }
    }
}

/*
 * Module-graph guard. Allowed project-dependency edges (anything else fails the build):
 *   app -> work, data-hc, data-local, core-algo
 *   work -> data-hc, data-local, core-algo
 *   data-hc -> data-local, core-algo
 *   data-local -> core-algo
 *   algo-cli -> core-algo
 *   core-algo -> (nothing)
 * It also asserts that :core-algo applies no Android Gradle plugin. The runtime-classpath purity of :core-algo is
 * verified by :core-algo:verifyPureJvmRuntimeClasspath (resolution must happen in the owning project).
 * Both run as dependencies of :core-algo:test, so `./gradlew test` always enforces them.
 */
val verifyModuleGraph =
    tasks.register("verifyModuleGraph") {
        group = "verification"
        description = "Verifies project-dependency edges against the allowed module graph and that :core-algo is not an Android module."

        val allowedEdges: Map<String, List<String>> =
            mapOf(
                ":app" to listOf(":core-algo", ":data-hc", ":data-local", ":work"),
                ":work" to listOf(":core-algo", ":data-hc", ":data-local"),
                ":data-hc" to listOf(":core-algo", ":data-local"),
                ":data-local" to listOf(":core-algo"),
                ":algo-cli" to listOf(":core-algo"),
                ":core-algo" to emptyList(),
            )
        val androidPluginIds =
            listOf(
                "com.android.application",
                "com.android.library",
                "com.android.base",
                "com.android.test",
                "com.android.dynamic-feature",
                "com.android.kotlin.multiplatform.library",
                "com.android.built-in-kotlin",
                "com.android.lint",
            )
        val allProjects = rootProject.subprojects.toList()
        val coreAlgo = project(":core-algo")

        // provider {} values are computed when the configuration cache entry is stored (after all projects are
        // configured), so the task action never touches Project instances.
        val actualEdges: Provider<Map<String, List<String>>> =
            provider {
                allProjects.associate { p ->
                    val edges =
                        p.configurations
                            .flatMap { configuration ->
                                configuration.dependencies.withType(ProjectDependency::class.java).map { it.path }
                            }.filter { it != p.path }
                            .distinct()
                            .sorted()
                    p.path to edges
                }
            }
        val coreAlgoAndroidPlugins: Provider<List<String>> =
            provider {
                val byId = androidPluginIds.filter { coreAlgo.pluginManager.hasPlugin(it) }
                val byClass = coreAlgo.plugins.map { it.javaClass.name }.filter { it.startsWith("com.android.") }
                (byId + byClass).distinct().sorted()
            }
        val report = layout.buildDirectory.file("reports/module-graph/edges.txt")

        inputs.property("allowedEdges", allowedEdges)
        inputs.property("actualEdges", actualEdges)
        inputs.property("coreAlgoAndroidPlugins", coreAlgoAndroidPlugins)
        outputs.file(report)

        doLast {
            val actual = actualEdges.get()
            val problems = mutableListOf<String>()
            actual.forEach { (from, targets) ->
                val allowed = allowedEdges[from]
                if (allowed == null) {
                    problems += "$from is not listed in the allowed module graph (root build.gradle.kts)"
                } else {
                    targets.filterNot { it in allowed }.forEach { to -> problems += "forbidden project dependency $from -> $to" }
                }
            }
            coreAlgoAndroidPlugins.get().forEach { problems += ":core-algo must stay pure JVM but applies Android plugin $it" }

            val reportFile = report.get().asFile
            reportFile.parentFile.mkdirs()
            reportFile.writeText(
                buildString {
                    actual.toSortedMap().forEach { (from, targets) -> appendLine("$from -> ${targets.joinToString(", ")}") }
                    if (problems.isNotEmpty()) {
                        appendLine()
                        problems.forEach { appendLine("PROBLEM: $it") }
                    }
                },
            )
            if (problems.isNotEmpty()) {
                throw GradleException("Module graph check failed:\n" + problems.joinToString("\n") { "  - $it" })
            }
        }
    }

tasks.named("check") { dependsOn(verifyModuleGraph) }
