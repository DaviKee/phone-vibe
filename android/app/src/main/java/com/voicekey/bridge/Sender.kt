package com.voicekey.bridge

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 传输通道抽象：UDP 与 BLE 走同一套帧 */
interface Sender {
    val name: String
    val isReady: Boolean
    fun onSessionStart(sid: Int)
    fun onAudio(sid: Int, seq: Int, data: ByteArray, len: Int)
    fun onSessionEnd(sid: Int, cancelled: Boolean)

    /** 反向控制（确认/取消） */
    fun onControl(cmd: Int) {}

    /** 一个包里塞几帧 */
    fun framesPerPacket(): Int = 2

    fun close() {}
}

object Logx {
    private const val TAG = "VoiceKey"
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var listener: ((String) -> Unit)? = null
    private val history = ArrayDeque<String>()

    fun d(msg: String) {
        Log.d(TAG, msg)
        val line = "${fmt.format(Date())}  $msg"
        synchronized(history) {
            history.addLast(line)
            while (history.size > 120) history.removeFirst()
        }
        listener?.invoke(line)
    }

    fun snapshot(): List<String> = synchronized(history) { history.toList() }
}
