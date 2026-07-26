package aether.desktop

import aether.desktop.data.DesktopPrefs
import aether.desktop.data.Natives
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Headless-проверка мессаджинга: два свежих аккаунта на локальном сервере,
 * личка (Olm multi-device fanout), квитанции delivered, группа общим ключом,
 * реакция, редактирование, удаление «у всех».
 * Запуск: .\gradlew.bat msgsmoke
 */
fun main() {
    Natives.init()
    val server = System.getenv("AETHER_SMOKE_SERVER") ?: "http://127.0.0.1:8765"
    val stamp = (System.currentTimeMillis() % 1_000_000).toString()
    val userA = "mdska$stamp"
    val userB = "mdskb$stamp"
    val pass = "msg-smoke-pass-1"

    val tmpRoot = File(System.getProperty("java.io.tmpdir"), "aether-msgsmoke-$stamp").apply { mkdirs() }

    fun sessionFor(user: String): AppSession {
        val prefs = DesktopPrefs(File(tmpRoot, user))
        val auth = aether.desktop.auth.AuthRepository(prefs)
        val account = runBlocking { auth.register(server, user, pass) }
        return AppSession.create(account, prefs)
    }

    runBlocking {
        val a = sessionFor(userA)
        val b = sessionFor(userB)
        println("SESSIONS_OK a=$userA b=$userB deviceA=${a.repository.myDeviceId()} deviceB=${b.repository.myDeviceId()}")
        check(a.repository.myDeviceId().startsWith("desktop-")) { "device_id обязан быть desktop-*" }

        suspend fun awaitOrFail(what: String, timeoutMs: Long = 45_000, check: suspend () -> Boolean) {
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < timeoutMs) {
                if (check()) return
                delay(1_000)
            }
            throw IllegalStateException("Таймаут: $what")
        }

        // 1. Личка A -> B
        a.repository.enqueueText(userB, "привет от A", null, null)
        awaitOrFail("B получил личное сообщение") {
            b.store.getMessagesForPeerOnce(userA).any { it.text == "привет от A" && !it.isOut }
        }
        println("DIRECT_A_TO_B_OK")

        // 2. Ответ B -> A
        b.repository.enqueueText(userA, "привет от B", null, null)
        awaitOrFail("A получил ответ") {
            a.store.getMessagesForPeerOnce(userB).any { it.text == "привет от B" && !it.isOut }
        }
        println("DIRECT_B_TO_A_OK")

        // 3. Квитанция delivered: статус исходящего у A должен стать >= 2
        awaitOrFail("A увидел delivered (✓✓)") {
            a.store.getMessagesForPeerOnce(userB).any { it.isOut && it.status >= 2 }
        }
        println("DELIVERED_OK")

        // 4. Группа: A создаёт, добавляет B, шлёт
        val gid = "group_" + java.util.UUID.randomUUID().toString().take(8)
        val keyB64 = a.repository.newGroupKeyB64()
        a.api.createGroup(gid, "Смоук-группа", "", false, a.repository.wrapGroupKeyFor(userA, keyB64))
        a.api.addGroupMember(gid, userB, a.repository.wrapGroupKeyFor(userB, keyB64))
        a.repository.registerCreatedGroup(gid, "Смоук-группа", keyB64, false)
        a.repository.enqueueText(gid, "групповое от A", null, null)
        awaitOrFail("B получил групповое") {
            b.store.getMessagesForPeerOnce(gid).any { it.text == "групповое от A" && !it.isOut }
        }
        println("GROUP_OK")

        // 5. Реакция B на сообщение A
        val target = b.store.getMessagesForPeerOnce(userA).first { it.text == "привет от A" }
        b.repository.react(userA, target.msgId, "🔥")
        awaitOrFail("A увидел реакцию") {
            a.store.getMessagesForPeerOnce(userB)
                .firstOrNull { it.isOut && it.text == "привет от A" }
                ?.reactions?.contains("🔥") == true
        }
        println("REACTION_OK")

        // 6. Редактирование: A правит своё сообщение, B видит новый текст
        val mine = a.store.getMessagesForPeerOnce(userB).first { it.isOut && it.text == "привет от A" }
        a.repository.editMessage(userB, mine.msgId, "привет от A (изм.)")
        awaitOrFail("B увидел редактирование") {
            b.store.getMessagesForPeerOnce(userA).any { it.text == "привет от A (изм.)" }
        }
        println("EDIT_OK")

        // 7. Удаление «у всех»
        a.repository.deleteForEveryone(userB, mine.msgId)
        awaitOrFail("у B сообщение удалено") {
            b.store.getMessagesForPeerOnce(userA).none { it.msgId == mine.msgId }
        }
        println("DELETE_OK")

        // 8. Медиа: A шлёт PNG, B скачивает и сверяет байты
        val png = File(tmpRoot, "probe.png")
        val image = java.awt.image.BufferedImage(64, 48, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply {
            color = java.awt.Color(0x33, 0x90, 0xEC)
            fillRect(0, 0, 64, 48)
            dispose()
        }
        javax.imageio.ImageIO.write(image, "png", png)
        val sendError = a.repository.sendMedia(userB, listOf(png), "медиа-проверка")
        check(sendError == null) { "sendMedia: $sendError" }
        awaitOrFail("B получил и расшифровал фото") {
            val incoming = b.store.getMessagesForPeerOnce(userA)
                .firstOrNull { !it.isOut && it.text.contains("\"media\"") && it.text.contains("медиа-проверка") }
            val file = incoming?.let { b.repository.downloadMedia(it.text) }
            file != null && file.readBytes().contentEquals(png.readBytes())
        }
        println("MEDIA_OK")

        a.close()
        b.close()
        tmpRoot.deleteRecursively()
        println("MSG_SMOKE_OK")
    }
    kotlin.system.exitProcess(0)
}
