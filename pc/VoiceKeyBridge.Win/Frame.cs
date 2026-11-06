using System;
using System.Collections.Generic;

namespace VoiceKeyBridge
{
    /// <summary>
    /// 帧协议（与手机端 Frame.kt 一一对应）
    ///   START  01 | sid | codec | srKHz | frameBytes(2,BE) | frameMs
    ///   AUDIO  02 | sid | seq(2,BE) | adpcm
    ///   END    03 | sid | status
    ///   CTRL   04 | cmd
    /// </summary>
    public static class Frame
    {
        public const byte TStart = 0x01;
        public const byte TAudio = 0x02;
        public const byte TEnd = 0x03;
        public const byte TCtrl = 0x04;

        public const byte CtrlAck = 1;
        public const byte CtrlConfirm = 2;
        public const byte CtrlCancel = 3;

        public const int SampleRate = 16000;
    }

    public sealed class VoiceSession
    {
        public int Sid { get; }
        public AdpcmDecoder Decoder { get; } = new();
        public int Bytes { get; private set; }
        public DateTime StartedAt { get; } = DateTime.Now;

        private readonly List<byte> _pcm = new();

        public VoiceSession(int sid) => Sid = sid;

        public byte[] Feed(byte[] adpcm)
        {
            Bytes += adpcm.Length;
            byte[] pcm = Decoder.Decode(adpcm);
            _pcm.AddRange(pcm);
            return pcm;
        }

        public double Seconds => Bytes / 8000.0;

        public byte[] AllPcm => _pcm.ToArray();
    }
}
