# (#A5) Правила R8 для release-сборки.
# Принцип: сохраняем только то, что грузится через рефлексию/JNI —
# остальное R8 может смело выкидывать и оптимизировать.

# --- libsodium (lazysodium + JNA): нативные вызовы через рефлексию ---
-keep class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-dontwarn java.awt.*

# --- WebRTC: JNI-биндинги ---
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# --- OkHttp/Okio: официальные dontwarn ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Moshi (kotlin-reflect адаптеры) ---
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonQualifier @interface *
-dontwarn org.jetbrains.annotations.**

# --- Room: entity/dao генерируются, но имена колонок через рефлексию не идут.
# Достаточно стандартных правил из библиотеки (consumer rules), ничего не добавляем.

# --- Kotlin coroutines debug metadata ---
-dontwarn kotlinx.coroutines.debug.**

# --- uCrop ---
-dontwarn com.yalantis.ucrop.**

# Сохранить номера строк для читаемых стектрейсов в крашах
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
