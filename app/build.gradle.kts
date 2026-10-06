plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // HyperIsland's Compose sources refer to this namespace for R and BuildConfig.
    // The application ID remains unchanged so the installed Xposed module is upgraded in place.
    namespace = "io.github.hyperisland"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.melolock"
        minSdk = 36
        targetSdk = 37
        versionCode = 9
        versionName = "0.2.0"
        testInstrumentationRunner = "io.github.melolock.ToggleInstrumentation"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources { excludes += "**" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigationevent:navigationevent-android:1.1.2")
    implementation("org.jetbrains.compose.foundation:foundation-android:1.12.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("androidx.graphics:graphics-shapes:1.1.0")
    implementation("io.github.d4viddf:hyperisland_kit:0.4.4")
    implementation("com.github.aptabase:aptabase-kotlin:0.0.8")
    implementation("org.luckypray:dexkit:2.2.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    compileOnly("de.robv.android.xposed:api:82")
}
