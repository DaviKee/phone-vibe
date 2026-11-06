# 在 Windows 上继续这个项目

这个包里有三部分：手机端（Android/Kotlin）、电脑端（C# WinForms）、已经编译好的成品。

---

## 一、只想跑起来（不写代码）

解压后进 `bin\`：

1. 双击 `VoiceKeyBridge.exe`（无需装 .NET，运行时已打包）
2. SmartScreen 拦截 → 「更多信息」→「仍要运行」
3. 防火墙弹窗 → **勾上「专用网络」** → 允许
4. 手机装 `VoiceKey-bridge-debug.apk`，两端传输方式选同一个

---

## 二、想改电脑端代码

### 装环境（两选一）

**A. Visual Studio 2022（推荐，调试体验最好）**

```
winget install Microsoft.VisualStudio.2022.Community
```
安装时勾选「.NET 桌面开发」工作负载。17.8 及以上版本自带 .NET 8 SDK，不用另装。

**B. VS Code + 命令行**

```
winget install Microsoft.DotNet.SDK.8
winget install Microsoft.VisualStudioCode
```
VS Code 里装扩展 `C# Dev Kit`。

### 打开与运行

- **VS**：直接 `文件 → 打开 → 项目/解决方案`，选 `VoiceKeyBridge.Win\VoiceKeyBridge.Win.csproj`，F5 直接跑
- **命令行**（在包根目录）：
  ```powershell
  dotnet run --project VoiceKeyBridge.Win
  ```

首次会还原 NAudio 包，几十秒。之后改代码直接 F5。

### 重新打包成单文件 exe

```powershell
dotnet publish VoiceKeyBridge.Win -c Release -r win-x64 --self-contained true -o out
```
产物在 `out\VoiceKeyBridge.exe`，约 70 MB。

---

## 三、代码在哪、改什么

```
VoiceKeyBridge.Win\
├── Program.cs        入口，启动窗体
├── MainForm.cs       全部界面逻辑：布局、配置读写、日志、电平条   ← 改 UI 看这里
├── UdpReceiver.cs    UDP 收包（默认通道）
├── BleReceiver.cs    蓝牙 GATT 收包（WinRT）
├── Frame.cs          帧协议：类型 + 会话号 + 序号 + 载荷         ← 改协议先改这里
├── Adpcm.cs          IMA ADPCM 解码（4bit / 16kHz）
└── AudioSink.cs      声卡输出、WAV 落盘、常开静音流
```

几个最常改的点：

| 想改什么 | 位置 |
|---|---|
| 默认端口 47823 | `MainForm.cs` 里 `portBox` 的初始值 |
| 日志格式 / 提示文案 | `MainForm.cs` 的 `Log()` 与各 `Text` 赋值 |
| 音量条灵敏度 | `AudioSink.cs` 里计算 RMS 的地方 |
| 收到「确认」时按回车的实现 | `MainForm.cs` 里 `SendEnter()`（走 `SendInput`） |
| 换成自定义协议 | 先改 `Frame.cs`，手机端 `Frame.kt` 要同步改 |

> ⚠️ **协议是两端强耦合的**。`Frame.cs` 和手机端 `android\app\src\main\java\com\voicekey\bridge\Frame.kt` 的常量必须一字不差，包括 `CODEC_ADPCM16K = 0x01`、`SAMPLES_PER_FRAME = 240`（15 ms @16 kHz）、`FRAME_BYTES = 120`。改了一边不改另一边，表现是"有日志但没声音"。

---

## 四、改手机端（如果不是只调电脑端）

需要 Android Studio（含 SDK 34 + JDK 17）：

```powershell
winget install Google.AndroidStudio
```
打开 `android\` 目录，等 Gradle 同步完即可。首次同步会下载 Gradle 8.7 和依赖，约 5–10 分钟。

手机端核心文件在 `android\app\src\main\java\com\voicekey\bridge\`：

| 文件 | 职责 |
|---|---|
| `KeyAccessibilityService.kt` | 拦截音量键、识别长按/双按手势 ← 手感问题看这里 |
| `VoiceBarService.kt` | 前台服务、录音、分包、会话生命周期 |
| `OverlayBar.kt` | 底部胶囊悬浮条 |
| `Cfg.kt` | 阈值（300ms / 600ms）、传输方式、端口 |
| `UdpSender.kt` / `BleSender.kt` | 两个通道的实现 |

编译 APK：
```powershell
cd android
.\gradlew assembleDebug
```

---

## 五、调试建议

**先确认链路，再调手感。** 顺序别颠倒：

1. **UDP 通不通**：电脑端点开始监听，手机按一下，电脑日志没反应 → 查防火墙、查两端 IP 是否同网段
2. **有包没声音**：看日志里有没有 `✓ #xx 完成 x.xx s`。有说明协议通、只是声卡选错
3. **有声音但识别不准**：多半是电平太低。先看电平条峰值，正常说话应该到 60% 以上
4. **蓝牙模式起不来**：WinRT 在部分老适配器上激活失败。这是已知风险，UDP 是默认路径，建议就用 UDP

**抓包定位协议问题**：`UdpReceiver.cs` 里收到包时先 `Console.WriteLine` 打出前 8 字节，格式应该是 `[类型, 会话号, 序号高, 序号低, ...]`，类型 `01` 起始、`02` 音频、`03` 结束、`04` 控制。

---

## 六、两个已知的坑

**声卡独占**：如果 WorkBuddy 那边已经占用了 `CABLE Input`，本程序写入会失败。解决：本程序输出选 `CABLE Input`，WorkBuddy 麦克风选 `CABLE Output`，别选反。

**HyperOS 杀后台**：手机端必须设自启动、电池无限制、多任务上滑锁定，否则锁屏几分钟服务就没了，表现是"按键完全没反应"。
