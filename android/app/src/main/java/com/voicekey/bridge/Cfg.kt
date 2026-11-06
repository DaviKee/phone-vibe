package com.voicekey.bridge

import android.content.Context

/** 全局配置（SharedPreferences） */
object Cfg {

    const val TRANSPORT_WIFI = "wifi"
    const val TRANSPORT_BLE = "ble"

    private fun p(ctx: Context) = ctx.getSharedPreferences("voicekey", Context.MODE_PRIVATE)

    fun transport(ctx: Context): String =
        p(ctx).getString("transport", TRANSPORT_WIFI) ?: TRANSPORT_WIFI

    fun setTransport(ctx: Context, v: String) =
        p(ctx).edit().putString("transport", v).apply()

    /** 电脑 IP，留空则发局域网广播 */
    fun pcIp(ctx: Context): String = p(ctx).getString("pc_ip", "") ?: ""

    fun setPcIp(ctx: Context, v: String) = p(ctx).edit().putString("pc_ip", v).apply()

    fun port(ctx: Context): Int = p(ctx).getInt("port", 47823)

    fun setPort(ctx: Context, v: Int) = p(ctx).edit().putInt("port", v).apply()

    /** 按住多久算"长按"（毫秒） */
    fun holdMs(ctx: Context): Int = p(ctx).getInt("hold_ms", 300)

    fun setHoldMs(ctx: Context, v: Int) = p(ctx).edit().putInt("hold_ms", v).apply()
}
