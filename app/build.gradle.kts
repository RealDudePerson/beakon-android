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
        versionCode = 8
        versionName = "1.4"
    }

    // Permanent signing key: a keystore kept in ~/workspace/beakon-signing
    // (outside this repo, so it is never committed, and outside /root, so VM
    // replacements cannot wipe it and silently change the signer — that is
    // what broke updates in 1.2/1.3, when the /root debug keystore was
    // regenerated and Android refused the update as a different signer).
    // Both debug and release builds use it when the properties file exists,
    // so every shipped APK shares one signer forever; otherwise they fall
    // back to the debug keystore and a build on another machine still works.
    val permanentPropsFile = rootProject.projectDir.resolve("../beakon-signing/signing.properties")
    val usePermanentSigning = permanentPropsFile.exists()

    signingConfigs {
        if (usePermanentSigning) {
            create("permanent") {
                val props = permanentPropsFile.readLines()
                    .filter { "=" in it }
                    .associate { val (k, v) = it.split("=", limit = 2); k to v }
                storeFile = file(props["storeFile"])
                storePassword = props["storePassword"]
                keyAlias = props["keyAlias"]
                keyPassword = props["keyPassword"]
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = if (usePermanentSigning) signingConfigs.getByName("permanent") else signingConfigs.getByName("debug")
        }
        release {
            // Release-optimized build (debuggable=false, no Compose ui-tooling,
            // ART runs its optimizing compiler) signed with the permanent
            // keystore above, so it installs over the existing build without
            // an uninstall, which would lose the owner's settings. Same
            // applicationId and signing key as the debug APK, so it is a
            // plain update.
            signingConfig = if (usePermanentSigning) signingConfigs.getByName("permanent") else signingConfigs.getByName("debug")
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
