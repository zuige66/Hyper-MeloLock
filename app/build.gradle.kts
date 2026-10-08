import java.io.FileInputStream
import java.util.Properties

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

    // 发布签名：口令只放在仓库根的 keystore.properties（.gitignore 已排除 *.keystore 与该文件）。
    // 文件不存在时（例如 CI 或别人克隆仓库）release 构建会直接用 debug 签名，不会报错——
    // 但那样打出来的是未正式签名的包，不能上传 Release。
    val keystoreFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreFile.exists()) {
            val props = Properties().apply {
                FileInputStream(keystoreFile).use { load(it) }
            }
            create("release") {
                storeFile = rootProject.file(props.getProperty("STORE_FILE"))
                storePassword = props.getProperty("STORE_PASSWORD")
                keyAlias = props.getProperty("KEY_ALIAS")
                keyPassword = props.getProperty("KEY_PASSWORD")
                // Android 16 只需要 v2/v3，但第三方安装器/备份工具仍可能去读 v1，全部签上最省心。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            // 不开混淆：这是个 Xposed 模块，Hook 与 ROM 内部视图都靠类名/方法名字符串定位，
            // 开 ProGuard/R8 收益极小、风险不小。
            isMinifyEnabled = false
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        // 迁入的 HyperIsland 多语言资源里有第三方库遗留的 ExtraTranslation（如 values-ar 里的
        // androidx_startup），不是本项目能改的源头。release 构建不该被它们拦住。
        checkReleaseBuilds = false
        abortOnError = false
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
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("io.github.d4viddf:hyperisland_kit:0.4.4")
    implementation("com.github.aptabase:aptabase-kotlin:0.0.8")
    implementation("org.luckypray:dexkit:2.2.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    compileOnly("de.robv.android.xposed:api:82")
}
