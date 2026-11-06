# 手机音量键 → 电脑语音输入桥

按住手机音量下键说话，松开后语音自动进入 Windows 电脑的麦克风通道，全程不离开当前 App。

## 它是怎么工作的

```
┌─────────────┐   音量键    ┌──────────────┐   UDP / BLE   ┌──────────────┐   虚拟声卡   ┌──────────┐
│  Android    │──────────▶│ 前台服务      │─────────────▶│ Windows 程序  │────────────▶│  任意应用  │
│  音量下键    │  无障碍拦截  │ 录音+ADPCM编码│   64 kbps     │ 解码+播放     │  CABLE Input │  (WorkBuddy) │
└─────────────┘            └──────────────┘               └──────────────┘               └──────────┘
```

- **按键拦截**：无障碍服务 `flagRequestFilterKeyEvents`，在事件到达系统音量控制前截住，不需要 root，也不读取屏幕内容。
- **音频编码**：16 kHz 单声道 IMA ADPCM，4 bit/样本 → **64 kbps**，一帧 15 ms（240 样本 / 120 字节）。
- **传输**：默认 Wi-Fi/UDP（抖动小、无配对）；可选 BLE GATT（手机当 Server）。两条通道共用同一套帧格式。
- **落地**：电脑端把 PCM 写进常开的声卡流。配合 [VB-CABLE](https://vb-audio.com/Cable/) 即可让目标软件把它当成麦克风。

## 交互

| 手势 | 行为 |
|---|---|
| 按住音量下 ≥ 300 ms | 底部浮出胶囊条，开始收音 |
| 松开 | 停止 → 打包发送 → 显示「已发送」后消失 |
| 音量下短按 | 判为误触，丢弃音频，补一次正常音量减 |
| 长按音量上 ≥ 600 ms | 向电脑发「确认」（默认回车） |
| 音量上 + 下同按 | 取消，音频丢弃 |

录音从按下的瞬间开始，不等阈值——阈值只决定「发不发」。不到阈值的音频先存在本地缓冲，短按就整段丢掉，够长才连同缓冲一起补发。否则开口第一个音节会被吃掉。

## 目录

```
android/         手机端（Kotlin，minSdk 26，无第三方依赖）
pc/              电脑端（C# / WinForms / .NET 8 / NAudio）
python-cli/      命令行版接收端，跨平台调试用
docs/            在 Windows 上继续开发的说明
```

## 快速开始

### 手机

安装 `VoiceKey-bridge-debug.apk`，然后：

1. 授予录音、通知权限
2. 开启无障碍：设置 → 更多设置 → 无障碍 → 已下载的服务 → 音量键语音桥
3. 授予「显示在其他应用上层」
4. 点「启动常驻服务」，通知栏出现「语音桥待命」

HyperOS / MIUI 必须额外设置：省电策略「无限制」、允许自启动、最近任务里上滑锁定。否则锁屏几分钟服务被清掉，表现为按键完全没反应。

### 电脑

**图形版**：双击 `VoiceKeyBridge.exe`（自带运行时，无需装 .NET）。首次运行会被 SmartScreen 拦一次，点「更多信息 → 仍要运行」；防火墙弹窗务必勾上「专用网络」。

**命令行版**：

```bash
pip install sounddevice
python python-cli/voicekey_receiver.py --wav last.wav
```

### 接进目标软件

装 [VB-CABLE](https://vb-audio.com/Cable/)：

- 本程序输出设备选 `CABLE Input`
- 目标软件（WorkBuddy 等）的麦克风选 `CABLE Output`

## 帧协议

两端强耦合，改一边必须同步改另一边。多字节字段为**大端**。

| 类型 | 格式 | 说明 |
|---|---|---|
| `0x01` START | `01 \| sid \| codec \| srKHz \| frameBytes(2) \| frameMs` | 会话开始，codec `0x01` = ADPCM 16k |
| `0x02` AUDIO | `02 \| sid \| seq(2) \| adpcm...` | 音频数据，一包含 1–4 帧 |
| `0x03` END | `03 \| sid \| status` | 会话结束，status 0 正常 / 1 取消 |
| `0x04` CTRL | `04 \| cmd` | cmd: 1 应答 / 2 确认 / 3 取消 |

- `sid`：会话号，8 bit，循环递增
- 每包帧数随 MTU 变化：BLE 下按 `(MTU-7)/120` 计算并夹在 1–4；UDP 固定 2 帧/包（30 ms）
- ADPCM 的 predictor / step index **跨帧连续**，解码端不能每帧重置，否则会有周期性爆音

## 构建

### 电脑端

```powershell
dotnet publish pc/VoiceKeyBridge.Win -c Release -r win-x64 --self-contained true -o out
```

### 手机端

用 Android Studio 打开 `android/`（需要 SDK 34 + JDK 17）。工程没有提交 Gradle Wrapper，首次打开时 AS 会提示生成；也可以本机装 Gradle 8.7 后执行 `gradle wrapper`。

```bash
cd android
gradle assembleDebug     # 或 AS 里 Build → Build APK(s)
```

## 已知限制

- **BLE 在 Windows 上是次要路径**。WinRT 的 GATT 在部分老适配器上激活会失败，且实测吞吐 30–70 kbps，64 kbps 音频贴着天花板。建议用 UDP。
- **手机端拦截依赖无障碍服务**。系统或安全软件清理无障碍服务后按键失效，需要重新开启。
- **接收端必须常驻**。程序退出后目标软件就收不到音频，等同于麦克风被拔掉。
- **未在真机长期验证**。协议与编解码有回环测试（1.0 s 输入 → 0.99 s 输出，差值来自 66 帧截断），但手势阈值（300 / 600 ms）实际手感仍需按个人习惯调。

## 许可

个人项目，未附许可协议。如需复用请先联系。
