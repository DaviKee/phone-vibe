using System;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;

namespace VoiceKeyBridge
{
    /// <summary>Wi-Fi / UDP 接收。手机端 IP 留空时走局域网广播，这里监听 0.0.0.0 即可。</summary>
    public sealed class UdpReceiver : IDisposable
    {
        private UdpClient? _client;
        private CancellationTokenSource? _cts;

        public event Action<byte[]>? Datagram;
        public event Action<string>? Log;

        public bool Running => _client != null;

        public void Start(int port)
        {
            Stop();
            _cts = new CancellationTokenSource();
            var client = new UdpClient();
            client.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            client.Client.Bind(new IPEndPoint(IPAddress.Any, port));
            try { client.Client.ReceiveBufferSize = 1 << 20; } catch { }
            _client = client;

            var token = _cts.Token;
            _ = Task.Run(async () =>
            {
                while (!token.IsCancellationRequested)
                {
                    try
                    {
                        UdpReceiveResult r = await client.ReceiveAsync(token);
                        Datagram?.Invoke(r.Buffer);
                    }
                    catch (OperationCanceledException) { break; }
                    catch (ObjectDisposedException) { break; }
                    catch (SocketException se)
                    {
                        Log?.Invoke("UDP: " + se.Message);
                    }
                    catch (Exception ex)
                    {
                        Log?.Invoke("UDP: " + ex.Message);
                    }
                }
            }, token);
        }

        public void Stop()
        {
            try { _cts?.Cancel(); } catch { }
            try { _client?.Dispose(); } catch { }
            _client = null;
            _cts = null;
        }

        public void Dispose() => Stop();
    }
}
