import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pocketforge.ai"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketforge.ai"
        minSdk = 26
        targetSdk = 34
        versionCode = 22
        versionName = "1.19.0"
        vectorDrawables { useSupportLibrary = true }
    }

    // 固定签名：keystore/pocketforge.jks（不入仓库；CI 用 Secret 还原），口令来自 keystore.properties 或环境变量
    val releaseStoreFile = rootProject.file("keystore/pocketforge.jks")
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val storePass = System.getenv("PF_KEYSTORE_PASSWORD") ?: ksProps.getProperty("storePassword") ?: ""
    val keyPass = System.getenv("PF_KEY_PASSWORD") ?: ksProps.getProperty("keyPassword") ?: storePass
    val keyAliasName = System.getenv("PF_KEY_ALIAS") ?: ksProps.getProperty("keyAlias") ?: "androiddebugkey"

    signingConfigs {
        create("release") {
            if (releaseStoreFile.exists() && storePass.isNotEmpty()) {
                storeFile = releaseStoreFile
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseStoreFile.exists() && storePass.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.mwiede:jsch:0.2.20")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
