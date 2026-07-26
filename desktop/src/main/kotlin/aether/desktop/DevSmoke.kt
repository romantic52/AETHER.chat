package aether.desktop

import aether.desktop.auth.AuthRepository
import aether.desktop.data.DesktopPrefs
import aether.desktop.data.Natives
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Headless-проверка авторизации без UI: регистрация/логин/автовход по токену.
 * Запуск: .\gradlew.bat smoke [--args="--seed"]
 *  - без аргументов: всё во временной директории, реальные секреты не трогает;
 *  - --seed: сохраняет сессию в настоящий %APPDATA%\Aether (для проверки
 *    автовхода в UI), пользователь desksmoke1.
 */
fun main(args: Array<String>) {
    Natives.init()
    val server = System.getenv("AETHER_SMOKE_SERVER") ?: "http://127.0.0.1:8765"
    val seed = "--seed" in args
    val dir = if (seed) DesktopPrefs.defaultDir() else File(System.getProperty("java.io.tmpdir"), "aether-smoke")
    val prefs = DesktopPrefs(dir)
    val auth = AuthRepository(prefs)
    val user = "desksmoke1"
    val pass = "desk-smoke-pass-1"

    runBlocking {
        val account = try {
            auth.register(server, user, pass)
        } catch (e: Exception) {
            println("register: ${e.message} — пробую логин")
            auth.login(server, user, pass, null)
        }
        println("LOGIN_OK user=${account.username} token_len=${account.token.length}")

        val restored = auth.restore()
        checkNotNull(restored) { "restore() вернул null — автовход сломан" }
        println("RESTORE_OK user=${restored.username}")

        if (!seed) {
            auth.logout(restored)
            check(auth.restore() == null) { "после logout сессия не должна восстанавливаться" }
            println("LOGOUT_OK")
            dir.deleteRecursively()
        }
        println("AUTH_SMOKE_OK seed=$seed")
    }
}
