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

    // CI 用環境變數提供簽章金鑰（GitHub Secrets）；本機沒設就用 debug 金鑰
    val keystore = System.getenv("SIGNING_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    // 詞庫（assets/scd/*.scd）不壓縮進 APK，執行時直接 mmap，不必先解出來
    androidResources {
        noCompress += "scd"
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
    // 詞庫線上更新：每天在背景檢查一次
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // 側載安裝時也套用 Baseline Profile（src/main/baseline-prof.txt），選字的程式預先編譯成機器碼
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // 自動填入建議（簡訊驗證碼、密碼）顯示在鍵盤上：Inline Suggestions 的樣式（Android 11 以上）
    implementation("androidx.autofill:autofill:1.1.0")

    testImplementation("junit:junit:4.13.2")
    // 單元測試裡 android.jar 的 org.json 只是空殼，換成真的實作
    testImplementation("org.json:json:20240303")
    // SCD 與舊版 SQLite 詞庫逐筆比對（ScdSqliteEquivalenceTest；有給 SQLite 詞庫路徑才跑）
    testImplementation("org.xerial:sqlite-jdbc:3.45.1.0")
}

tasks.withType<Test>().configureEach {
    // -Pscd.sqlite=<解壓後的 scorpio_dict.db> 時跑 SCD／SQLite 逐筆比對
    (project.findProperty("scd.sqlite") as String?)?.let { systemProperty("scd.sqlite", it) }
}
