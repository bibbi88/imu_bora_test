package fi.bibbi88.borawatch

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID

/**
 * BLE peripheral with the Nordic UART Service – the same service Bora uses –
 * so FlyGineer, XCTrack and the web lab can connect to the watch like to Bora.
 */
@SuppressLint("MissingPermission")
class BleUart(private val ctx: Context, private val onStatus: (String) -> Unit) {

    companion object {
        val NUS: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private val subscribed = mutableSetOf<BluetoothDevice>()
    private val mtu = mutableMapOf<String, Int>()
    private val queue = ArrayDeque<Pair<BluetoothDevice, ByteArray>>()
    private var busy = false
    private var advertising = false
    var connected = 0; private set

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true; report()
        }
        override fun onStartFailure(errorCode: Int) {
            advertising = false
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && !triedWithoutName) {
                triedWithoutName = true; startAdvertising(includeName = false)
            } else onStatus("BLE advertise failed ($errorCode)")
        }
    }
    private var triedWithoutName = false

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising(includeName = true)
            else onStatus("BLE service failed ($status)")
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(this@BleUart) {
                if (newState == BluetoothProfile.STATE_CONNECTED) connected++
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = (connected - 1).coerceAtLeast(0)
                    subscribed.remove(device); mtu.remove(device.address)
                    queue.removeAll { it.first == device }
                    busy = false
                }
                Unit
            }
            report()
        }

        override fun onMtuChanged(device: BluetoothDevice, m: Int) {
            synchronized(this@BleUart) { mtu[device.address] = m }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD) synchronized(this@BleUart) {
                if (value != null && value.isNotEmpty() && value[0].toInt() != 0) subscribed.add(device)
                else subscribed.remove(device)
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            report()
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val on = synchronized(this@BleUart) { device in subscribed }
            val v = if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(this@BleUart) { busy = false; pump() }
        }
    }

    fun start(): Boolean {
        val adapter = manager.adapter ?: run { onStatus("No Bluetooth"); return false }
        if (!adapter.isEnabled) { onStatus("Turn on Bluetooth"); return false }
        if (server != null) return true
        val s = manager.openGattServer(ctx, callback) ?: run { onStatus("GATT server failed"); return false }
        server = s
        val service = BluetoothGattService(NUS, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val t = BluetoothGattCharacteristic(
            TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        t.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val r = BluetoothGattCharacteristic(
            RX, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        service.addCharacteristic(t); service.addCharacteristic(r)
        tx = t
        s.addService(service)
        onStatus("BLE starting…")
        return true
    }

    private fun startAdvertising(includeName: Boolean) {
        val adv = manager.adapter?.bluetoothLeAdvertiser ?: run { onStatus("BLE advertising not supported"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(NUS)).setIncludeDeviceName(false).build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(includeName).build()
        try { adv.stopAdvertising(advCallback) } catch (_: Exception) {}
        adv.startAdvertising(settings, data, scan, advCallback)
    }

    /** Send one text line to every subscribed central, split to fit the MTU. */
    fun send(line: String) {
        val bytes = line.toByteArray(Charsets.US_ASCII)
        synchronized(this) {
            if (subscribed.isEmpty()) return
            for (d in subscribed) {
                val chunk = ((mtu[d.address] ?: 23) - 3).coerceAtLeast(20)
                var i = 0
                while (i < bytes.size) {
                    queue.addLast(d to bytes.copyOfRange(i, minOf(bytes.size, i + chunk)))
                    i += chunk
                }
            }
            while (queue.size > 60) queue.removeFirst()   // never let a slow link build up lag
            pump()
        }
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        if (busy) return
        val s = server ?: return
        val c = tx ?: return
        val (dev, data) = queue.removeFirstOrNull() ?: return
        busy = true
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            s.notifyCharacteristicChanged(dev, c, false, data) == BluetoothStatusCodes.SUCCESS
        } else {
            c.value = data
            s.notifyCharacteristicChanged(dev, c, false)
        }
        if (!ok) busy = false
    }

    private fun report() {
        val subs = synchronized(this) { subscribed.size }
        onStatus(
            when {
                subs > 0 -> "BLE: sending to $subs"
                connected > 0 -> "BLE: $connected connected"
                advertising -> "BLE: waiting for connection"
                else -> "BLE: starting…"
            }
        )
    }

    fun stop() {
        try { manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCallback) } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null; advertising = false
    }
}
