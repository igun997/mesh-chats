package com.meshchats.app.core.transport.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android [BleRadio] backed by the platform advertiser and scanner.
 *
 * This is a deliberately thin adapter: it translates the controller's
 * start/stop calls into [BluetoothLeAdvertiser] and [BluetoothLeScanner]
 * operations and forwards results, but holds no discovery policy of its own.
 * Peers are identified only by the advertised service data — the Bluetooth
 * device address is never read, since it rotates and must not be treated as
 * identity.
 *
 * ### Advertisement layout
 * The beacon advertises **service data only** (never the service UUID list). A
 * legacy advertising PDU holds 31 bytes; the flags structure (3) plus a
 * 128-bit service-data header (18) plus the 10-byte payload lands exactly on
 * that limit ([BleAdvertisementBudget]). Adding the 128-bit UUID to the UUID
 * list as well would overflow the packet, so scans filter on service data
 * (UUID + version prefix) instead of the UUID list. The assembled size is
 * checked before touching the platform, and start fails closed if it would
 * overflow.
 *
 * ### Failure handling
 * Advertising and scanning both fail closed: platform failure callbacks are
 * routed to `onError` at most once per active start (guarded by [errorFired],
 * reset on [stop]), and the controller catches [SecurityException] at its
 * boundary, so this class can call the guarded APIs directly.
 *
 * ### Permission suppression
 * BLE calls here (advertise/scan/stop) require BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE
 * (or the pre-31 location grant). Lint cannot see the guard because it lives at the
 * controller boundary: [DefaultBleDiscoveryController] checks [BleRadio.missingPermissions]
 * before ever calling [start], and wraps every radio call in `runCatching`, turning a
 * revoked-permission [SecurityException] into a bounded Error state instead of a crash.
 * That makes the MissingPermission check a false positive for this adapter, so it is
 * suppressed at the class level.
 */
@SuppressLint("MissingPermission")
class AndroidBleRadio(
    context: Context,
    private val serviceUuid: UUID = BleDiscoveryController.SERVICE_UUID,
) : BleRadio {

    // Hold the application context, never an Activity/Service, to avoid leaks.
    private val appContext: Context = context.applicationContext

    private val parcelServiceUuid = ParcelUuid(serviceUuid)

    private val bluetoothManager: BluetoothManager? =
        ContextCompat.getSystemService(appContext, BluetoothManager::class.java)

    private val adapter get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null

    /** Ensures `onError` fires at most once per active start; reset on [stop]. */
    private val errorFired = AtomicBoolean(false)

    override val isSupported: Boolean
        get() {
            val hasFeature = appContext.packageManager
                .hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
            val a = adapter ?: return false
            // Multiple-advertisement support is required to beacon while scanning.
            return hasFeature && a.isMultipleAdvertisementSupported
        }

    override fun isEnabled(): Boolean = adapter?.isEnabled == true

    override fun missingPermissions(): Set<String> =
        BlePermissionPolicy.requiredPermissions(Build.VERSION.SDK_INT)
            .filter {
                ContextCompat.checkSelfPermission(appContext, it) !=
                    PackageManager.PERMISSION_GRANTED
            }
            .toSet()

    override fun start(
        serviceUuid: UUID,
        payload: ByteArray,
        onResult: (BleScanResult) -> Unit,
        onError: (String) -> Unit,
    ) {
        // Report the first failure of this start attempt exactly once.
        errorFired.set(false)
        val reportError: (String) -> Unit = { message ->
            if (errorFired.compareAndSet(false, true)) onError(message)
        }

        // Fail closed before touching the platform if the assembled packet
        // would overflow the legacy advertisement budget.
        if (!BleAdvertisementBudget.fitsLegacy(payload.size)) {
            reportError(
                "BLE advertisement too large: " +
                    "${BleAdvertisementBudget.assembledSize(payload.size)} > " +
                    "${BleAdvertisementBudget.LEGACY_MAX_BYTES} bytes.",
            )
            return
        }

        val a = adapter ?: run {
            reportError("Bluetooth adapter unavailable.")
            return
        }

        val advertiser = a.bluetoothLeAdvertiser ?: run {
            reportError("BLE advertising unavailable.")
            return
        }
        val scanner = a.bluetoothLeScanner ?: run {
            reportError("BLE scanning unavailable.")
            return
        }
        this.advertiser = advertiser
        this.scanner = scanner

        val advCallback = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                reportError("BLE advertise failed (code $errorCode).")
            }
        }.also { advertiseCallback = it }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        // Service data only — advertising the UUID list too would overflow the
        // 31-byte legacy packet (see [BleAdvertisementBudget]).
        val advData = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(serviceUuid), payload)
            .build()
        advertiser.startAdvertising(settings, advData, advCallback)

        val newScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.let { forward(it, onResult) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { forward(it, onResult) }
            }

            override fun onScanFailed(errorCode: Int) {
                reportError("BLE scan failed (code $errorCode).")
            }
        }
        this.scanCallback = newScanCallback

        // Filter on service data (UUID + protocol-version prefix), since the
        // service UUID is no longer advertised in the UUID list.
        val versionPrefix = byteArrayOf(BleDiscoveryProtocol.VERSION)
        val versionMask = byteArrayOf(0xFF.toByte())
        val filter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(serviceUuid), versionPrefix, versionMask)
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        scanner.startScan(listOf(filter), scanSettings, newScanCallback)
    }

    override fun stop() {
        advertiseCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
        scanCallback?.let { runCatching { scanner?.stopScan(it) } }
        advertiseCallback = null
        scanCallback = null
        advertiser = null
        scanner = null
        // Allow the next start to report its own first failure.
        errorFired.set(false)
    }

    private fun forward(result: ScanResult, onResult: (BleScanResult) -> Unit) {
        // Read only the advertised service data; never the device address.
        val payload = result.scanRecord?.getServiceData(parcelServiceUuid) ?: return
        onResult(BleScanResult(payload = payload, rssiDbm = result.rssi))
    }
}
