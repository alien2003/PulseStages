import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.android.junit)
}

// -PversionName=1.2.3 -PversionCode=1020399 (the release workflow derives both from the git tag:
// .github/scripts/version-from-tag.sh).
val appVersionName: String = providers.gradleProperty("versionName").getOrElse("0.1.0-dev")
val appVersionCode: Int = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)

// Release signing is configured only when all four variables are present; otherwise release stays unsigned.
val releaseKeystorePath = providers.environmentVariable("PULSESTAGES_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("PULSESTAGES_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("PULSESTAGES_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("PULSESTAGES_KEY_PASSWORD").orNull
val hasReleaseSigning =
    listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.alien2003.pulsestages"
    compileSdk {
        version =
            release(libs.versions.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
            }
    }

    defaultConfig {
        applicationId = "io.github.alien2003.pulsestages"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName
        // The on-device end-to-end suite (src/androidTest) runs against the INSTALLED debug app without reinstalling it:
        // tools/device-test/run-device-tests.sh installs only the test APK (assembleDebugAndroidTest) and starts each
        // class with `am instrument` (tools/device-test/README.md). Never use connectedAndroidTest for it: that task
        // installs the app APK as well. E2eRunner installs an in-memory WorkManager before the app's Application.onCreate
        // runs in the test process, so that no worker of the app can run there.
        testInstrumentationRunner = "io.github.alien2003.pulsestages.e2e.E2eRunner"
    }

    androidResources {
        localeFilters += listOf("en", "ru")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release").apply {
                storeFile = file(checkNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    testOptions {
        unitTests {
            // The app-graph, wiring and screenshot tests run on Robolectric with the app's merged resources and assets.
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

/**
 * Hands the merged manifest of every variant (obtained through the Variant API, SingleArtifact.MERGED_MANIFEST) to the
 * unit-test JVM as `-Dpulsestages.mergedManifest.<variant>=<path>`. Declaring the files as inputs makes the test task
 * depend on manifest merging, so MergedManifestTest always checks the real merged output.
 */
abstract class MergedManifestsArgumentProvider : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFiles: ConfigurableFileCollection

    @get:Internal
    abstract val manifestPathsByVariant: MapProperty<String, String>

    override fun asArguments(): Iterable<String> =
        manifestPathsByVariant.get().map { (variant, path) -> "-Dpulsestages.mergedManifest.$variant=$path" }
}

val mergedManifests = objects.newInstance(MergedManifestsArgumentProvider::class.java)

/**
 * Hands the minified release build to the unit-test JVM (ReleaseApkTest; AGP creates unit tests for the debug build
 * type only): the APK directory (SingleArtifact.APK), R8's mapping file (SingleArtifact.OBFUSCATION_MAPPING_FILE) and
 * the SDK's aapt2, as `-Dpulsestages.release.*` properties. As inputs they make the test task build the release APK,
 * so the test always checks what would ship.
 */
abstract class ReleaseArtifactsArgumentProvider : CommandLineArgumentProvider {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aapt2: RegularFileProperty

    override fun asArguments(): Iterable<String> =
        listOf(
            "-Dpulsestages.release.apkDir=${apkDirectory.get().asFile.absolutePath}",
            "-Dpulsestages.release.mapping=${mappingFile.get().asFile.absolutePath}",
            "-Dpulsestages.release.aapt2=${aapt2.get().asFile.absolutePath}",
        )
}

val releaseArtifacts = objects.newInstance(ReleaseArtifactsArgumentProvider::class.java)

// Like :work, :data-local and :data-hc: Robolectric runs offline against the android-all jar resolved by Gradle.
val robolectricAndroidAll = configurations.dependencyScope("robolectricAndroidAll")
val robolectricAndroidAllClasspath =
    configurations.resolvable("robolectricAndroidAllClasspath") {
        extendsFrom(robolectricAndroidAll.get())
        isTransitive = false
    }
val robolectricDependencyDir = layout.buildDirectory.dir("robolectric-deps")
val prepareRobolectricDependencies =
    tasks.register<Sync>("prepareRobolectricDependencies") {
        description = "Copies the android-all jar resolved by Gradle into the directory used by offline Robolectric."
        from(robolectricAndroidAllClasspath)
        into(robolectricDependencyDir)
    }

// The screenshots of docs/screenshots are rendered from synthetic data on the JVM by ScreenshotsTest (Robolectric
// native graphics). Every test run renders them into build/screenshots; with -PrecordScreenshots they are written to
// docs/screenshots instead:  ./gradlew :app:testDebugUnitTest --tests '*ScreenshotsTest' -PrecordScreenshots
val recordScreenshots = providers.gradleProperty("recordScreenshots").isPresent
val screenshotDir: String =
    if (recordScreenshots) {
        rootProject.layout.projectDirectory
            .dir("docs/screenshots")
            .asFile.absolutePath
    } else {
        layout.buildDirectory
            .dir("screenshots")
            .get()
            .asFile.absolutePath
    }
// docs/settings-reference.md is generated from the settings catalog, the defaults and the validators by
// SettingsReferenceTest, which fails when the committed file is out of date; with -PrecordSettingsReference it
// rewrites the file:  ./gradlew :app:testDebugUnitTest --tests '*SettingsReferenceTest' -PrecordSettingsReference
val recordSettingsReference = providers.gradleProperty("recordSettingsReference").isPresent
val settingsReferenceFile: File = rootProject.layout.projectDirectory.file("docs/settings-reference.md").asFile
// The reference links to the sections of ALGORITHM.md; a renamed heading must re-run (and fail) the test.
val algorithmDoc: File = rootProject.layout.projectDirectory.file("ALGORITHM.md").asFile
// ResourcesParityTest reads the strings of every module; their changes must re-run the app's tests.
val moduleStrings =
    files(
        listOf("work", "data-hc", "data-local", "core-algo").map { module ->
            fileTree(rootProject.layout.projectDirectory.dir(module)) { include("src/*/res/values*/*.xml") }
        },
    )

val e2eUiSources = fileTree("src/androidTest") { include("**/e2e/ui/*.kt") }

tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricDependencies)
    inputs.files(prepareRobolectricDependencies).withPropertyName("robolectricDependencies").withNormalizer(ClasspathNormalizer::class.java)
    inputs.files(moduleStrings).withPropertyName("moduleStrings").withPathSensitivity(PathSensitivity.RELATIVE)
    // E2eResourceNamesTest checks the resource names of the on-device suite's UI tour.
    inputs.files(e2eUiSources).withPropertyName("e2eUiSources").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDependencyDir.get().asFile.absolutePath)
    systemProperty("pulsestages.screenshots.dir", screenshotDir)
    if (recordScreenshots) outputs.upToDateWhen { false }
    inputs.files(algorithmDoc).withPropertyName("algorithmDoc").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("pulsestages.settingsReference.file", settingsReferenceFile.absolutePath)
    if (recordSettingsReference) {
        systemProperty("pulsestages.settingsReference.record", "true")
        outputs.upToDateWhen { false }
    } else {
        inputs.files(settingsReferenceFile).withPropertyName("settingsReference").withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

androidComponents {
    onVariants { variant ->
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        mergedManifests.manifestFiles.from(manifest)
        mergedManifests.manifestPathsByVariant.put(variant.name, manifest.map { it.asFile.absolutePath })
        if (variant.buildType == "release") {
            releaseArtifacts.apkDirectory.set(variant.artifacts.get(SingleArtifact.APK))
            releaseArtifacts.mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
        }
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.configureTestTask { testTask ->
            testTask.jvmArgumentProviders.add(mergedManifests)
            testTask.jvmArgumentProviders.add(releaseArtifacts)
        }
    }
}
releaseArtifacts.aapt2.set(androidComponents.sdkComponents.aapt2.flatMap { it.executable })

dependencies {
    implementation(project(":core-algo"))
    implementation(project(":data-local"))
    implementation(project(":data-hc"))
    implementation(project(":work"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    implementation(libs.androidx.compose.material3.adaptive)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.health.connect.client)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    // FakeHealthRepository, FakeSettingsRepository, TestClock and RecordingLogger for the ViewModel tests.
    testImplementation(testFixtures(project(":data-hc")))
    testRuntimeOnly(libs.junit.platform.launcher)

    // The production Hilt graph, the WorkManager wiring and the screenshots run on Robolectric (JUnit 4 through the
    // Vintage engine), with Hilt's test components, work-testing's test WorkManager and Compose's test rule.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)
    robolectricAndroidAll(libs.robolectric.android.all.instrumented)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    // The on-device end-to-end suite (src/androidTest, JUnit 4): only the test APK carries these; nothing is added to
    // the app (its merged manifest, and MergedManifestTest, stay unchanged).
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // The in-memory WorkManager of the suite's processes (E2eWorkManager); the app keeps its own WorkManager.
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
