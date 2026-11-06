using System;

namespace VoiceKeyBridge
{
    /// <summary>与手机端严格对应的 IMA ADPCM 4-bit 解码器（有状态）。</summary>
    public sealed class AdpcmDecoder
    {
        private static readonly int[] Step =
        {
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
            34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
            157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
            724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499,
            2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630,
            9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
            27086, 29794, 32767
        };

        private static readonly int[] IndexTable = { -1, -1, -1, -1, 2, 4, 6, 8 };

        private int _predictor;
        private int _index;

        public void Reset()
        {
            _predictor = 0;
            _index = 0;
        }

        private short Nibble(int code)
        {
            int step = Step[_index];
            int diff = step >> 3;
            if ((code & 4) != 0) diff += step;
            if ((code & 2) != 0) diff += step >> 1;
            if ((code & 1) != 0) diff += step >> 2;

            _predictor += (code & 8) != 0 ? -diff : diff;
            if (_predictor > 32767) _predictor = 32767;
            if (_predictor < -32768) _predictor = -32768;

            _index += IndexTable[code & 7];
            if (_index < 0) _index = 0;
            if (_index > 88) _index = 88;

            return (short)_predictor;
        }

        /// <summary>每个字节还原成 2 个 16-bit 采样（高 4 位在前）。</summary>
        public byte[] Decode(byte[] data)
        {
            var pcm = new byte[data.Length * 4];
            int p = 0;
            foreach (byte b in data)
            {
                short hi = Nibble((b >> 4) & 0x0F);
                short lo = Nibble(b & 0x0F);
                pcm[p++] = (byte)(hi & 0xFF);
                pcm[p++] = (byte)((hi >> 8) & 0xFF);
                pcm[p++] = (byte)(lo & 0xFF);
                pcm[p++] = (byte)((lo >> 8) & 0xFF);
            }
            return pcm;
        }
    }
}
