plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.android.junit)
}

android {
    namespace = "io.github.alien2003.pulsestages.work"
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
    testOptions {
        unitTests {
            // The worker and scheduler tests run on Robolectric, which reads the merged test manifest and resources.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

// Like :data-local and :data-hc: Robolectric runs offline against the android-all jar resolved (and cached) by Gradle.
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
    // Gate V3 of docs/prereg/prereg-merge-gap.md: MergeGapGateReplayTest writes its replay report here
    // (-Ppulsestages.mergegapReport=<path>) and replays the arms of -Ppulsestages.mergegapArms (default: A, B, R45 and
    // R90; arm C, the removed setting sources.continuationGapMin, runs only at the experiment's commit a55aa55).
    systemProperty(
        "pulsestages.mergegap.v3Report",
        providers
            .gradleProperty("pulsestages.mergegapReport")
            .getOrElse(
                layout.buildDirectory
                    .file("reports/mergegap-v3.json")
                    .get()
                    .asFile.absolutePath,
            ),
    )
    providers
        .gradleProperty("pulsestages.mergegapArms")
        .orNull
        ?.let { systemProperty("pulsestages.mergegap.arms", it) }
}

// The replays of gate V3 (JUnit tag mergegap-replay) are excluded from the unit-test tasks unless the Gradle property
// pulsestages.mergegapReplay is set; then only they run (docs/prereg/prereg-merge-gap.md section 16).
val mergeGapReplay: Boolean = providers.gradleProperty("pulsestages.mergegapReplay").isPresent
junitPlatform {
    filters {
        if (mergeGapReplay) includeTags("mergegap-replay") else excludeTags("mergegap-replay")
    }
}

dependencies {
    // Types of these modules appear in this module's public API (WorkScheduler takes ScheduleSettings and RecomputeScope).
    api(project(":core-algo"))
    api(project(":data-local"))
    api(project(":data-hc"))

    api(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(testFixtures(project(":data-hc")))
    testImplementation(testFixtures(project(":core-algo")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Worker Result mapping (TestListenableWorkerBuilder) and the WorkManager scheduler need a Context: Robolectric
    // (JUnit 4) via the Vintage engine, with work-testing's test WorkManager.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testRuntimeOnly(libs.junit.vintage.engine)
    robolectricAndroidAll(libs.robolectric.android.all.instrumented)
}
