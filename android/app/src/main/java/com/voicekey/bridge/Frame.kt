package com.voicekey.bridge

/**
 * 手机 ↔ 电脑 的极简帧协议。UDP 与 BLE 共用同一套字节。
 *
 *  START  01 | sid | codec | srKHz | frameBytes(2, BE) | frameMs
 *  AUDIO  02 | sid | seq(2, BE) | <adpcm bytes>
 *  END    03 | sid | status(0=正常 1=取消)
 *  CTRL   04 | cmd   (电脑 → 手机：1=ACK 2=CONFIRM 3=CANCEL)
 */
object Frame {

    const val T_START: Byte = 0x01
    const val T_AUDIO: Byte = 0x02
    const val T_END: Byte = 0x03
    const val T_CTRL: Byte = 0x04

    const val CODEC_ADPCM16K: Byte = 0x01

    const val CTRL_ACK: Byte = 0x01
    const val CTRL_CONFIRM: Byte = 0x02
    const val CTRL_CANCEL: Byte = 0x03

    fun start(sid: Int, frameMs: Int): ByteArray = byteArrayOf(
        T_START, sid.toByte(), CODEC_ADPCM16K,
        (AdpcmEncoder.SAMPLE_RATE / 1000).toByte(),
        ((AdpcmEncoder.FRAME_BYTES shr 8) and 0xFF).toByte(),
        (AdpcmEncoder.FRAME_BYTES and 0xFF).toByte(),
        frameMs.toByte()
    )

    fun audio(sid: Int, seq: Int, data: ByteArray, len: Int): ByteArray {
        val out = ByteArray(4 + len)
        out[0] = T_AUDIO
        out[1] = sid.toByte()
        out[2] = ((seq shr 8) and 0xFF).toByte()
        out[3] = (seq and 0xFF).toByte()
        System.arraycopy(data, 0, out, 4, len)
        return out
    }

    fun ctrl(cmd: Int): ByteArray = byteArrayOf(T_CTRL, cmd.toByte())

    fun end(sid: Int, cancelled: Boolean): ByteArray =
        byteArrayOf(T_END, sid.toByte(), if (cancelled) 1 else 0)
}
