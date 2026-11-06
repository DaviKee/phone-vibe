package com.voicekey.bridge

/**
 * IMA / DVI ADPCM 4-bit 编码器（有状态，predictor 跨帧连续）。
 * 每字节打包 2 个样本，高 4 位为先采集样本 —— 与 PC 端解码器严格对应。
 */
class AdpcmEncoder {

    private var predictor = 0
    private var stepIndex = 0

    fun reset() {
        predictor = 0
        stepIndex = 0
    }

    fun encode(pcm: ShortArray, count: Int, out: ByteArray, offset: Int = 0): Int {
        var o = offset
        var i = 0
        while (i < count) {
            val hi = encodeOne(pcm[i].toInt())
            val lo = if (i + 1 < count) encodeOne(pcm[i + 1].toInt()) else 0
            out[o++] = ((hi shl 4) or (lo and 0x0F)).toByte()
            i += 2
        }
        return o - offset
    }

    private fun encodeOne(sampleIn: Int): Int {
        val sample = sampleIn.coerceIn(-32768, 32767)
        val step = STEP_TABLE[stepIndex]

        var diff = sample - predictor
        var code = 0
        if (diff < 0) {
            code = 8
            diff = -diff
        }

        var delta = 0
        var vpdiff = step shr 3
        var s = step
        if (diff >= s) { delta = 4; diff -= s; vpdiff += s }
        s = s shr 1
        if (diff >= s) { delta = delta or 2; diff -= s; vpdiff += s }
        s = s shr 1
        if (diff >= s) { delta = delta or 1; vpdiff += s }

        predictor += if (code and 8 != 0) -vpdiff else vpdiff
        if (predictor > 32767) predictor = 32767
        if (predictor < -32768) predictor = -32768

        stepIndex += INDEX_TABLE[delta]
        if (stepIndex < 0) stepIndex = 0
        if (stepIndex > 88) stepIndex = 88

        return code or delta
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val SAMPLES_PER_FRAME = 240          // 15 ms
        const val FRAME_BYTES = 120

        private val STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499,
            2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630,
            9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
            27086, 29794, 32767
        )

        private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
    }
}
