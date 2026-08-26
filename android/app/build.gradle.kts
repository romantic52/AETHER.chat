import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun String.asBuildConfigString() = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "org.groktest.securemessenger"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.groktest.securemessenger"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField(
            "String",
            "TURN_HOST",
            localProperties.getProperty("aether.turnHost", "").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "TURN_USERNAME",
            localProperties.getProperty("aether.turnUsername", "").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "TURN_CREDENTIAL",
            localProperties.getProperty("aether.turnCredential", "").asBuildConfigString()
        )
    }

    signingConfigs {
        getByName("debug") {
            val localDebugKeystore = rootProject.file("debug.keystore")
            if (localDebugKeystore.exists()) {
                storeFile = localDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // (#A5) R8 + shrink: релиз в разы плавнее и меньше debug-сборки.
            // Debug-Compose не для оценки производительности!
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Подпись debug-ключом, чтобы APK ставился сразу.
            // Для публикации замените на собственный keystore.
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Локальное хранилище теперь в ядре (SQLite в Rust, см. CoreStore) — Room убран.

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Блокировка приложения отпечатком/лицом
    implementation("androidx.biometric:biometric:1.1.0")
    // Жидкое стекло: настоящий backdrop-blur (RenderEffect). Версия под Compose 1.6.
    implementation("dev.chrisbanes.haze:haze:0.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("com.squareup.moshi:moshi:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    // JNA — рантайм для UniFFI-биндингов Rust-ядра (sm_core).
    // (lazysodium удалён: crypto_box/AES считает Rust-ядро, см. core/ и E2ECrypto.)
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    
    // WebRTC for Calling
    implementation("io.getstream:stream-webrtc-android:1.1.1")
    
    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")
    
    // uCrop for image cropping
    implementation("com.github.yalantis:ucrop:2.2.8")
    
    // WorkManager — отложенные сообщения
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // CameraX — запись видео-кружков внутри приложения (как в Telegram)
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Сканирование QR привязки устройства (оффлайн-модель в APK)
    implementation("com.google.mlkit:barcode-scanning:17.2.0")
}
