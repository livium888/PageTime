import java.io.File
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.pagetime.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pagetime.app"
        minSdk = 26
        targetSdk = 34
        // Monotonic so every build is a valid update over the previous one.
        // Minutes since epoch (~8.5M now) always increases and fits in an Int.
        versionCode = (System.currentTimeMillis() / 60_000L).toInt()
        versionName = "1.0"
        buildConfigField(
            "String",
            "GEMINI_API_KEY",
            "\"${providers.gradleProperty("GEMINI_API_KEY").orNull ?: providers.environmentVariable("GEMINI_API_KEY").orNull ?: ""}\"",
        )
    }

    // Stable release key so users can update in place instead of uninstalling.
    // The keystore comes from repository secrets as base64; without them (local
    // dev) the release build is simply left unsigned and debug builds are used.
    signingConfigs {
        create("release") {
            val base64 = providers.environmentVariable("KEYSTORE_BASE64").orNull
            val password = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
            if (!base64.isNullOrBlank() && !password.isNullOrBlank()) {
                val keystoreFile =
                    File(
                        System.getenv("RUNNER_TEMP") ?: System.getProperty("java.io.tmpdir"),
                        "pagetime-release.p12",
                    )
                keystoreFile.writeBytes(Base64.getDecoder().decode(base64))
                storeFile = keystoreFile
                storePassword = password
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull ?: "pagetime"
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull ?: password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (!providers.environmentVariable("KEYSTORE_BASE64").orNull.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by the Readium EPUB toolkit.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    // Real org.json for JVM unit tests: the Android framework copies in the
    // mocked android.jar throw "Method not mocked" at runtime.
    testImplementation(libs.org.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.security.crypto)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.fsrs)
    implementation(libs.jsoup)
    implementation(libs.coil.compose)

    // MediaPipe tasks-genai: on-device LLM inference for the offline AI provider.
    // Weights are downloaded at runtime (Settings) — never bundled in the APK.
    implementation(libs.mediapipe.tasks.genai)

    // Readium: open-source EPUB engine (rendering, pagination/scroll, locators).
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.androidx.fragment.ktx)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    debugImplementation(libs.androidx.ui.tooling)
}
