package org.groktest.securemessenger.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.groktest.securemessenger.api.RelayApi
import org.groktest.securemessenger.data.CoreStore
import org.groktest.securemessenger.data.ChatEntity
import org.groktest.securemessenger.data.MessageEntity

/**
 * Отправка отложенного сообщения.
 * Конверт шифруется ЗАРАНЕЕ (при планировании), поэтому worker не хранит
 * ни приватных ключей, ни plaintext-протокола — только готовый ciphertext
 * и текст для локального отображения.
 */
class ScheduledMessageWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val serverUrl = inputData.getString(KEY_SERVER_URL) ?: return Result.failure()
        val token = inputData.getString(KEY_TOKEN) ?: return Result.failure()
        val myId = inputData.getString(KEY_MY_ID) ?: return Result.failure()
        val peerId = inputData.getString(KEY_PEER_ID) ?: return Result.failure()
        val text = inputData.getString(KEY_TEXT) ?: return Result.failure()
        // (#A3) Конверт целиком (JSON): личный box или групповой is_group-конверт
        val envelopeJson = inputData.getString(KEY_ENVELOPE_JSON) ?: return Result.failure()

        return try {
            val api = RelayApi(serverUrl)
            api.token = token
            val envObj = org.json.JSONObject(envelopeJson)
            val envelope = mutableMapOf<String, Any>()
            for (k in envObj.keys()) envelope[k] = envObj.get(k)
            // (#A2) id воркера стабилен между ретраями → сервер не создаст дубликат
            val serverMsgId = api.sendMessage(myId, peerId, envelope, clientMsgId = id.toString())

            val store = CoreStore.create(applicationContext, myId)
            if (store.getChat(peerId) == null) {
                val chatType = if (peerId.startsWith("channel_")) 2 else if (peerId.startsWith("group_")) 1 else 0
                store.insertChat(ChatEntity(peerId = peerId, name = peerId, type = chatType))
            }
            store.insertMessage(
                MessageEntity(
                    msgId = serverMsgId,
                    peerId = peerId,
                    isOut = true,
                    text = text,
                    timestamp = System.currentTimeMillis(),
                    status = 1
                )
            )
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_TOKEN = "token"
        const val KEY_MY_ID = "my_id"
        const val KEY_PEER_ID = "peer_id"
        const val KEY_TEXT = "text"
        const val KEY_ENVELOPE_JSON = "envelope_json"
    }
}
