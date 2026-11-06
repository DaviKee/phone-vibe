using System;
using System.Collections.Generic;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace VoiceKeyBridge
{
    public sealed class MainForm : Form
    {
        // ---- UI ----
        private readonly Label _lblStatus = new();
        private readonly RadioButton _rbUdp = new() { Text = "Wi-Fi / UDP", Checked = true, AutoSize = true };
        private readonly RadioButton _rbBle = new() { Text = "蓝牙 GATT", AutoSize = true };
        private readonly ComboBox _cmbDevice = new() { DropDownStyle = ComboBoxStyle.DropDownList, Anchor = AnchorStyles.Left | AnchorStyles.Right };
        private readonly ComboBox _cmbConfirm = new() { DropDownStyle = ComboBoxStyle.DropDownList, Anchor = AnchorStyles.Left | AnchorStyles.Right };
        private readonly CheckBox _chkSave = new() { Text = "保存 WAV", AutoSize = true, Checked = true };
        private readonly TextBox _txtDir = new() { Text = @"%USERPROFILE%\Music\VoiceKey", Anchor = AnchorStyles.Left | AnchorStyles.Right };
        private readonly NumericUpDown _numPort = new() { Minimum = 1024, Maximum = 65535, Value = 47823, Anchor = AnchorStyles.Left };
        private readonly Button _btnStart = new() { Text = "开始监听", Height = 40, Dock = DockStyle.Fill };
        private readonly ProgressBar _bar = new() { Minimum = 0, Maximum = 100, Height = 10, Dock = DockStyle.Fill };
        private readonly TextBox _txtLog = new()
        {
            Multiline = true, ReadOnly = true, ScrollBars = ScrollBars.Vertical,
            Dock = DockStyle.Fill, Font = new Font("Consolas", 9f), BackColor = Color.FromArgb(250, 250, 250)
        };

        // ---- runtime ----
        private UdpReceiver? _udp;
        private BleReceiver? _ble;
        private readonly AudioSink _sink = new();
        private readonly Dictionary<int, VoiceSession> _sessions = new();
        private bool _running;
        private DateTime _lastLevel = DateTime.MinValue;

        [DllImport("user32.dll")]
        private static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, UIntPtr dwExtraInfo);

        public MainForm()
        {
            Text = "音量键语音桥 · 电脑端";
            Font = new Font("Microsoft YaHei UI", 9f);
            ClientSize = new Size(640, 720);
            MinimumSize = new Size(560, 620);
            StartPosition = FormStartPosition.CenterScreen;

            BuildUi();
            RefreshDevices();
            _sink.Level += lvl => Ui(() =>
            {
                var now = DateTime.Now;
                if ((now - _lastLevel).TotalMilliseconds < 45) return;
                _lastLevel = now;
                _bar.Value = Math.Clamp((int)(lvl * 100), 0, 100);
            });

            Log("就绪。手机端 App 里选同一种传输方式，然后按住音量下键说话。");
            SetStatus("未开始", Color.DimGray);
        }

        // ---------------------------------------------------------------- UI

        private void BuildUi()
        {
            var root = new TableLayoutPanel
            {
                Dock = DockStyle.Top,
                AutoSize = true,
                AutoSizeMode = AutoSizeMode.GrowAndShrink,
                ColumnCount = 3,
                Padding = new Padding(14, 12, 14, 8)
            };
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 86));
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 80));
            for (int i = 0; i < 10; i++) root.RowStyles.Add(new RowStyle(SizeType.AutoSize));

            int r = 0;

            _lblStatus.AutoSize = true;
            _lblStatus.Font = new Font("Microsoft YaHei UI", 11f, FontStyle.Bold);
            _lblStatus.Text = "未开始";
            root.Controls.Add(RowLabel("状态"), 0, r);
            root.Controls.Add(_lblStatus, 1, r);
            root.SetColumnSpan(_lblStatus, 2);
            r++;

            var flowTransport = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = false };
            flowTransport.Controls.Add(_rbUdp);
            flowTransport.Controls.Add(_rbBle);
            root.Controls.Add(RowLabel("传输方式"), 0, r);
            root.Controls.Add(flowTransport, 1, r);
            root.SetColumnSpan(flowTransport, 2);
            r++;

            var btnRefresh = new Button { Text = "刷新", Dock = DockStyle.Fill };
            btnRefresh.Click += (_, _) => RefreshDevices();
            root.Controls.Add(RowLabel("输出设备"), 0, r);
            root.Controls.Add(_cmbDevice, 1, r);
            root.Controls.Add(btnRefresh, 2, r);
            r++;

            var flowSave = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = false };
            flowSave.Controls.Add(_chkSave);
            _txtDir.Width = 300;
            flowSave.Controls.Add(_txtDir);
            var btnBrowse = new Button { Text = "浏览", Dock = DockStyle.Fill };
            btnBrowse.Click += (_, _) => BrowseDir();
            root.Controls.Add(RowLabel("录音保存"), 0, r);
            root.Controls.Add(flowSave, 1, r);
            root.Controls.Add(btnBrowse, 2, r);
            r++;

            var flowPort = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Fill, WrapContents = false };
            flowPort.Controls.Add(_numPort);
            flowPort.Controls.Add(new Label
            {
                Text = "（手机端填同一个端口；IP 留空即可）",
                AutoSize = true,
                Margin = new Padding(8, 8, 0, 0),
                ForeColor = Color.Gray
            });
            root.Controls.Add(RowLabel("监听端口"), 0, r);
            root.Controls.Add(flowPort, 1, r);
            root.SetColumnSpan(flowPort, 2);
            r++;

            _cmbConfirm.Items.AddRange(new object[] { "按回车（Enter）", "什么都不做" });
            _cmbConfirm.SelectedIndex = 0;
            root.Controls.Add(RowLabel("确认动作"), 0, r);
            root.Controls.Add(_cmbConfirm, 1, r);
            root.SetColumnSpan(_cmbConfirm, 2);
            r++;

            _btnStart.Margin = new Padding(0, 12, 0, 8);
            _btnStart.Click += (_, _) => ToggleRunning();
            root.Controls.Add(_btnStart, 0, r);
            root.SetColumnSpan(_btnStart, 3);
            r++;

            root.Controls.Add(_bar, 0, r);
            root.SetColumnSpan(_bar, 3);
            r++;

            var hint = new Label
            {
                AutoSize = true,
                ForeColor = Color.Gray,
                Text = "提示：要在 WorkBuddy 里用上这段语音，输出设备选 CABLE Input，再把 WorkBuddy 的麦克风设成 CABLE Output。",
                Margin = new Padding(0, 4, 0, 8),
                MaximumSize = new Size(600, 0)
            };
            root.Controls.Add(hint, 0, r);
            root.SetColumnSpan(hint, 3);

            Controls.Add(_txtLog);
            Controls.Add(root);
        }

        private static Label RowLabel(string text) => new()
        {
            Text = text,
            AutoSize = true,
            Anchor = AnchorStyles.Left,
            Margin = new Padding(0, 10, 8, 0)
        };

        private void SetStatus(string text, Color color)
        {
            _lblStatus.Text = text;
            _lblStatus.ForeColor = color;
        }

        private void Ui(Action a)
        {
            if (!IsHandleCreated || IsDisposed) return;
            try { BeginInvoke(a); } catch { }
        }

        private void Log(string msg)
        {
            var line = $"{DateTime.Now:HH:mm:ss}  {msg}\r\n";
            if (InvokeRequired) { Ui(() => AppendRaw(line)); return; }
            AppendRaw(line);
        }

        private void AppendRaw(string line)
        {
            if (_txtLog.TextLength > 40000) _txtLog.Text = _txtLog.Text.Substring(20000);
            _txtLog.AppendText(line);
        }

        private void RefreshDevices()
        {
            _cmbDevice.Items.Clear();
            _cmbDevice.Items.Add("（不播放，只保存 WAV）");
            foreach (var kv in AudioSink.EnumerateDevices())
                _cmbDevice.Items.Add(kv.Value);
            _cmbDevice.SelectedIndex = 0;
            if (_cmbDevice.Items.Count > 1)
            {
                // 有 CABLE Input 就默认选它
                for (int i = 0; i < _cmbDevice.Items.Count; i++)
                {
                    var s = _cmbDevice.Items[i]?.ToString() ?? "";
                    if (s.IndexOf("CABLE Input", StringComparison.OrdinalIgnoreCase) >= 0)
                    {
                        _cmbDevice.SelectedIndex = i;
                        break;
                    }
                }
            }
        }

        private void BrowseDir()
        {
            using var dlg = new FolderBrowserDialog { SelectedPath = ExpandDir() };
            if (dlg.ShowDialog(this) == DialogResult.OK) _txtDir.Text = dlg.SelectedPath;
        }

        private string ExpandDir()
        {
            var p = Environment.ExpandEnvironmentVariables(_txtDir.Text.Trim());
            return string.IsNullOrWhiteSpace(p) ? "" : p;
        }

        // ---------------------------------------------------------------- 启动/停止

        private async void ToggleRunning()
        {
            if (_running) { StopAll(); return; }

            _sessions.Clear();
            _sink.SaveDir = _chkSave.Checked ? ExpandDir() : null;

            int dev = _cmbDevice.SelectedIndex - 1;
            if (dev >= 0)
            {
                try { _sink.StartPlayback(dev); }
                catch (Exception ex) { Log("播放设备打开失败：" + ex.Message); }
            }

            if (_rbUdp.Checked)
            {
                int port = (int)_numPort.Value;
                _udp = new UdpReceiver();
                _udp.Datagram += OnDatagram;
                _udp.Log += m => Log(m);
                try
                {
                    _udp.Start(port);
                    Log($"UDP 已监听 0.0.0.0:{port}");
                }
                catch (Exception ex)
                {
                    Log("UDP 启动失败：" + ex.Message + "（端口被占用？换一个试试）");
                    _udp = null;
                    StopAll();
                    return;
                }
            }
            else
            {
                try
                {
                    _ble = new BleReceiver();
                    _ble.Datagram += OnDatagram;
                    _ble.Log += m => Log(m);
                    _ble.ConnectionChanged += c => Ui(() => SetStatus(c ? "● 蓝牙已连接" : "… 等待蓝牙连接", c ? Color.DarkGreen : Color.DimGray));
                    _ble.Start();
                }
                catch (Exception ex)
                {
                    Log("蓝牙启动失败：" + ex.Message);
                    _ble = null;
                    StopAll();
                    return;
                }
            }

            _running = true;
            _btnStart.Text = "停止监听";
            SetStatus(_rbUdp.Checked ? "… 等待手机按键" : "… 等待蓝牙连接", Color.DarkSlateBlue);
            await System.Threading.Tasks.Task.CompletedTask;
        }

        private void StopAll()
        {
            _udp?.Stop(); _udp = null;
            _ble?.Stop(); _ble = null;
            _sink.StopPlayback();
            _running = false;
            _btnStart.Text = "开始监听";
            _bar.Value = 0;
            SetStatus("已停止", Color.DimGray);
        }

        // ---------------------------------------------------------------- 帧处理

        private void OnDatagram(byte[] d)
        {
            if (d.Length < 2) return;
            try
            {
                switch (d[0])
                {
                    case Frame.TStart when d.Length >= 7:
                        {
                            int sid = d[1];
                            lock (_sessions) _sessions[sid] = new VoiceSession(sid);
                            _sink.BeginSession();
                            Log($"▶ 开始接收 #{sid:X2}（{d[3]}kHz / {((d[4] << 8) | d[5])}B 帧）");
                            Ui(() => SetStatus("● 正在接收语音…", Color.DarkGreen));
                            break;
                        }
                    case Frame.TAudio when d.Length > 4:
                        {
                            int sid = d[1];
                            VoiceSession? s;
                            lock (_sessions) _sessions.TryGetValue(sid, out s);
                            if (s != null)
                            {
                                var payload = new byte[d.Length - 4];
                                Buffer.BlockCopy(d, 4, payload, 0, payload.Length);
                                _sink.Feed(s.Feed(payload));
                            }
                            break;
                        }
                    case Frame.TEnd when d.Length >= 3:
                        {
                            int sid = d[1];
                            bool cancelled = d[2] == 1;
                            VoiceSession? s;
                            lock (_sessions) { _sessions.TryGetValue(sid, out s); _sessions.Remove(sid); }
                            string? wav = _sink.EndSession();
                            if (s != null)
                            {
                                Log(cancelled
                                    ? $"✗ #{sid:X2} 已取消"
                                    : $"✓ #{sid:X2} 完成 {s.Seconds:F2}s" + (wav != null ? $" → {wav}" : ""));
                            }
                            Ui(() => SetStatus("… 等待手机按键", Color.DarkSlateBlue));
                            break;
                        }
                    case Frame.TCtrl when d.Length >= 2:
                        {
                            if (d[1] == Frame.CtrlConfirm)
                            {
                                Log("⌨ 收到「确认」手势");
                                if (_cmbConfirm.SelectedIndex == 0) PressEnter();
                            }
                            else if (d[1] == Frame.CtrlCancel)
                            {
                                Log("✗ 收到「取消」手势");
                            }
                            break;
                        }
                }
            }
            catch (Exception ex)
            {
                Log("处理数据出错：" + ex.Message);
            }
        }

        private static void PressEnter()
        {
            keybd_event(0x0D, 0, 0, UIntPtr.Zero);
            keybd_event(0x0D, 0, 0x0002, UIntPtr.Zero);
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            StopAll();
            _sink.Dispose();
            base.OnFormClosing(e);
        }
    }
}
