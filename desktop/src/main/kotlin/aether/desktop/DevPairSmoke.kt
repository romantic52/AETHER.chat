package aether.desktop

import aether.desktop.auth.AuthRepository
import aether.desktop.data.DesktopPrefs
import aether.desktop.data.Natives
import aether.desktop.pairing.PairingClient
import aether.desktop.pairing.QrRenderer
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking

/**
 * Десктопная сторона QR-привязки без UI: печатает QR в файл и ждёт
 * подтверждения с телефона, затем расшифровывает bundle и поднимает сессию.
 * Тем же кодом (PairingClient/QrRenderer), что и экран входа.
 *
 * Запуск: .\gradlew.bat pairsmoke --args="<путь-к-qr.png> [--keep]"
 *   --keep: оставить сессию в %APPDATA%\Aether (для запуска UI после привязки)
 */
fun main(args: Array<String>) {
    Natives.init()
    val server = System.getenv("AETHER_SMOKE_SERVER") ?: "http://127.0.0.1:8765"
    val out = File(args.firstOrNull { !it.startsWith("--") } ?: "pairing_qr.png")
    val keep = "--keep" in args

    val prefs = if (keep) DesktopPrefs(DesktopPrefs.defaultDir())
    else DesktopPrefs(File(System.getProperty("java.io.tmpdir"), "aether-pairsmoke"))
    val auth = AuthRepository(prefs)
    val client = PairingClient(server)

    runBlocking {
        val started = client.start()
        ImageIO.write(QrRenderer.renderAwt(started.qrPayload, 480), "png", out)
        println("QR_FILE=${out.absolutePath}")
        println("QR_PAYLOAD=${started.qrPayload}")
        println("WAITING_APPROVAL ttl=${started.expiresIn}s")

        val deadline = System.currentTimeMillis() + started.expiresIn * 1000L
        var approved: PairingClient.Approved? = null
        while (System.currentTimeMillis() < deadline && approved == null) {
            approved = try {
                client.poll(started)
            } catch (e: PairingClient.PairingExpired) {
                println("PAIRING_EXPIRED")
                break
            }
        }
        val result = approved ?: run {
            println("PAIRING_TIMEOUT")
            kotlin.system.exitProcess(2)
        }
        println("APPROVED user=${result.userId} device=${result.deviceId}")

        val bundle = client.openBundle(result)
        client.forget()
        check(bundle.privateB64.isNotBlank() && bundle.publicB64.isNotBlank()) { "пустой bundle" }
        println("BUNDLE_DECRYPTED pub=${bundle.publicB64.take(12)}…")

        val account = auth.adoptPairedSession(
            server = server,
            userId = result.userId,
            token = result.sessionToken,
            deviceId = result.deviceId,
            keys = DesktopPrefs.AccountKeys(bundle.publicB64, bundle.privateB64),
        )
        println("SESSION_ADOPTED user=${account.username}")

        // Ключ должен совпасть с аккаунтным паблик-ключом на сервере:
        // иначе группы (обёрнутые на аккаунтный ключ) не расшифруются.
        val serverPub = account.api.getPublicKey(account.username)
        check(serverPub == bundle.publicB64) { "паблик из bundle != серверного: $serverPub" }
        println("ACCOUNT_KEY_MATCHES")

        val session = AppSession.create(account, prefs)
        check(session.repository.myDeviceId() == result.deviceId) {
            "device_id должен быть выданным сервером: ${session.repository.myDeviceId()} != ${result.deviceId}"
        }
        println("DEVICE_ID_OK ${result.deviceId}")

        // Переписка с привязанного устройства: --peer <id> шлёт сообщение и
        // 3 минуты печатает входящие (для живой проверки в обе стороны).
        val peer = args.dropWhile { it != "--peer" }.drop(1).firstOrNull()
        if (peer != null) {
            session.repository.enqueueText(peer, "привет с привязанного десктопа", null, null)
            println("SENT_TO_PEER $peer")
            val seen = mutableSetOf<String>()
            val until = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < until) {
                for (m in session.store.getMessagesForPeerOnce(peer)) {
                    if (!m.isOut && seen.add(m.msgId)) println("RECV: ${m.text.take(160)}")
                }
                kotlinx.coroutines.delay(2_000)
            }
        }

        if (!keep) {
            session.close()
            prefs.clearSession()
        }
        println("PAIR_SMOKE_OK")
    }
    kotlin.system.exitProcess(0)
}
