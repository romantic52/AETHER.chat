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
    // Голосовые: Android и iOS пишут AAC в MP4, десктоп — MP3. SPI-декодеры
    // подключаются к javax.sound.sampled, поэтому проигрывание идёт внутри
    // приложения, а не системным плеером.
    implementation("com.tianscar.javasound:jaad:0.9.4")
    implementation("com.tianscar.javasound:javasound-mp3:1.9.8")
    // Веб пишет WebM/Opus, и SPI для него нет вовсе (хуже того, jaad ошибочно
    // забирает такой файл себе и падает). Демультиплексируем и декодируем сами;
    // обе библиотеки — чистая Java, потому что дистрибутив собирается
    // jpackage/jlink и нативные библиотеки в него не положить.
    implementation("org.jitsi:jebml:2.4.4-6-gb310849")
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    // jebml объявляет slf4j как provided и без него падает на инициализации
    // логгера; nop-биндинг заодно убирает предупреждение об отсутствии вывода.
    implementation("org.slf4j:slf4j-nop:2.0.16")
    // Запись голосовых: чистая Java-реализация LAME (MP3 играют все клиенты).
    implementation("de.sciss:jump3r:1.0.5")
}

kotlin {
    jvmToolchain(21)
}

// JNA прибита жёстко: через неё UniFFI грузит sm_core.dll и работает DPAPI.
// Транзитивный подъём версии от сторонних библиотек ломает и то, и другое.
configurations.all {
    resolutionStrategy {
        force("net.java.dev.jna:jna:5.14.0", "net.java.dev.jna:jna-platform:5.14.0")
    }
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

// Проверка декодеров голосовых (DevAudioSmoke.kt): .\gradlew.bat audiosmoke --args="<files>"
tasks.register<JavaExec>("audiosmoke") {
    group = "verification"
    mainClass.set("aether.desktop.DevAudioSmokeKt")
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
        // sm_core.dll обязана попасть в дистрибутив: без неё установленное
        // приложение падает на первом же вызове ядра (JNA не находит либу).
        // appResourcesRootDir кладёт natives/ рядом с exe, а jna.library.path
        // указывает на распакованный каталог ресурсов.
        nativeDistributions.appResourcesRootDir.set(project.layout.projectDirectory.dir("natives-dist"))
        jvmArgs("-Djna.library.path=\$APPDIR")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Aether"
            packageVersion = "1.0.0"
            // jlink собирает JRE только из перечисленных модулей: без них
            // установленное приложение падало с NoClassDefFoundError
            // (java.net.http — HTTP-клиент, jdk.crypto.ec — TLS к https-серверу,
            // jdk.unsupported — sun.misc.Unsafe для JNA).
            // jdk.localedata — иначе в собранном JRE только английская локаль
            // и даты в ленте выглядят как «27 Jul» вместо «27 июля».
            modules(
                "java.net.http",
                "java.naming",
                "java.sql",
                "jdk.crypto.ec",
                "jdk.localedata",
                "jdk.unsupported",
            )
            windows {
                menuGroup = "Aether"
            }
        }
    }
}

/** Раскладывает sm_core.dll в структуру appResourcesRootDir (windows-x64/). */
val prepareNatives by tasks.registering(Copy::class) {
    from(layout.projectDirectory.dir("natives")) { include("sm_core.dll") }
    into(layout.projectDirectory.dir("natives-dist/windows-x64"))
}

// prepareAppResources забирает содержимое natives-dist — она должна быть готова раньше.
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareNatives) }
