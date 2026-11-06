using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace VoiceKeyBridge
{
    /// <summary>
    /// 蓝牙 GATT 客户端（WinRT）。手机是 Server，电脑是 Client。
    /// 只订阅 AUDIO 特征的通知，收到的是与 UDP 完全相同的字节流。
    /// </summary>
    public sealed class BleReceiver : IDisposable
    {
        private static readonly Guid ServiceUuid = Guid.Parse("5a3c0001-7e1d-4c8b-9f2a-3d6e5b1c0a97");
        private static readonly Guid CtrlUuid = Guid.Parse("5a3c0002-7e1d-4c8b-9f2a-3d6e5b1c0a97");
        private static readonly Guid AudioUuid = Guid.Parse("5a3c0003-7e1d-4c8b-9f2a-3d6e5b1c0a97");

        private BluetoothLEAdvertisementWatcher? _watcher;
        private BluetoothLEDevice? _device;
        private GattDeviceService? _service;
        private GattCharacteristic? _audio;
        private GattCharacteristic? _ctrl;
        private bool _connecting;

        public event Action<byte[]>? Datagram;
        public event Action<string>? Log;
        public event Action<bool>? ConnectionChanged;

        public bool Connected => _audio != null;

        public void Start()
        {
            Stop();
            _watcher = new BluetoothLEAdvertisementWatcher
            {
                ScanningMode = BluetoothLEScanningMode.Active
            };
            _watcher.Received += OnAdvertisement;
            _watcher.Start();
            Log?.Invoke("蓝牙: 开始扫描…");
        }

        private void OnAdvertisement(BluetoothLEAdvertisementWatcher sender,
            BluetoothLEAdvertisementReceivedEventArgs args)
        {
            if (_connecting || _audio != null) return;
            bool match = false;
            foreach (Guid g in args.Advertisement.ServiceUuids)
            {
                if (g == ServiceUuid) { match = true; break; }
            }
            if (!match) return;
            _ = ConnectAsync(args.BluetoothAddress);
        }

        private async Task ConnectAsync(ulong address)
        {
            if (_connecting) return;
            _connecting = true;
            try
            {
                Log?.Invoke("蓝牙: 发现设备，正在连接…");
                var dev = await BluetoothLEDevice.FromBluetoothAddressAsync(address);
                if (dev == null)
                {
                    Log?.Invoke("蓝牙: 取设备失败");
                    return;
                }

                GattDeviceServicesResult svcRes =
                    await dev.GetGattServicesForUuidAsync(ServiceUuid, BluetoothCacheMode.Uncached);
                if (svcRes.Status != GattCommunicationStatus.Success || svcRes.Services.Count == 0)
                {
                    Log?.Invoke("蓝牙: 未找到服务（" + svcRes.Status + "），可能需要在系统里先配对");
                    dev.Dispose();
                    return;
                }

                GattDeviceService svc = svcRes.Services[0];
                GattCharacteristicsResult chRes =
                    await svc.GetCharacteristicsForUuidAsync(AudioUuid, BluetoothCacheMode.Uncached);
                if (chRes.Status != GattCommunicationStatus.Success || chRes.Characteristics.Count == 0)
                {
                    Log?.Invoke("蓝牙: 未找到音频特征（" + chRes.Status + "）");
                    dev.Dispose();
                    return;
                }

                GattCharacteristic audio = chRes.Characteristics[0];
                GattCommunicationStatus cfg =
                    await audio.WriteClientCharacteristicConfigurationDescriptorAsync(
                        GattClientCharacteristicConfigurationDescriptorValue.Notify);
                if (cfg != GattCommunicationStatus.Success)
                {
                    Log?.Invoke("蓝牙: 订阅失败（" + cfg + "）");
                    dev.Dispose();
                    return;
                }

                audio.ValueChanged += OnValueChanged;

                GattCharacteristicsResult ctrlRes =
                    await svc.GetCharacteristicsForUuidAsync(CtrlUuid, BluetoothCacheMode.Uncached);
                if (ctrlRes.Status == GattCommunicationStatus.Success && ctrlRes.Characteristics.Count > 0)
                    _ctrl = ctrlRes.Characteristics[0];

                _device = dev;
                _service = svc;
                _audio = audio;
                _watcher?.Stop();
                Log?.Invoke("蓝牙: 已连接并订阅音频");
                ConnectionChanged?.Invoke(true);
            }
            catch (Exception ex)
            {
                Log?.Invoke("蓝牙: " + ex.Message);
            }
            finally
            {
                _connecting = false;
            }
        }

        private void OnValueChanged(GattCharacteristic sender, GattValueChangedEventArgs args)
        {
            try
            {
                IBuffer buffer = args.CharacteristicValue;
                var data = new byte[buffer.Length];
                using (var reader = DataReader.FromBuffer(buffer))
                {
                    reader.ReadBytes(data);
                }
                Datagram?.Invoke(data);
            }
            catch (Exception ex)
            {
                Log?.Invoke("蓝牙读取: " + ex.Message);
            }
        }

        /// <summary>可选：从电脑发一条控制帧给手机（目前没用到）</summary>
        public async void SendControl(byte cmd)
        {
            var c = _ctrl;
            if (c == null) return;
            try
            {
                var writer = new DataWriter();
                writer.WriteBytes(new[] { Frame.TCtrl, cmd });
                await c.WriteValueAsync(writer.DetachBuffer());
            }
            catch { }
        }

        public void Stop()
        {
            try { _watcher?.Stop(); } catch { }
            if (_watcher != null) _watcher.Received -= OnAdvertisement;
            _watcher = null;

            if (_audio != null) _audio.ValueChanged -= OnValueChanged;
            _audio = null;
            _ctrl = null;

            try { _service?.Dispose(); } catch { }
            _service = null;
            try { _device?.Dispose(); } catch { }
            _device = null;
            ConnectionChanged?.Invoke(false);
        }

        public void Dispose() => Stop();
    }
}
