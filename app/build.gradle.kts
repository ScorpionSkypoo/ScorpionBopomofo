plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 版本號跟著 git 走：
//   versionName＝最近的 v* tag（例如 v1.2.0 → 1.2.0；tag 之後還有 commit 就是 1.2.0-3-gabc1234）
//   versionCode＝100＋commit 數（每次提交自動遞增；加 100 是因為舊版本號已經用到 59）
fun git(vararg args: String): String? = try {
    providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim().ifEmpty { null }
} catch (e: Exception) {
    null
}
val commitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0
val describe = git("describe", "--tags", "--match", "v*", "--always", "--dirty")?.removePrefix("v") ?: "dev"

android {
    namespace = "com.p3dro.scorpio.ime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.p3dro.scorpio.ime"
        minSdk = 26
        targetSdk = 34
        versionCode = 100 + commitCount
        versionName = describe

        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // 手寫辨識（離線；第一次使用時下載辨識模型）
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
