package com.voicekey.bridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 常驻前台服务：持有麦克风、悬浮条、传输通道。
 * 平时只挂一条通知、不占屏幕；按音量下键时才开麦克风、才弹语音条。
 */
class VoiceBarService : Service() {

    companion object {
        @Volatile var instance: VoiceBarService? = null
        @Volatile var running = false

        const val ACTION_BEGIN = "com.voicekey.bridge.BEGIN"
        const val ACTION_END = "com.voicekey.bridge.END"
        const val ACTION_CANCEL = "com.voicekey.bridge.CANCEL"
        const val ACTION_CONFIRM = "com.voicekey.bridge.CONFIRM"
    }

    private val main = Handler(Looper.getMainLooper())
    private var bar: OverlayBar? = null

    /** BLE 通道必须常驻：每次重建 GATT Server 会让电脑端断连 */
    private var ble: BleSender? = null
    private var udp: UdpSender? = null

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val alive = AtomicBoolean(false)

    private var sid = 0
    private var seq = 0
    private var startedAt = 0L

    @Volatile private var recording = false
    @Volatile private var committed = false
    @Volatile private var byteCount = 0
    @Volatile private var started = false
    /** 未到阈值时先攒着的音频包 */
    private val preBuf = ArrayList<ByteArray>()

    /** 待发的一包（不满一包时先攒着） */
    @Volatile private var pendingPacket: ByteArray? = null
    @Volatile private var pendingLen = 0

    private val showBarTask = Runnable {
        if (recording) {
            committed = true
            ensureBar().show("正在聆听…", "松开发送 · 音量上+下取消")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = true
        startForegroundSafe()
        Logx.d("服务已就绪，等待按键")
        if (Cfg.transport(this) == Cfg.TRANSPORT_BLE) ensureBle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BEGIN -> begin()
            ACTION_END -> end()
            ACTION_CANCEL -> cancel()
            ACTION_CONFIRM -> confirm()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        instance = null
        stopCapture()
        ble?.stop()
        ble = null
        udp?.close()
        udp = null
        bar?.hide()
        Logx.d("服务已停止")
        super.onDestroy()
    }

    private fun ensureBar(): OverlayBar = bar ?: OverlayBar(this).also { bar = it }

    // ------------------------------------------------------------ 会话

    @SuppressLint("MissingPermission")
    fun begin() {
        if (recording) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Logx.d("没有录音权限")
            return
        }

        val s = pickSender()
        Logx.d("开始会话，通道=${s.name}")

        val minBuf = AudioRecord.getMinBufferSize(
            AdpcmEncoder.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AdpcmEncoder.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, AdpcmEncoder.SAMPLE_RATE / 4)
            )
        } catch (e: Exception) {
            Logx.d("AudioRecord 创建失败: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Logx.d("麦克风初始化失败（可能被其他应用占用）")
            rec.release()
            return
        }

        record = rec
        sid = (sid + 1) and 0xFF
        seq = 0
        byteCount = 0
        pendingLen = 0
        started = false
        synchronized(preBuf) { preBuf.clear() }
        recording = true
        committed = false
        startedAt = System.currentTimeMillis()
        alive.set(true)

        main.postDelayed(showBarTask, Cfg.holdMs(this).toLong())
        thread = Thread({ pump(rec, s) }, "vk-audio").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun pump(rec: AudioRecord, s: Sender) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val enc = AdpcmEncoder()
        val pcm = ShortArray(AdpcmEncoder.SAMPLES_PER_FRAME)
        val frame = ByteArray(AdpcmEncoder.FRAME_BYTES)
        val perPacket = s.framesPerPacket().coerceIn(1, 4)
        val packet = ByteArray(AdpcmEncoder.FRAME_BYTES * perPacket)
        var filled = 0
        var inPacket = 0

        try {
            rec.startRecording()
            while (alive.get()) {
                var read = 0
                while (read < pcm.size && alive.get()) {
                    val n = rec.read(pcm, read, pcm.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < pcm.size) break

                val n = enc.encode(pcm, read, frame)
                System.arraycopy(frame, 0, packet, filled, n)
                filled += n
                inPacket++
                byteCount += n

                if (inPacket >= perPacket) {
                    val pkt = packet.copyOf(filled)
                    if (started) {
                        s.onAudio(sid, seq++, pkt, pkt.size)
                    } else if (committed) {
                        // 刚过阈值：先把这之前的音频补发出去，再开始正常发送
                        s.onSessionStart(sid)
                        started = true
                        synchronized(preBuf) {
                            for (q in preBuf) s.onAudio(sid, seq++, q, q.size)
                            preBuf.clear()
                        }
                        s.onAudio(sid, seq++, pkt, pkt.size)
                    } else {
                        // 还没到阈值，先攒着（最多 300ms），不够长就整段丢弃
                        synchronized(preBuf) { if (preBuf.size < 40) preBuf.add(pkt) }
                    }
                    filled = 0
                    inPacket = 0
                    pendingLen = 0
                } else {
                    pendingPacket = packet
                    pendingLen = filled
                }
                if (committed) ensureBar().setLevel(levelOf(pcm, read))
            }
        } catch (e: Exception) {
            Logx.d("采集线程异常: ${e.message}")
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun levelOf(pcm: ShortArray, n: Int): Float {
        var acc = 0.0
        for (i in 0 until n) {
            val v = pcm[i].toDouble()
            acc += v * v
        }
        val rms = sqrt(acc / n)
        if (rms < 1e-3) return 0f
        val db = (20 * log10(rms / 32768.0)).toFloat()
        return ((db + 60f) / 55f).coerceIn(0f, 1f)
    }

    /** 松开：够长就发送，太短就丢弃并补一次音量调节 */
    fun end() {
        if (!recording) return
        val held = System.currentTimeMillis() - startedAt
        stopCapture()
        main.removeCallbacks(showBarTask)
        val s = activeSender

        if (held < Cfg.holdMs(this)) {
            Logx.d("按键过短(${held}ms)，丢弃，补一次音量减")
            adjustVolume(-1)
            bar?.hide()
            committed = false
            started = false
            releaseUdp()
            return
        }

        // 极端情况：音频线程还没轮到"提交"就停了，这里补一次
        if (!started) {
            s?.onSessionStart(sid)
            started = true
            synchronized(preBuf) {
                for (q in preBuf) s?.onAudio(sid, seq++, q, q.size)
                preBuf.clear()
            }
        }
        synchronized(preBuf) { preBuf.clear() }

        // 不满一包的尾巴
        val pp = pendingPacket
        if (s != null && pp != null && pendingLen > 0) {
            s.onAudio(sid, seq++, pp, pendingLen)
            pendingLen = 0
        }
        s?.onSessionEnd(sid, false)
        Logx.d("已发送 ${byteCount} 字节 ADPCM（约 ${byteCount / 8} ms）")

        if (committed) {
            bar?.update("已发送", "松开即发送")
            bar?.hide(700)
        }
        committed = false
        started = false
        releaseUdp()
    }

    fun cancel() {
        if (!recording) return
        stopCapture()
        main.removeCallbacks(showBarTask)
        if (started) activeSender?.onSessionEnd(sid, true)
        committed = false
        started = false
        pendingLen = 0
        Logx.d("已取消")
        bar?.update("已取消", "")
        bar?.hide(500)
        releaseUdp()
    }

    fun confirm() {
        val s = activeSender
        if (s == null || !s.isReady) {
            Logx.d("确认失败：电脑端未连接")
            return
        }
        s.onControl(Frame.CTRL_CONFIRM.toInt())
        Logx.d("已向电脑发送确认")
        ensureBar().show("已确认", "回车已发送")
        bar?.hide(600)
    }

    private fun stopCapture() {
        alive.set(false)
        val t = thread
        thread = null
        try { t?.join(250) } catch (_: Exception) {}
        recording = false
        record = null
    }

    // ------------------------------------------------------------ 通道选择

    private var activeSender: Sender? = null

    private fun pickSender(): Sender {
        if (Cfg.transport(this) == Cfg.TRANSPORT_BLE) {
            val b = ensureBle()
            if (b != null && b.isReady) {
                activeSender = b
                return b
            }
            Logx.d("蓝牙未连接电脑，本次改用 Wi-Fi/UDP")
        }
        val u = UdpSender(this)
        udp = u
        activeSender = u
        return u
    }

    private fun ensureBle(): BleSender? {
        ble?.let { return it }
        val b = BleSender(this)
        if (!b.start()) {
            Logx.d("蓝牙通道启动失败（适配器/权限问题）")
            return null
        }
        b.onPcCommand = { cmd ->
            when (cmd) {
                Frame.CTRL_CANCEL.toInt() -> cancel()
                Frame.CTRL_CONFIRM.toInt() -> confirm()
                else -> Logx.d("电脑回 ACK")
            }
        }
        ble = b
        return b
    }

    private fun releaseUdp() {
        udp?.close()
        udp = null
        if (activeSender is UdpSender) activeSender = null
    }

    @SuppressLint("MissingPermission")
    private fun adjustVolume(dir: Int) {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ 前台通知

    private fun startForegroundSafe() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("vk", "语音桥", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n: Notification = Notification.Builder(this, "vk")
            .setContentTitle("语音桥待命")
            .setContentText("按住音量下键说话 · 长按音量上键确认")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                2001, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(2001, n)
        }
    }
}
