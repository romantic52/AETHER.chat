package aether.desktop

import aether.desktop.data.DesktopPrefs
import aether.desktop.data.Natives
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Живой headless-клиент для проверки против Android-эмулятора:
 * входит как desksmoke1, шлёт peer'у текст и фото, затем 3 минуты печатает
 * входящие от peer'а строками RECV:. Запуск:
 *   .\gradlew.bat peersmoke --args="droidx1"
 */
fun main(args: Array<String>) {
    Natives.init()
    val peer = args.firstOrNull { !it.startsWith("--") } ?: error("Укажите peer id")
    val server = System.getenv("AETHER_SMOKE_SERVER") ?: "http://127.0.0.1:8765"
    val user = "desksmoke1"
    val pass = "desk-smoke-pass-1"

    val prefs = DesktopPrefs(DesktopPrefs.defaultDir())
    val auth = aether.desktop.auth.AuthRepository(prefs)

    runBlocking {
        val account = try {
            auth.login(server, user, pass, null)
        } catch (e: Exception) {
            auth.register(server, user, pass)
        }
        val session = AppSession.create(account, prefs)
        println("PEERSMOKE_READY user=$user device=${session.repository.myDeviceId()} -> $peer")

        session.repository.enqueueText(peer, "привет с десктопа 👋", null, null)
        println("SENT_TEXT")

        if ("--no-photo" !in args) {
            val png = File.createTempFile("aether_probe", ".png")
            val image = java.awt.image.BufferedImage(320, 200, java.awt.image.BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                color = java.awt.Color(0x33, 0x90, 0xEC)
                fillRect(0, 0, 320, 200)
                color = java.awt.Color.WHITE
                font = java.awt.Font("Arial", java.awt.Font.BOLD, 28)
                drawString("AETHER DESKTOP", 30, 105)
                dispose()
            }
            javax.imageio.ImageIO.write(image, "png", png)
            val err = session.repository.sendMedia(peer, listOf(png), "фото с десктопа")
            println(if (err == null) "SENT_PHOTO" else "PHOTO_ERROR: $err")
            png.delete()
        }

        val seen = mutableSetOf<String>()
        val deadline = System.currentTimeMillis() + 180_000
        while (System.currentTimeMillis() < deadline) {
            for (m in session.store.getMessagesForPeerOnce(peer)) {
                if (!m.isOut && seen.add(m.msgId)) {
                    println("RECV: ${m.text.take(200)}")
                }
            }
            val alive = session.api.heartbeat()
            if (!alive) {
                println("SESSION_DEAD")
                break
            }
            delay(2_000)
        }
        session.close()
        println("PEERSMOKE_DONE")
    }
    kotlin.system.exitProcess(0)
}
