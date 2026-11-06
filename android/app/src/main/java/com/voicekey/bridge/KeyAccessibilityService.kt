package com.voicekey.bridge

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 全局音量键拦截。
 *
 * Android 里普通 App 拿不到全局按键，悬浮窗也拿不到硬件键（除非抢焦点，那就破坏了"不影响原 App"）。
 * 唯一干净的路子是无障碍服务的 flagRequestFilterKeyEvents —— 它能在事件到达系统音量控制之前拦截，
 * 并且不需要 root、不需要读屏权限。
 *
 * 手势：
 *   按住音量下  → 开始收音（松开发送；低于 holdMs 视为误触，丢弃并补一次音量调节）
 *   长按音量上  → 向电脑发送"确认"
 *   上下同按    → 取消本次
 */
class KeyAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: KeyAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())

    private var downHeld = false
    private var upHeld = false
    private var upLongFired = false
    private var upLongTask: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logx.d("无障碍服务已连接，音量键已接管")
        ensureVoiceService()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!isScreenOn()) return false
        if (!Settings.canDrawOverlays(this)) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> handleVolumeDown(event)
            KeyEvent.KEYCODE_VOLUME_UP -> handleVolumeUp(event)
            else -> false
        }
    }

    private fun handleVolumeDown(e: KeyEvent): Boolean {
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return true
                // 音量上已按住 → 组合键取消
                if (upHeld) {
                    VoiceBarService.instance?.cancel()
                    downHeld = true
                    return true
                }
                if (!downHeld) {
                    downHeld = true
                    ensureVoiceService()
                    val svc = VoiceBarService.instance
                    if (svc != null) {
                        svc.begin()
                    } else {
                        // 服务刚拉起，稍等再开始（避免丢掉第一次按键）
                        main.postDelayed({
                            if (downHeld) {
                                ensureVoiceService()
                                VoiceBarService.instance?.begin()
                            }
                        }, 150)
                    }
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (downHeld) {
                    downHeld = false
                    VoiceBarService.instance?.end()
                }
                return true
            }
        }
        return false
    }

    private fun handleVolumeUp(e: KeyEvent): Boolean {
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount > 0) return true
                if (downHeld) {
                    // 录音中按上键 → 取消
                    VoiceBarService.instance?.cancel()
                    upHeld = true
                    return true
                }
                upHeld = true
                upLongFired = false
                val task = Runnable {
                    if (upHeld && !downHeld) {
                        upLongFired = true
                        VoiceBarService.instance?.confirm()
                    }
                }
                upLongTask = task
                main.postDelayed(task, 600)
                return true
            }
            KeyEvent.ACTION_UP -> {
                upLongTask?.let { main.removeCallbacks(it) }
                upLongTask = null
                if (upHeld && !upLongFired) {
                    // 短按：还原成正常的音量加
                    adjustVolumeUp()
                }
                upHeld = false
                upLongFired = false
                return true
            }
        }
        return false
    }

    private fun adjustVolumeUp() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
        } catch (_: Exception) {}
    }

    private fun isScreenOn(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isInteractive
    }

    private fun ensureVoiceService() {
        if (VoiceBarService.running) return
        try {
            val i = Intent(this, VoiceBarService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } catch (e: Exception) {
            Logx.d("拉起服务失败: ${e.message}")
        }
    }
}
