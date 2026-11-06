package com.voicekey.bridge

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sin

/**
 * 悬浮语音条：贴屏幕底部的一条小胶囊，FLAG_NOT_FOCUSABLE | NOT_TOUCHABLE，
 * 不抢焦点、不挡触摸，下面正在用的 App 完全不受影响。
 */
class OverlayBar(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var root: View? = null
    private var pulse: ValueAnimator? = null
    private var wave: WaveView? = null
    private var title: TextView? = null
    private var sub: TextView? = null

    val isShowing: Boolean get() = root != null

    private fun dp(v: Float) = (v * ctx.resources.displayMetrics.density)

    fun show(text: String, hint: String) {
        if (root != null) {
            title?.text = text
            sub?.text = hint
            return
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f).toInt(), dp(10f).toInt(), dp(20f).toInt(), dp(10f).toInt())
            background = GradientDrawable().apply {
                cornerRadius = dp(26f)
                setColor(Color.parseColor("#E6202020"))
                setStroke(dp(1f).toInt(), Color.parseColor("#33FFFFFF"))
            }
        }

        val w = WaveView(ctx).apply { setLevel(0f) }
        row.addView(w, LinearLayout.LayoutParams(dp(34f).toInt(), dp(24f).toInt()).apply {
            rightMargin = dp(12f).toInt()
        })

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(ctx).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
        }
        sub = TextView(ctx).apply {
            this.text = hint
            setTextColor(Color.parseColor("#99FFFFFF"))
            textSize = 11f
            setSingleLine(true)
        }
        col.addView(title)
        col.addView(sub)
        row.addView(col)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(96f).toInt()
        }

        try {
            wm.addView(row, lp)
        } catch (e: Exception) {
            Logx.d("悬浮窗添加失败（是否授予了「显示在其他应用上层」？）: ${e.message}")
            return
        }
        wave = w
        root = row

        row.alpha = 0f
        row.translationY = dp(18f)
        row.animate().alpha(1f).translationY(0f).setDuration(140).start()

        // 呼吸动效：录音时轻微脉动
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { if (row.parent != null) row.alpha = 0.82f + 0.18f * (it.animatedValue as Float) }
            start()
        }
    }

    fun setLevel(level: Float) {
        main.post { wave?.setLevel(level) }
    }

    fun update(text: String, hint: String) {
        main.post {
            title?.text = text
            sub?.text = hint
        }
    }

    fun hide(delayMs: Long = 0) {
        main.postDelayed({
            val r = root ?: return@postDelayed
            root = null
            pulse?.cancel()
            pulse = null
            r.animate().alpha(0f).translationY(dp(14f)).setDuration(130)
                .withEndAction {
                    try { wm.removeView(r) } catch (_: Exception) {}
                }.start()
        }, delayMs)
    }
}

/** 简单的波形条，不追求好看，够用来确认"在收音" */
class WaveView(ctx: Context) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6EF2A8")
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private var level = 0f
    private var phase = 0f

    fun setLevel(l: Float) {
        level = level * 0.55f + l.coerceIn(0f, 1f) * 0.45f
        phase += 0.45f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = 4
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w / (n + 1)
        val cx = h / 2f
        for (i in 0 until n) {
            val wobble = 0.55f + 0.45f * abs(sin(phase + i * 0.9f))
            val amp = (0.18f + 0.82f * level) * wobble
            val half = (h / 2f) * amp
            val x = gap * (i + 1)
            canvas.drawLine(x, cx - half, x, cx + half, paint)
        }
    }
}
