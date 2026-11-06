using System;
using System.Collections.Generic;
using System.IO;
using NAudio.Wave;

namespace VoiceKeyBridge
{
    /// <summary>
    /// 播放与落盘。
    /// 播放流常开（空闲时喂静音），避免每段开关声卡产生的爆音——
    /// 那个爆音会被 WorkBuddy 的语音识别当成杂音。
    /// </summary>
    public sealed class AudioSink : IDisposable
    {
        private WaveOutEvent? _waveOut;
        private BufferedWaveProvider? _buffer;
        private WaveFileWriter? _writer;
        private string? _sessionWav;

        public const int SampleRate = 16000;

        public bool PlaybackOn { get; private set; }
        public string? SaveDir { get; set; }

        public event Action<float>? Level;

        public static List<KeyValuePair<int, string>> EnumerateDevices()
        {
            var list = new List<KeyValuePair<int, string>>();
            try
            {
                int n = WaveOut.DeviceCount;
                for (int i = 0; i < n; i++)
                {
                    var caps = WaveOut.GetCapabilities(i);
                    list.Add(new KeyValuePair<int, string>(i, $"[{i}] {caps.ProductName}"));
                }
            }
            catch
            {
                // 没有声卡就返回空列表
            }
            return list;
        }

        public void StartPlayback(int deviceNumber)
        {
            StopPlayback();
            try
            {
                _buffer = new BufferedWaveProvider(new WaveFormat(SampleRate, 16, 1))
                {
                    DiscardOnBufferOverflow = true,
                    BufferDuration = TimeSpan.FromSeconds(2),
                    ReadFully = true
                };
                _waveOut = new WaveOutEvent
                {
                    DeviceNumber = deviceNumber,
                    DesiredLatency = 120,
                    NumberOfBuffers = 3
                };
                _waveOut.Init(_buffer);
                _waveOut.Play();
                PlaybackOn = true;
            }
            catch (Exception)
            {
                PlaybackOn = false;
                StopPlayback();
                throw;
            }
        }

        public void StopPlayback()
        {
            try { _waveOut?.Stop(); } catch { }
            try { _waveOut?.Dispose(); } catch { }
            _waveOut = null;
            _buffer = null;
            PlaybackOn = false;
        }

        public void BeginSession()
        {
            _sessionWav = null;
            if (string.IsNullOrEmpty(SaveDir)) return;
            try
            {
                Directory.CreateDirectory(SaveDir!);
                _sessionWav = Path.Combine(
                    SaveDir!, $"voice-{DateTime.Now:HHmmss-fff}.wav");
                _writer = new WaveFileWriter(_sessionWav, new WaveFormat(SampleRate, 16, 1));
            }
            catch
            {
                _writer = null;
                _sessionWav = null;
            }
        }

        public void Feed(byte[] pcm)
        {
            _buffer?.AddSamples(pcm, 0, pcm.Length);
            try { _writer?.Write(pcm, 0, pcm.Length); } catch { }
            Level?.Invoke(Rms(pcm));
        }

        public string? EndSession()
        {
            try { _writer?.Dispose(); } catch { }
            _writer = null;
            var p = _sessionWav;
            _sessionWav = null;
            return p;
        }

        private static float Rms(byte[] pcm)
        {
            int n = pcm.Length / 2;
            if (n == 0) return 0f;
            double acc = 0;
            for (int i = 0; i < pcm.Length; i += 2)
            {
                short s = (short)(pcm[i] | (pcm[i + 1] << 8));
                acc += (double)s * s;
            }
            double rms = Math.Sqrt(acc / n);
            if (rms < 1) return 0f;
            double db = 20 * Math.Log10(rms / 32768.0);
            return (float)Math.Clamp((db + 60) / 55, 0, 1);
        }

        public void Dispose()
        {
            try { _writer?.Dispose(); } catch { }
            StopPlayback();
        }
    }
}
