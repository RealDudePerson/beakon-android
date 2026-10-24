plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.beakon.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.beakon.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            // Release-optimized build (debuggable=false, no Compose ui-tooling,
            // ART runs its optimizing compiler) signed with the *debug* keystore
            // so it can be installed over the existing debug build without an
            // uninstall, which would lose the owner's settings. Same applicationId
            // and signing key as the debug APK, so it is a plain update.
            signingConfig = signingConfigs.getByName("debug")
            // Minification stays off on purpose: this app uses kotlinx.serialization
            // and Compose, where a missing keep rule is a runtime crash rather than
            // a build error. There is no device here to catch such a crash, so we
            // do not enable R8. See README.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        warningsAsErrors = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
            )
        }
    }
}

// Emit Compose compiler metrics/reports on every build. These are diagnostic only
// (no runtime effect, no APK content) and are the evidence for recomposition work:
// build/compose_reports/<variant>-composables.txt says which composables can skip
// recomposition, and <variant>-classes.txt says which classes the compiler treats
// as unstable (unstable parameters are why a composable cannot skip).
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.osmdroid.android)
    implementation(libs.play.services.location)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Make the repository root available to JVM tests so the integration test can
// launch the Python mock server that lives under mock/.
tasks.withType<Test>().configureEach {
    systemProperty("beakon.repoRoot", rootProject.projectDir.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
