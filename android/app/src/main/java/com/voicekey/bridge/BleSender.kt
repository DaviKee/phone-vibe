package com.voicekey.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * 自定义 BLE GATT 外设端（手机做 Server，Windows 做 Client）。
 *
 * Windows 10 没有 ATVV 消费者，但有完整的 GATT Client（WinRT / 任何支持 BLE 的适配器）。
 * 所以这里自己定义服务，不依赖任何厂商协议。
 *
 *   SERVICE 5a3c0001-…  INFO 只读，返回一行能力描述
 *   CTRL    5a3c0002-…  电脑 → 手机 Write
 *   AUDIO   5a3c0003-…  手机 → 电脑 Notify
 */
class BleSender(private val ctx: Context) : Sender {

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("5a3c0001-7e1d-4c8b-9f2a-3d6e5b1c0a97")
        val CHAR_CTRL: UUID = UUID.fromString("5a3c0002-7e1d-4c8b-9f2a-3d6e5b1c0a97")
        val CHAR_AUDIO: UUID = UUID.fromString("5a3c0003-7e1d-4c8b-9f2a-3d6e5b1c0a97")
        val CHAR_INFO: UUID = UUID.fromString("5a3c0004-7e1d-4c8b-9f2a-3d6e5b1c0a97")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var charAudio: BluetoothGattCharacteristic? = null
    private var charCtl: BluetoothGattCharacteristic? = null

    @Volatile private var central: BluetoothDevice? = null
    @Volatile private var subscribed = false
    @Volatile private var mtu = 23

    private val permit = Semaphore(1)

    /** 电脑回的命令，供 UI/状态机使用 */
    @Volatile var onPcCommand: ((Int) -> Unit)? = null

    private val adapter get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    override val name: String get() = "蓝牙 GATT"

    override val isReady: Boolean
        get() = central != null && subscribed

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val ad = adapter ?: return false
        if (!ad.isEnabled) return false

        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        server?.close()
        @Suppress("DEPRECATION")
        server = bm.openGattServer(ctx, callback) ?: return false

        val info = BluetoothGattCharacteristic(
            CHAR_INFO,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val ctl = BluetoothGattCharacteristic(
            CHAR_CTRL,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val audio = BluetoothGattCharacteristic(
            CHAR_AUDIO,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        charCtl = ctl
        charAudio = audio

        val svc = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        svc.addCharacteristic(info)
        svc.addCharacteristic(ctl)
        svc.addCharacteristic(audio)
        server?.addService(svc)

        advertiser = ad.bluetoothLeAdvertiser ?: return false
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid.fromString(SERVICE_UUID.toString()))
            .build()
        val scanRsp = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        return try {
            advertiser?.startAdvertising(settings, data, scanRsp, advCb)
            true
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        try { advertiser?.stopAdvertising(advCb) } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        server = null
        central = null
        subscribed = false
    }

    private val advCb = object : AdvertiseCallback() {}

    private val callback = object : BluetoothGattServerCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                central = device
                Logx.d("BLE: 电脑已连接 ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Logx.d("BLE: 电脑断开")
                if (central?.address == device.address) {
                    central = null
                    subscribed = false
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            mtu = newMtu
            val usable = newMtu - 3 - 4
            framesPerPacketValue = (usable / AdpcmEncoder.FRAME_BYTES).coerceIn(1, 4)
            Logx.d("BLE: MTU = $newMtu → 每包 $framesPerPacketValue 帧")
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD && descriptor.characteristic.uuid == CHAR_AUDIO) {
                subscribed = value != null && value.isNotEmpty() && (value[0].toInt() and 1) != 0
                Logx.d("BLE: 音频订阅 = $subscribed")
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (value != null && value.size >= 2 && characteristic.uuid == CHAR_CTRL) {
                if (value[0] == Frame.T_CTRL) onPcCommand?.invoke(value[1].toInt())
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (!permit.tryAcquire()) permit.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(data: ByteArray): Boolean {
        val dev = central ?: return false
        val srv = server ?: return false
        val ch = charAudio ?: return false
        if (!permit.tryAcquire(250, TimeUnit.MILLISECONDS)) return false
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                srv.notifyCharacteristicChanged(dev, ch, false, data) == 0
            } else {
                @Suppress("DEPRECATION")
                ch.value = data
                @Suppress("DEPRECATION")
                srv.notifyCharacteristicChanged(dev, ch, false)
            }
        } catch (e: Exception) {
            false
        }
        if (!ok) permit.release()
        return ok
    }

    override fun onSessionStart(sid: Int) {
        notify(Frame.start(sid, 15))
    }

    override fun onAudio(sid: Int, seq: Int, data: ByteArray, len: Int) {
        notify(Frame.audio(sid, seq, data, len))
    }

    override fun onSessionEnd(sid: Int, cancelled: Boolean) {
        notify(Frame.end(sid, cancelled))
    }

    override fun onControl(cmd: Int) {
        // 手机 → 电脑的控制帧复用 AUDIO 通道（电脑端按类型分发）
        notify(Frame.ctrl(cmd))
    }

    override fun framesPerPacket(): Int = framesPerPacketValue

    private var framesPerPacketValue = 2
}
