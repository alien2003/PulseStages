// Command-line entry point to :core-algo, used by the Python training/evaluation scripts in tools/train.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

application {
    mainClass = "io.github.alien2003.pulsestages.cli.MainKt"
    applicationName = "pulsestages-algo"
}

dependencies {
    implementation(project(":core-algo"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
