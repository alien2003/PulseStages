plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.android.junit)
}

android {
    namespace = "io.github.alien2003.pulsestages.data.hc"
    compileSdk {
        version =
            release(libs.versions.compileSdk.get().toInt()) {
                minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
            }
    }
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
    // FakeHealthRepository, the in-memory ledger and the other test doubles live in src/testFixtures so that the
    // tests of :work (and :app) can reuse them: testImplementation(testFixtures(project(":data-hc"))).
    testFixtures {
        enable = true
    }
    testOptions {
        unitTests {
            // The Room cross-check test runs on Robolectric, which reads the merged test manifest and resources.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

// Like :data-local: Robolectric runs offline against the android-all jar resolved (and cached) by Gradle.
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

tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricDependencies)
    inputs.files(prepareRobolectricDependencies).withPropertyName("robolectricDependencies").withNormalizer(ClasspathNormalizer::class.java)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDependencyDir.get().asFile.absolutePath)
    // Gate G4 of docs/prereg/prereg-merge-gap.md: the grouping fixture that tools/train generates and checks as well
    // (MergeGapGroupingFixtureTest, arms A, B, R45 and R90; arm C and SourceSettlementMergeGapTest only up to a55aa55).
    val mergeGapFixture = layout.projectDirectory.file("../tools/train/tests/data/mergegap-grouping.json")
    inputs.file(mergeGapFixture).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("mergegapGroupingFixture")
    systemProperty("pulsestages.mergegapGroupingFixture", mergeGapFixture.asFile.absolutePath)
}

dependencies {
    // Types of these modules appear in this module's public API (NightInput, AlgoParams, AppSettings, DAOs, ...).
    api(project(":core-algo"))
    api(project(":data-local"))

    implementation(libs.androidx.health.connect.client)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testFixturesApi(project(":core-algo"))
    testFixturesApi(project(":data-local"))
    testFixturesApi(libs.kotlinx.coroutines.core)
    // harnessTest() (testutil/TestData.kt) runs scenarios with runTest; shared with the tests of :work.
    testFixturesApi(libs.kotlinx.coroutines.test)
    testFixturesImplementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // One cross-check runs the pipeline on the real Room ledger (Android SQLite): Robolectric (JUnit 4) via Vintage.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)
    robolectricAndroidAll(libs.robolectric.android.all.instrumented)
    // Builds the real Hilt graph of this module in a Robolectric test (Dagger only checks requested bindings).
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
