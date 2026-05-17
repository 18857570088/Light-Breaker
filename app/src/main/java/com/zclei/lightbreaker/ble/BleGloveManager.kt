package com.zclei.lightbreaker.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import com.zclei.lightbreaker.hit.GloveHitAccumulator
import com.zclei.lightbreaker.hit.HitEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class GloveDevice(
    val name: String,
    val address: String,
    val hand: GloveHand,
    val rssi: Int,
)

enum class ConnectionPhase {
    Idle,
    Scanning,
    Connecting,
    Connected,
    Ready,
    Disconnected,
    Error,
}

data class GloveConnectionState(
    val hand: GloveHand,
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val deviceName: String? = null,
    val batteryText: String = "--",
    val gyroCount: Int = 0,
    val gyroPower: Int = 0,
    val message: String = "",
)

class BleGloveManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val accumulator = GloveHitAccumulator()
    private val sessions = mutableMapOf<GloveHand, Session>()
    private val foundDevices = linkedMapOf<String, GloveDevice>()

    private val _devices = MutableStateFlow<List<GloveDevice>>(emptyList())
    val devices: StateFlow<List<GloveDevice>> = _devices

    private val _states =
        MutableStateFlow(
            mapOf(
                GloveHand.Left to GloveConnectionState(GloveHand.Left),
                GloveHand.Right to GloveConnectionState(GloveHand.Right),
            ),
        )
    val states: StateFlow<Map<GloveHand, GloveConnectionState>> = _states

    private val _packets = MutableSharedFlow<GlovePacket>(extraBufferCapacity = 32)
    val packets: SharedFlow<GlovePacket> = _packets

    private val _hits = MutableSharedFlow<HitEvent>(extraBufferCapacity = 64)
    val hits: SharedFlow<HitEvent> = _hits

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult,
            ) {
                handleScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::handleScanResult)
            }

            override fun onScanFailed(errorCode: Int) {
                setAllScanningMessage("扫描失败：$errorCode")
            }
        }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (adapter?.isEnabled != true) {
            setAllScanningMessage("蓝牙未开启")
            return
        }
        foundDevices.clear()
        _devices.value = emptyList()
        updateState(GloveHand.Left) { it.copy(phase = ConnectionPhase.Scanning, message = "正在扫描左手套") }
        updateState(GloveHand.Right) { it.copy(phase = ConnectionPhase.Scanning, message = "正在扫描右手套") }
        adapter.bluetoothLeScanner?.startScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        listOf(GloveHand.Left, GloveHand.Right).forEach { hand ->
            updateState(hand) { state ->
                if (state.phase == ConnectionPhase.Scanning) {
                    state.copy(phase = ConnectionPhase.Idle, message = "扫描已停止")
                } else {
                    state
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: GloveDevice) {
        stopScan()
        disconnect(device.hand)
        val remote = adapter?.getRemoteDevice(device.address) ?: return
        updateState(device.hand) {
            it.copy(
                phase = ConnectionPhase.Connecting,
                deviceName = device.name,
                message = "正在连接 ${device.name}",
            )
        }
        val callback = createGattCallback(device.hand, device.name)
        val gatt =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                remote.connectGatt(context, false, callback)
            }
        sessions[device.hand] = Session(device.hand, device.name, gatt)
    }

    @SuppressLint("MissingPermission")
    fun disconnect(hand: GloveHand) {
        sessions.remove(hand)?.let { session ->
            writeCommand(session, GlovePacketParser.disableGyroCommand)
            session.gatt.disconnect()
            session.gatt.close()
        }
        updateState(hand) { it.copy(phase = ConnectionPhase.Disconnected, message = "已断开") }
    }

    fun disconnectAll() {
        listOf(GloveHand.Left, GloveHand.Right).forEach(::disconnect)
        accumulator.reset()
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val name = result.device.name ?: result.scanRecord?.deviceName ?: return
        if (!GlovePacketParser.isSupportedName(name)) {
            return
        }
        val hand = GlovePacketParser.handFromName(name)
        if (hand == GloveHand.Unknown) {
            return
        }
        val device =
            GloveDevice(
                name = name,
                address = result.device.address,
                hand = hand,
                rssi = result.rssi,
            )
        foundDevices[device.address] = device
        _devices.value = foundDevices.values.toList().sortedWith(compareBy<GloveDevice> { it.hand.ordinal }.thenBy { it.name })
    }

    private fun createGattCallback(
        hand: GloveHand,
        deviceName: String,
    ): BluetoothGattCallback =
        object : BluetoothGattCallback() {
            @SuppressLint("MissingPermission")
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    updateState(hand) {
                        it.copy(phase = ConnectionPhase.Connected, deviceName = deviceName, message = "已连接，正在发现服务")
                    }
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    updateState(hand) {
                        it.copy(phase = ConnectionPhase.Disconnected, deviceName = deviceName, message = "连接已断开")
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    updateState(hand) {
                        it.copy(phase = ConnectionPhase.Error, message = "服务发现失败：$status")
                    }
                    return
                }
                val notifyChar = chooseNotifyCharacteristic(gatt.services)
                val writeChar = chooseWriteCharacteristic(gatt.services)
                if (notifyChar == null || writeChar == null) {
                    updateState(hand) {
                        it.copy(phase = ConnectionPhase.Error, message = "未找到 notify/write 特征")
                    }
                    return
                }
                sessions[hand] = Session(hand, deviceName, gatt, notifyChar, writeChar)
                enableNotifications(gatt, notifyChar)
                writeCommand(sessions.getValue(hand), GlovePacketParser.enableGyroCommand)
                updateState(hand) {
                    it.copy(phase = ConnectionPhase.Ready, deviceName = deviceName, message = "陀螺仪已开启")
                }
            }

            @Suppress("DEPRECATION")
            @Deprecated("Retained for Android API levels that deliver notifications through characteristic.value.")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                handleNotify(hand, characteristic.value)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                handleNotify(hand, value)
            }
        }

    private fun handleNotify(
        hand: GloveHand,
        value: ByteArray,
    ) {
        val packet = GlovePacketParser.parseOrNull(value, hand) ?: return
        scope.launch {
            _packets.emit(packet)
            accumulator.accept(packet, System.currentTimeMillis()).forEach { hit -> _hits.emit(hit) }
        }
        updateState(hand) {
            it.copy(
                batteryText = packet.batteryText,
                gyroCount = packet.gyroPunchCount,
                gyroPower = packet.effectivePower,
                message = "包 ${packet.packetNo} · ${packet.rawHex}",
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        characteristic.getDescriptor(CLIENT_CONFIG_UUID)?.let { descriptor ->
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(
        session: Session,
        command: ByteArray,
    ) {
        val characteristic = session.writeCharacteristic ?: return
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        characteristic.value = command
        @Suppress("DEPRECATION")
        session.gatt.writeCharacteristic(characteristic)
    }

    private fun chooseNotifyCharacteristic(services: List<BluetoothGattService>): BluetoothGattCharacteristic? =
        services.flatMap { it.characteristics }.filter { characteristic ->
            val props = characteristic.properties
            props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        }.minByOrNull { characteristicPriority(it, preferredPrefix = "0000ffe4") }

    private fun chooseWriteCharacteristic(services: List<BluetoothGattService>): BluetoothGattCharacteristic? =
        services.flatMap { it.characteristics }.filter { characteristic ->
            val props = characteristic.properties
            props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        }.minByOrNull { characteristicPriority(it, preferredPrefix = "0000ffe1") }

    private fun characteristicPriority(
        characteristic: BluetoothGattCharacteristic,
        preferredPrefix: String,
    ): Int {
        val uuid = characteristic.uuid.toString().lowercase()
        return when {
            uuid.startsWith(preferredPrefix) -> 0
            uuid.startsWith("0000ffe") -> 10
            uuid.startsWith("0000fee") -> 20
            else -> 100
        }
    }

    private fun setAllScanningMessage(message: String) {
        listOf(GloveHand.Left, GloveHand.Right).forEach { hand ->
            updateState(hand) { it.copy(phase = ConnectionPhase.Error, message = message) }
        }
    }

    private fun updateState(
        hand: GloveHand,
        transform: (GloveConnectionState) -> GloveConnectionState,
    ) {
        val current = _states.value.toMutableMap()
        current[hand] = transform(current[hand] ?: GloveConnectionState(hand))
        _states.value = current
    }

    private data class Session(
        val hand: GloveHand,
        val deviceName: String,
        val gatt: BluetoothGatt,
        val notifyCharacteristic: BluetoothGattCharacteristic? = null,
        val writeCharacteristic: BluetoothGattCharacteristic? = null,
    )

    private companion object {
        val CLIENT_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
