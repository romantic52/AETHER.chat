package aether.desktop.data

import java.io.File

/**
 * Поиск sm_core.dll до первого обращения к JNA: биндинги uniffi ищут либу
 * по имени "sm_core" через jna.library.path.
 */
object Natives {
    fun init() {
        val candidates = listOfNotNull(
            System.getenv("AETHER_NATIVES"),
            // Установленный дистрибутив: ресурсы приложения рядом с exe.
            System.getProperty("compose.application.resources.dir"),
            "natives",
            "desktop/natives",
            "../core/target/release",
            "core/target/release",
        )
        val dir = candidates
            .map(::File)
            .firstOrNull { File(it, "sm_core.dll").isFile }
            ?: error(
                "sm_core.dll не найдена. Запустите core\\build_windows.ps1 " +
                    "или задайте переменную окружения AETHER_NATIVES."
            )
        val existing = System.getProperty("jna.library.path")
        val path = dir.absolutePath + if (existing.isNullOrBlank()) "" else File.pathSeparator + existing
        System.setProperty("jna.library.path", path)
    }
}
