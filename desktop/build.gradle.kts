import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // Обычная JVM-JNA (НЕ @aar): биндинги uniffi грузят sm_core.dll через неё.
    implementation("net.java.dev.jna:jna:5.14.0")
    // Crypt32Util (DPAPI) для шифрования секретов под Windows.
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
    // Рендер QR-кода привязки (кодирование, оффлайн).
    implementation("com.google.zxing:core:3.5.3")
}

kotlin {
    jvmToolchain(21)
}

// Headless-smoke авторизации (DevSmoke.kt): .\gradlew.bat smoke
tasks.register<JavaExec>("smoke") {
    group = "verification"
    mainClass.set("aether.desktop.DevSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Headless-smoke мессаджинга (DevMsgSmoke.kt): .\gradlew.bat msgsmoke
tasks.register<JavaExec>("msgsmoke") {
    group = "verification"
    mainClass.set("aether.desktop.DevMsgSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Десктопная сторона QR-привязки без UI (DevPairSmoke.kt): .\gradlew.bat pairsmoke --args="<qr.png>"
tasks.register<JavaExec>("pairsmoke") {
    group = "verification"
    mainClass.set("aether.desktop.DevPairSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Живой headless-клиент против эмулятора (DevPeerSmoke.kt): .\gradlew.bat peersmoke --args="<peer>"
tasks.register<JavaExec>("peersmoke") {
    group = "verification"
    mainClass.set("aether.desktop.DevPeerSmokeKt")
    classpath = sourceSets["main"].runtimeClasspath
}

compose.desktop {
    application {
        mainClass = "aether.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Aether"
            packageVersion = "1.0.0"
            windows {
                menuGroup = "Aether"
            }
        }
    }
}
