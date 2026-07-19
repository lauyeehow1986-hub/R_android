import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing is opt-in and secret-free in the repo: it reads a gitignored
// keystore.properties at the repo root (storeFile / storePassword / keyAlias /
// keyPassword). Absent that file, release builds stay unsigned — so debug builds
// and CI that don't need a signed artifact are unaffected. See README "Releasing".
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.rmobile.console"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rmobile.console"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

        // Overridable per build: -PrExecutionBaseUrl=https://your-host/
        // Defaults to the Android emulator's alias for the host machine, matching
        // the docker-compose backend in /backend running on localhost:8000.
        val baseUrl = (project.findProperty("rExecutionBaseUrl") as String?)
            ?: "http://10.0.2.2:8000/"
        buildConfigField("String", "R_EXECUTION_BASE_URL", "\"$baseUrl\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProperties.getProperty("storeFile")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Code shrinking is intentionally OFF. The app relies on
            // kotlinx.serialization models and a WebR @JavascriptInterface bridge
            // (WebRController) that R8 can silently strip/rename without exhaustive
            // keep rules; the APK size is dominated by the bundled WebR WASM assets,
            // which R8 doesn't shrink anyway. So minifying is all risk, no payoff for
            // a distributable build. Re-enable only with verified keep rules.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.retrofit.core)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
