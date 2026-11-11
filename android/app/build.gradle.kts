import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 签名配置来源（优先级从高到低）：
 *   1. Gradle 属性 -Phishare.keystore=<keystore.properties 路径>
 *   2. 环境变量 HISHARE_KEYSTORE_PROPERTIES
 *   3. 团队公共目录 /opt/team/keys/hishare/keystore.properties（默认）
 * 找不到时 release 仍可构建，但产物是未签名包。
 */
val keystorePropsFile = file(
    providers.gradleProperty("hishare.keystore").orNull
        ?: System.getenv("HISHARE_KEYSTORE_PROPERTIES")
        ?: "/opt/team/keys/hishare/keystore.properties"
)
val keystoreProps = Properties()
val keystoreAvailable = keystorePropsFile.exists() && run {
    keystorePropsFile.inputStream().use { keystoreProps.load(it) }
    keystoreProps.getProperty("storeFile")?.let { file(it).exists() } ?: false
}

android {
    namespace = "com.hishare.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hishare.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreAvailable) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
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
        viewBinding = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.version")
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.0.1")
}
