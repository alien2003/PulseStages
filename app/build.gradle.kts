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

// -PversionName=1.2.3 -PversionCode=10203 (CI release workflow derives both from the git tag).
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
 * depend on manifest merging, so NoInternetPermissionTest always checks the real merged output.
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

androidComponents {
    onVariants { variant ->
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        mergedManifests.manifestFiles.from(manifest)
        mergedManifests.manifestPathsByVariant.put(variant.name, manifest.map { it.asFile.absolutePath })
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.configureTestTask { testTask ->
            testTask.jvmArgumentProviders.add(mergedManifests)
        }
    }
}

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
    testRuntimeOnly(libs.junit.platform.launcher)
}
