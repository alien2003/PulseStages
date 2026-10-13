// Command-line entry point to :core-algo, used by the Python training/evaluation scripts in tools/train.
// `./gradlew :algo-cli:installDist` produces the launcher build/install/algo-cli/bin/algo-cli.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

application {
    mainClass = "io.github.alien2003.pulsestages.cli.MainKt"
    applicationName = "algo-cli"
}

dependencies {
    implementation(project(":core-algo"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(testFixtures(project(":core-algo")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
