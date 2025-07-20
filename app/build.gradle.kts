import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") // Jetpack Compose を使用する場合に必要
    id("com.google.gms.google-services")      // Firebase や AdMob などの Google サービス連携に必要
}

// local.properties を読み込む
val localProps = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}


// local.properties から APIキーや署名情報を取得
val mapsApiKey = localProps.getProperty("MAPS_API_KEY") ?: ""
val admobAppId = localProps.getProperty("ADMOB_APP_ID") ?: ""
val admobNativeId = localProps.getProperty("ADMOB_NATIVE_ID") ?: ""
val admobRewardId = localProps.getProperty("ADMOB_REWARD_ID") ?: ""
val orbisJsonUrl = localProps.getProperty("ORBIS_JSON_URL") ?: ""

// keystore 関連情報（署名付き AAB 用）
val keystoreFile = localProps.getProperty("KEYSTORE_FILE")?.let { file(it) }
val keystorePassword = localProps.getProperty("KEYSTORE_PASSWORD") ?: ""
val keyAlias = localProps.getProperty("KEY_ALIAS") ?: ""
val keyPassword = localProps.getProperty("KEY_PASSWORD") ?: ""

android {
    namespace = "com.ykun.orbisalert"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ykun.orbisalert"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.7"

        // Java 8以上のAPIを使用する場合に必要（マルチDEX対応）
        multiDexEnabled = true

        // AndroidManifest.xml 内で参照できる文字列リソースを定義
        // 例: android:value="@string/google_maps_key" という形で利用可能
        resValue("string", "google_maps_key", mapsApiKey)
        resValue("string", "admob_app_id", admobAppId)
        resValue("string", "admob_native_id", admobNativeId)
        resValue("string", "admob_reward_id", admobRewardId)
        resValue("string", "orbis_json_url", orbisJsonUrl)
    }

    // 署名付きビルド設定（Playにアップロードする際に必須）
    signingConfigs {
        create("release") {
            storeFile = keystoreFile
            storePassword = keystorePassword
            keyAlias = keyAlias
            keyPassword = keyPassword
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true // 難読化
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // Java 言語バージョンの設定
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Kotlin の JVM バージョン
    kotlinOptions {
        jvmTarget = "17"
    }

    // Jetpack Compose を使う場合は true
    buildFeatures {
        compose = true
    }
}

dependencies {
    // 基本UIライブラリ
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")

    // Jetpack Compose 関連
    implementation("androidx.compose.ui:ui:1.6.7")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.7")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Google Maps SDK（地図表示用）
    implementation("com.google.android.gms:play-services-maps:18.2.0")

    // 位置情報の取得API（FusedLocationProviderClientなど）
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // Google Maps Utility Library（クラスタリングなど）
    implementation("com.google.maps.android:android-maps-utils:3.4.0")

    // WebView 拡張（任意、WebKitの細かな制御用）
    implementation("androidx.webkit:webkit:1.9.0")

    // AdMob 広告表示（ネイティブ・報酬型広告など）
    implementation("com.google.android.gms:play-services-ads:24.4.0")

    // Import the Firebase BoM
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-analytics")
}
