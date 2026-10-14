plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.android.junit)
}

android {
    namespace = "io.github.alien2003.pulsestages.data.local"
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
            // Robolectric reads the merged test manifest and resources produced by AGP.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

room {
    schemaDirectory("$projectDir/schemas")
}

// Robolectric downloads its android-all jar from Maven Central at test time unless it runs offline. Resolve the jar
// through Gradle instead (cached, reproducible, no network access from tests) and point Robolectric at it.
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
    // The exported Room schemas: LedgerMigrationTest builds databases of older versions from them.
    val schemas = layout.projectDirectory.dir("schemas")
    inputs.dir(schemas).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("roomSchemas")
    systemProperty("pulsestages.roomSchemas", schemas.asFile.absolutePath)
    inputs.files(prepareRobolectricDependencies).withPropertyName("robolectricDependencies").withNormalizer(ClasspathNormalizer::class.java)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricDependencyDir.get().asFile.absolutePath)
}

dependencies {
    // Types of these libraries appear in this module's public API (AppSettings -> AlgoParams, the RoomDatabase
    // subclass and DAOs, DataStore instances, Flow / CoroutineScope), and Hilt in :app must see them.
    api(project(":core-algo"))
    api(libs.androidx.room.runtime)
    api(libs.androidx.datastore)
    api(libs.androidx.datastore.preferences)
    api(libs.kotlinx.coroutines.android)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // Room DAO and logger tests need the Android framework (SQLite): Robolectric (JUnit 4) via the Vintage engine.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testRuntimeOnly(libs.junit.vintage.engine)
    robolectricAndroidAll(libs.robolectric.android.all.instrumented)
    // Builds the real Hilt graph of this module in a Robolectric test (nothing in :app consumes it yet).
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)
}
