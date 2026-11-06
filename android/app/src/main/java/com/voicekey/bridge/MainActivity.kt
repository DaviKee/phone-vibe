package com.voicekey.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var ipField: EditText
    private lateinit var portField: EditText
    private var rbWifi: RadioButton? = null
    private var rbBle: RadioButton? = null

    private val perms = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        Logx.listener = { line ->
            runOnUiThread {
                logView.append(line + "\n")
                (logView.parent as? ScrollView)?.post {
                    (logView.parent as? ScrollView)?.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
        Logx.snapshot().forEach { logView.append(it + "\n") }

        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 10)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------ UI

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(18))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(TextView(this).apply {
            text = "音量键语音桥"
            textSize = 20f
            setTextColor(Color.BLACK)
        })
        root.addView(TextView(this).apply {
            text = "按住音量下键说话 → 松开自动发到电脑；长按音量上键确认；上下同按取消。"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(12))
        })

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#444444"))
            setPadding(0, 0, 0, dp(10))
        }
        root.addView(status)

        root.addView(button("① 开启无障碍服务（拦截音量键）") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, lp(dp(46)))
        root.addView(button("② 授予「显示在其他应用上层」") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")
                )
            )
        }, lp(dp(46)))
        root.addView(button("③ 启动常驻服务") { startServiceNow() }, lp(dp(46)))

        root.addView(TextView(this).apply {
            text = "传输方式"
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(14), 0, dp(4))
        })

        val rg = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val wifi = RadioButton(this).apply { text = "Wi-Fi / UDP"; id = View.generateViewId() }
        val ble = RadioButton(this).apply { text = "蓝牙 GATT"; id = View.generateViewId() }
        rbWifi = wifi
        rbBle = ble
        rg.addView(wifi)
        rg.addView(ble)
        if (Cfg.transport(this) == Cfg.TRANSPORT_BLE) ble.isChecked = true else wifi.isChecked = true
        rg.setOnCheckedChangeListener { _, id ->
            Cfg.setTransport(this, if (id == ble.id) Cfg.TRANSPORT_BLE else Cfg.TRANSPORT_WIFI)
        }
        root.addView(rg)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        ipField = EditText(this).apply {
            hint = "电脑 IP（留空=局域网广播）"
            setText(Cfg.pcIp(this@MainActivity))
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
        }
        portField = EditText(this).apply {
            hint = "端口"
            setText(Cfg.port(this@MainActivity).toString())
            textSize = 13f
            inputType = InputType.TYPE_CLASS_NUMBER
            width = dp(90)
        }
        row.addView(ipField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(portField, LinearLayout.LayoutParams(dp(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row, lp(dp(52)))

        root.addView(button("保存设置") {
            Cfg.setPcIp(this, ipField.text.toString().trim())
            Cfg.setPort(this, portField.text.toString().toIntOrNull() ?: 47823)
            Logx.d("设置已保存：${Cfg.transport(this)} / ${Cfg.pcIp(this).ifEmpty { "广播" }}:${Cfg.port(this)}")
        }, lp(dp(42)))

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#F4F4F4")) }
        logView = TextView(this).apply {
            textSize = 10.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.parseColor("#333333"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(12) })

        setContentView(root)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun lp(h: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, h
    ).apply { topMargin = dp(6) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun refresh() {
        val a11y = isAccessibilityOn()
        val overlay = Settings.canDrawOverlays(this)
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        status.text = buildString {
            append(if (a11y) "✔ 无障碍已开启\n" else "✘ 无障碍未开启（音量键无法拦截）\n")
            append(if (overlay) "✔ 悬浮窗权限已授予\n" else "✘ 悬浮窗权限未授予\n")
            append(if (mic) "✔ 录音权限已授予\n" else "✘ 录音权限未授予\n")
            append(if (VoiceBarService.running) "✔ 常驻服务运行中" else "· 常驻服务未启动")
        }
    }

    private fun isAccessibilityOn(): Boolean {
        val expected = "$packageName/${KeyAccessibilityService::class.java.name}"
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return flat.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun startServiceNow() {
        try {
            val i = Intent(this, VoiceBarService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
            Logx.d("已请求启动常驻服务")
        } catch (e: Exception) {
            Logx.d("启动失败: ${e.message}")
        }
        status.postDelayed({ refresh() }, 500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
