package org.groktest.securemessenger.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Прямая доставка текста по Bluetooth LE — резервный транспорт для мест
 * без интернета.
 *
 * Рукопожатие обходится БЕЗ сервера, иначе весь смысл терялся бы: собеседник
 * сам отдаёт префикс-связку по GATT, из неё поднимается Olm-сессия. Криптография
 * тут не своя — та же, что в сети; этот слой только возит байты.
 *
 * Защита от подмены знакомого лежит на вызывающем: связка проходит через
 * то же закрепление ключа, что и серверная. Если ключ не совпал с
 * запомненным, отправка обязана сорваться, а не «довериться потому что рядом».
 *
 * Только текст. Голосовые и картинки по GATT ползут минутами — под них нужен
 * L2CAP, это отдельная работа.
 */
class BleTextTransport(
    private val context: Context,
    private val myId: String,
    /** Наша префикс-связка для того, кто к нам подключился. */
    private val ownPrekey: suspend () -> Prekey,
    /** Запечатать текст для устройства собеседника по его связке. */
    private val seal: suspend (peerId: String, peer: Prekey, wire: String) -> String,
    /** Принять расшифрованный конверт — дальше обычный путь входящих. */
    private val deliver: suspend (senderId: String, envelopeJson: String) -> Unit,
) {
    data class Prekey(
        val userId: String,
        val deviceId: String,
        val identityKeyB64: String,
        val oneTimeKeyB64: String,
    )

    class NotReachableException(peerId: String) :
        IllegalStateException("Собеседник $peerId не отвечает по Bluetooth")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: BluetoothGattServer? = null

    /** Накопители входящих кусков: устройство → собранные байты. */
    private val inbound = ConcurrentHashMap<String, StringBuilder>()

    private val manager: BluetoothManager?
        get() = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    // ------------------------------------------------------------------
    // Приём: GATT-сервер
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startServer(): Boolean {
        if (server != null) return true
        val gattServer = manager?.openGattServer(context, serverCallback) ?: return false
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                CHAR_PREKEY,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                CHAR_INBOX,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
        )
        gattServer.addService(service)
        server = gattServer
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        runCatching { server?.close() }
        server = null
        inbound.clear()
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?,
        ) {
            device ?: return
            if (characteristic?.uuid != CHAR_PREKEY) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            scope.launch {
                val payload = runCatching { encodePrekey(ownPrekey()) }.getOrNull()
                if (payload == null) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                    return@launch
                }
                val bytes = payload.toByteArray(Charsets.UTF_8)
                // Длинную связку система забирает по кускам, отдавая offset.
                val slice = if (offset >= bytes.size) ByteArray(0) else bytes.copyOfRange(offset, bytes.size)
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            device ?: return
            if (characteristic?.uuid != CHAR_INBOX || value == null || value.isEmpty()) {
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }
            val last = value[0] == FRAME_LAST
            val chunk = String(value, 1, value.size - 1, Charsets.UTF_8)
            val buffer = inbound.getOrPut(device.address) { StringBuilder() }
            if (buffer.length + chunk.length > MAX_MESSAGE_CHARS) {
                // Чужой может лить бесконечно: обрываем, а не растём в памяти.
                inbound.remove(device.address)
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }
            buffer.append(chunk)
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            if (!last) return

            val whole = buffer.toString()
            inbound.remove(device.address)
            scope.launch {
                runCatching {
                    val json = org.json.JSONObject(whole)
                    val senderId = json.getString("from")
                    deliver(senderId, json.getJSONObject("envelope").toString())
                }.onFailure {
                    android.util.Log.w("BleTransport", "Битый конверт по Bluetooth", it)
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED && device != null) {
                inbound.remove(device.address)
            }
        }
    }

    // ------------------------------------------------------------------
    // Отправка: GATT-клиент
    // ------------------------------------------------------------------

    /** Подключиться к устройству, забрать связку, запечатать и отдать текст. */
    @SuppressLint("MissingPermission")
    suspend fun send(peerId: String, address: String, wire: String) {
        val adapter = manager?.adapter ?: throw NotReachableException(peerId)
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: throw NotReachableException(peerId)

        val connected = CompletableDeferred<BluetoothGatt>()
        val servicesReady = CompletableDeferred<Unit>()
        val prekeyRead = CompletableDeferred<String>()
        val writeAck = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (!connected.isCompleted) connected.complete(gatt)
                        gatt.requestMtu(MTU)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (!connected.isCompleted) {
                            connected.completeExceptionally(NotReachableException(peerId))
                        }
                        if (!servicesReady.isCompleted) {
                            servicesReady.completeExceptionally(NotReachableException(peerId))
                        }
                        if (!prekeyRead.isCompleted) {
                            prekeyRead.completeExceptionally(NotReachableException(peerId))
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    servicesReady.complete(Unit)
                } else {
                    servicesReady.completeExceptionally(NotReachableException(peerId))
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (characteristic.uuid != CHAR_PREKEY) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    prekeyRead.complete(characteristic.value?.toString(Charsets.UTF_8).orEmpty())
                } else {
                    prekeyRead.completeExceptionally(NotReachableException(peerId))
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                writeAck.offer(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        val gatt = device.connectGatt(context, false, callback)
            ?: throw NotReachableException(peerId)
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
            withTimeout(CONNECT_TIMEOUT_MS) { servicesReady.await() }

            val service = gatt.getService(SERVICE_UUID) ?: throw NotReachableException(peerId)
            val prekeyChar = service.getCharacteristic(CHAR_PREKEY)
                ?: throw NotReachableException(peerId)
            @Suppress("DEPRECATION")
            if (!gatt.readCharacteristic(prekeyChar)) throw NotReachableException(peerId)
            val peer = decodePrekey(withTimeout(IO_TIMEOUT_MS) { prekeyRead.await() })

            // Запечатывание — снаружи: закрепление ключа и Olm живут в репозитории.
            val envelope = seal(peerId, peer, wire)
            val payload = org.json.JSONObject()
                .put("from", myId)
                .put("envelope", org.json.JSONObject(envelope))
                .toString()

            val inboxChar = service.getCharacteristic(CHAR_INBOX)
                ?: throw NotReachableException(peerId)
            writeChunked(gatt, inboxChar, payload, writeAck)
        } finally {
            runCatching { gatt.close() }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun writeChunked(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: String,
        ack: java.util.concurrent.ArrayBlockingQueue<Boolean>,
    ) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        // Один байт уходит под флаг «последний кусок», остальное — данные.
        val room = MTU - ATT_OVERHEAD - 1
        var offset = 0
        while (offset < bytes.size) {
            val size = minOf(room, bytes.size - offset)
            val last = offset + size >= bytes.size
            val frame = ByteArray(size + 1)
            frame[0] = if (last) FRAME_LAST else FRAME_MORE
            System.arraycopy(bytes, offset, frame, 1, size)

            ack.clear()
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.value = frame
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                gatt.writeCharacteristic(characteristic)
            }
            if (!ok) throw NotReachableException("bluetooth")
            // Следующий кусок только после подтверждения: стек GATT не терпит
            // параллельных записей и молча теряет их.
            val confirmed = ack.poll(IO_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (confirmed != true) throw NotReachableException("bluetooth")
            offset += size
        }
    }

    private fun encodePrekey(prekey: Prekey): String = org.json.JSONObject()
        .put("user_id", prekey.userId)
        .put("device_id", prekey.deviceId)
        .put("identity_key_b64", prekey.identityKeyB64)
        .put("one_time_key_b64", prekey.oneTimeKeyB64)
        .toString()

    private fun decodePrekey(raw: String): Prekey {
        val json = org.json.JSONObject(raw)
        return Prekey(
            userId = json.getString("user_id"),
            deviceId = json.getString("device_id"),
            identityKeyB64 = json.getString("identity_key_b64"),
            oneTimeKeyB64 = json.getString("one_time_key_b64"),
        )
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("ae7a0001-0000-4000-8000-000000000001")
        val CHAR_PREKEY: UUID = UUID.fromString("ae7a0002-0000-4000-8000-000000000001")
        val CHAR_INBOX: UUID = UUID.fromString("ae7a0003-0000-4000-8000-000000000001")

        private const val MTU = 517
        private const val ATT_OVERHEAD = 3
        private const val FRAME_MORE: Byte = 0x01
        private const val FRAME_LAST: Byte = 0x00

        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val IO_TIMEOUT_MS = 10_000L

        /** Текст и только текст: всё крупное обязано ехать другим транспортом. */
        private const val MAX_MESSAGE_CHARS = 64 * 1024
    }
}
