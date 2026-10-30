plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.speakloop.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.speakloop.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "2.2.4"
    }

    // 固定 debug 签名。不这么做的后果：GitHub Actions 每次跑在全新机器上，
    // AGP 会现场生成一把新的 debug 密钥，于是每次构建签名都不一样，
    // 新 APK 装不上旧版本，只能卸载重装 —— 而短语都存在 App 私有目录里，卸载即丢。
    // 这把钥匙只用于自用 debug 包，口令是公开的 "android"，谈不上秘密。
    signingConfigs {
        create("fixedDebug") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
        release {
            isMinifyEnabled = false
            // release 也先用这把钥匙签，免得又出现装不上的情况；真要发布再换正式证书
            signingConfig = signingConfigs.getByName("fixedDebug")
        }
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
