import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 版本跟 magpie 官方 release 对齐：VERSION=0.1.4936 ./gradlew assembleRelease
val magpieVersion: String = (project.findProperty("MAGPIE_VERSION") as String?) ?: "0.1.4868"
val rev: Int = ((project.findProperty("REV") as String?) ?: "0").toInt()
val (vMajor, vMinor, vPatch) = magpieVersion.split(".").map { it.toInt() } + listOf(0, 0)
val appVersionCode = vMajor * 1_000_000 + vMinor * 10_000 + vPatch * 10 + rev

android {
    namespace = "ai.magpie.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.magpie.android"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = magpieVersion
    }

    // 签名密钥不进仓库。想复用自己的签名，把 keystore 放到 keystore/magpie.keystore
    // 并在 gradle.properties 里写 MAGPIE_KEYSTORE_PASS / MAGPIE_KEY_ALIAS / MAGPIE_KEY_PASS。
    val ks = rootProject.file("keystore/magpie.keystore")
    val ksPass = (project.findProperty("MAGPIE_KEYSTORE_PASS") as String?) ?: "magpie123"
    val keyAlias = (project.findProperty("MAGPIE_KEY_ALIAS") as String?) ?: "magpie"
    val keyPass = (project.findProperty("MAGPIE_KEY_PASS") as String?) ?: "magpie123"

    signingConfigs {
        if (ks.exists()) {
            create("magpie") {
                storeFile = ks
                storePassword = ksPass
                this.keyAlias = keyAlias
                this.keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ks.exists()) signingConfig = signingConfigs.getByName("magpie")
        }
        debug {
            if (ks.exists()) signingConfig = signingConfigs.getByName("magpie")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true   // AGP 9 默认不再生成 BuildConfig
    }
    packaging {
        // 二进制不让压缩算法动，装的时候才快
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    val miuix = "0.9.2"
    implementation("top.yukonga.miuix.kmp:miuix-ui:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-preference:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-icons:$miuix")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
}
