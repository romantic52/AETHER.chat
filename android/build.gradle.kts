plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("com.google.devtools.ksp") version "1.9.22-1.0.17" apply false
}
// Каталог сборки можно увести за пределы синхронизируемой папки:
//   AETHER_BUILD_DIR=/tmp/aether-android ./gradlew assembleDebug
// iCloud, приглядывая за рабочим столом, плодит конфликтные копии вида
// «файл 2.xml» прямо во время сборки, и Gradle падает на проверке имён.
// Без переменной поведение прежнее — на чужих машинах ничего не меняется.
System.getenv("AETHER_BUILD_DIR")?.let { external ->
    subprojects {
        layout.buildDirectory.set(File(external, name))
    }
}
