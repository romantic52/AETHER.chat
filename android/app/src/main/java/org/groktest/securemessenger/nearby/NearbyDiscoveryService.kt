package org.groktest.securemessenger.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Грубая близость. Bluetooth не даёт расстояния — только «сильнее/слабее». */
enum class Proximity(val title: String, val hint: String) {
    VERY_CLOSE("Очень близко", "около 1 м"),
    NEAR("Рядом", "несколько метров"),
    FAR("Недалеко", "в пределах комнаты"),
    DISTANT("Далеко", "на грани слышимости");

    companion object {
        fun of(rssi: Int): Proximity = when {
            rssi >= -55 -> VERY_CLOSE
            rssi >= -70 -> NEAR
            rssi >= -85 -> FAR
            else -> DISTANT
        }
    }
}

data class NearbyPeer(
    /** Идентификатор находки в пределах сеанса. НЕ идентичность человека. */
    val id: String,
    /** Опознан как знакомый: его ключ обнаружения у нас есть. */
    val known: Boolean,
    val identityId: String?,
    val rssi: Int,
    val lastSeen: Long,
) {
    val proximity: Proximity get() = Proximity.of(rssi)
}

/**
 * Обнаружение рядом по Bluetooth LE.
 *
 * Наружу уходит только вращающийся идентификатор эпохи (EDI), упакованный
 * в service UUID: тот, кто не знает ключа обнаружения, видит меняющийся
 * набор байтов и не может связать две находки в одно устройство.
 *
 * Сервис ТОЛЬКО обнаруживает. Рукопожатие и доставка по прямому каналу —
 * отдельная задача (этапы 6–7), и обещать их в интерфейсе нельзя.
 */
class NearbyDiscoveryService(private val context: Context) {

    private val privacy = NearbyPrivacy.get(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val peers: StateFlow<List<NearbyPeer>> = _peers.asStateFlow()

    private val _advertising = MutableStateFlow(false)
    val advertising: StateFlow<Boolean> = _advertising.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** identityId → ключ обнаружения. Только те, кто нам его сам отдал. */
    private var knownKeys: Map<String, String> = emptyMap()

    private var rotationJob: Job? = null
    private var pruneJob: Job? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val advertiser: BluetoothLeAdvertiser?
        get() = adapter?.takeIf { it.isEnabled }?.bluetoothLeAdvertiser

    fun start(knownKeys: Map<String, String>) {
        this.knownKeys = knownKeys
        if (!hasPermissions()) return
        startRotation()
        startScanning()
        startPruning()
    }

    fun stop() {
        rotationJob?.cancel(); rotationJob = null
        pruneJob?.cancel(); pruneJob = null
        stopAdvertising()
        stopScanning()
        _peers.value = emptyList()
    }

    // MARK: — объявление о себе

    private fun startRotation() {
        rotationJob?.cancel()
        rotationJob = scope.launch {
            while (true) {
                refreshAdvertisement()
                // Пересобираем чаще интервала ротации: объявление сменится
                // сразу на границе окна, а не спустя четверть часа.
                delay(60_000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshAdvertisement() {
        privacy.expireTemporaryVisibilityIfNeeded()
        if (!privacy.shouldAdvertise() || !hasPermission(advertisePermission)) {
            stopAdvertising()
            return
        }
        val beacon = runCatching {
            uniffi.sm_core.nearbyBuildBeacon(privacy.discoveryKey(), uniffi.sm_core.nearbyCurrentEpoch())
        }.getOrNull() ?: return
        val uuid = runCatching { uniffi.sm_core.nearbyBeaconToUuid(beacon) }.getOrNull() ?: return

        stopAdvertising()
        val le = advertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        // Имени устройства в пакете нет — оно свело бы на нет весь смысл
        // вращающегося идентификатора.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid.fromString(uuid))
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                _advertising.value = true
            }

            override fun onStartFailure(errorCode: Int) {
                _advertising.value = false
            }
        }
        advertiseCallback = callback
        runCatching { le.startAdvertising(settings, data, callback) }
            .onFailure { _advertising.value = false }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        val callback = advertiseCallback ?: return
        advertiseCallback = null
        _advertising.value = false
        if (hasPermission(advertisePermission)) {
            runCatching { advertiser?.stopAdvertising(callback) }
        }
    }

    // MARK: — поиск чужих маяков

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!hasPermission(scanPermission)) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        stopScanning()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result ?: return
                handle(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach(::handle)
            }

            override fun onScanFailed(errorCode: Int) {
                _scanning.value = false
            }
        }
        scanCallback = callback
        // Маска по первому байту UUID: наш неймспейс 0xAE, остальное не важно.
        // Чужие сервисы отсекает радиостек, а не мы — меньше пробуждений.
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(
                    ParcelUuid.fromString(NAMESPACE_UUID),
                    ParcelUuid.fromString(NAMESPACE_MASK),
                )
                .build()
        )
        runCatching { scanner.startScan(filters, settings, callback) }
            .onSuccess { _scanning.value = true }
            .onFailure { _scanning.value = false }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        val callback = scanCallback ?: return
        scanCallback = null
        _scanning.value = false
        if (hasPermission(scanPermission)) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        }
    }

    private fun handle(result: ScanResult) {
        val uuids = result.scanRecord?.serviceUuids ?: return
        val epoch = uniffi.sm_core.nearbyCurrentEpoch()
        for (parcel in uuids) {
            val beacon = runCatching {
                uniffi.sm_core.nearbyUuidToBeacon(parcel.uuid.toString())
            }.getOrNull() ?: continue
            if (!runCatching { uniffi.sm_core.nearbyIsBeacon(beacon) }.getOrDefault(false)) continue

            // Своё же объявление отражается обратно — его не показываем.
            val ownKey = privacy.discoveryKey()
            if (runCatching {
                    uniffi.sm_core.nearbyMatchBeacon(beacon, ownKey, epoch)
                }.getOrDefault(false)
            ) continue

            val identityId = knownKeys.entries.firstOrNull { (_, key) ->
                runCatching { uniffi.sm_core.nearbyMatchBeacon(beacon, key, epoch) }
                    .getOrDefault(false)
            }?.key

            upsert(parcel.uuid.toString(), identityId, result.rssi)
        }
    }

    private fun upsert(key: String, identityId: String?, rssi: Int) {
        val now = System.currentTimeMillis()
        val current = _peers.value.toMutableList()
        val index = current.indexOfFirst { it.id == key }
        if (index >= 0) {
            val existing = current[index]
            current[index] = existing.copy(
                rssi = rssi,
                lastSeen = now,
                identityId = identityId ?: existing.identityId,
                known = (identityId ?: existing.identityId) != null,
            )
        } else {
            current += NearbyPeer(key, identityId != null, identityId, rssi, now)
        }
        _peers.value = current
    }

    private fun startPruning() {
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (true) {
                delay(3_000)
                val cutoff = System.currentTimeMillis() - PEER_TTL_MS
                val filtered = _peers.value.filter { it.lastSeen >= cutoff }
                if (filtered.size != _peers.value.size) _peers.value = filtered
            }
        }
    }

    // MARK: — разрешения

    private val advertisePermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            Manifest.permission.BLUETOOTH_ADMIN
        }

    private val scanPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            // До Android 12 поиск BLE неотделим от геолокации — это требование
            // системы, а не наше желание знать, где человек.
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasPermissions(): Boolean =
        hasPermission(advertisePermission) && hasPermission(scanPermission)

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    fun bluetoothReady(): Boolean = adapter?.isEnabled == true

    companion object {
        private const val PEER_TTL_MS = 20_000L

        /** Раскладка маячка: [0xAE | 15 байт]. Значим только первый байт. */
        private const val NAMESPACE_UUID = "AE000000-0000-0000-0000-000000000000"
        private const val NAMESPACE_MASK = "FF000000-0000-0000-0000-000000000000"
    }
}
