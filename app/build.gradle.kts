plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.jetbrains.kotlin.plugin.serialization)
}

android {
    namespace = "com.kernelloader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kernelloader"
        minSdk = 28
        targetSdk = 34
        versionCode = 26
        versionName = "4.1-universal"

        // In-app auto-update sources (GitHub Releases database)
        buildConfigField(
            "String",
            "UPDATE_API_URL",
            "\"https://api.github.com/repos/bmjubairdadu/kernel-loder/releases/latest\""
        )
        buildConfigField(
            "String",
            "UPDATE_RELEASES_PAGE",
            "\"https://github.com/bmjubairdadu/kernel-loder/releases\""
        )
    }

    buildTypes {
        release {
            // R8 code shrinking + resource shrinking - keeps the APK tiny
            // (AGP 9 DSL: isShrinkResources lives on the build type,
            //  the optimization block only toggles R8 itself)
            isShrinkResources = true
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Side-load distribution: sign with the debug keystore so the same
            // signature can install newer builds in-place (self auto-update).
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.libsu.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}