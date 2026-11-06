package com.voicekey.bridge

import android.content.Context
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wi-Fi / UDP 通道。电脑端监听 47823 端口即可，无需配对、无需驱动。
 *
 * 目标 IP 留空 → 发 255.255.255.255 广播，同一局域网内电脑都能收到。
 */
class UdpSender(ctx: Context) : Sender {

    private val port: Int = Cfg.port(ctx)
    private val host: String = Cfg.pcIp(ctx).trim()

    private var socket: DatagramSocket? = null
    private var target: InetAddress? = null

    @Volatile private var lastError: String? = null

    override val name: String get() = "Wi-Fi / UDP:$port"

    override val isReady: Boolean get() = socket != null && target != null

    init {
        try {
            socket = DatagramSocket().apply { broadcast = true }
            target = if (host.isEmpty()) InetAddress.getByName("255.255.255.255")
            else InetAddress.getByName(host)
            Logx.d("UDP: 目标 ${target?.hostAddress}:$port")
        } catch (e: Exception) {
            lastError = e.message
            Logx.d("UDP 初始化失败: ${e.message}")
        }
    }

    private fun send(bytes: ByteArray) {
        val s = socket ?: return
        val t = target ?: return
        try {
            s.send(DatagramPacket(bytes, bytes.size, t, port))
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    override fun onSessionStart(sid: Int) = send(Frame.start(sid, 15))

    override fun onAudio(sid: Int, seq: Int, data: ByteArray, len: Int) =
        send(Frame.audio(sid, seq, data, len))

    override fun onSessionEnd(sid: Int, cancelled: Boolean) = send(Frame.end(sid, cancelled))

    override fun onControl(cmd: Int) = send(Frame.ctrl(cmd))

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
}
