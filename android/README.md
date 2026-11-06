# 音量键语音桥（小米 15 → Win10）

亮屏状态下，按住音量下键说话，松开自动把语音送到电脑；全程**不切前台、不弹全屏、不动正在用的 App**。
只在你按下的那一刻，屏幕底部浮出一条胶囊，松手就消失。

## 为什么不是蓝牙 ATVV

ATVV 的唯一价值是"对接原生 Android TV，免装接收端"。你只对 Win10，Windows 上根本没有 ATVV 消费者，这项收益直接归零——继续用它只剩协议校准成本。

所以这里按 Windows 能吃的方案重新设计：**手机当 GATT Server，电脑用标准 GATT Client 连**。Windows 10 的 BLE 栈对 GATT Client 支持完整（WinRT），Python 的 `bleak` 就能跑，不需要任何驱动。

默认通道却是 **Wi-Fi / UDP**，理由很直白：

| | UDP over Wi-Fi | BLE GATT on Win10 |
|---|---|---|
| 码率余量 | 几百 kbps 随便给 | 实测有效 30–70 kbps，64 kbps 音频贴着上限 |
| 延迟抖动 | 1–5 ms | 连接间隔决定，15–50 ms 波动 |
| 配对/权限 | 无 | 需配对，且 Windows 会缓存 GATT 服务 |
| 断连恢复 | 无状态 | 每次重连重协商 |

两者都能用，App 里可切换。**先跑 UDP 把交互跑顺，再决定要不要换蓝牙。**

## 交互设计

| 手势 | 行为 |
|---|---|
| 按住音量下（≥300ms） | 底部浮出语音条并开始收音 |
| 松开音量下 | 停止收音 → 打包发送 → 显示"已发送"后消失 |
| 音量下短按（<300ms） | 判为误触，丢弃音频，补一次正常的音量减 |
| 长按音量上（600ms） | 向电脑发"确认"（默认按回车） |
| 音量上 + 下同按 | 取消本次，音频丢弃 |

关键点：**录音从按下瞬间就开始**，不等 300ms 阈值。这样"嗯…"这类开口音不会被吃掉；阈值只决定"要不要发出去"，不决定"从哪开始录"。

## 音量键是怎么拦下来的

这是整件事最硬的一环。普通 App 拿不到全局按键；悬浮窗也拿不到（除非抢焦点，那就正好破坏了"不影响原 App"）。

唯一干净的路子是**无障碍服务的 `flagRequestFilterKeyEvents`**：它能在事件到达系统音量控制之前截住，不需要 root，也不需要读屏权限（配置里 `canRetrieveWindowContent="false"`，不碰屏幕内容）。

代价是：装完必须手动去设置里开一次无障碍。HyperOS 上还建议给 App 开自启动 + 电量无限制，否则服务会被清掉。

## 悬浮条

`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`，贴底 96dp，圆角胶囊，左边四根波形柱跟着音量跳动。

不抢焦点、不接触摸，所以下面无论是微信、浏览器还是 WorkBuddy，输入法和滚动都不受影响。

## 协议（UDP / BLE 共用同一套字节）

```
START  01 | sid | codec | srKHz | frameBytes(2,BE) | frameMs
AUDIO  02 | sid | seq(2,BE) | <ADPCM>
END    03 | sid | status(0=正常 1=取消)
CTRL   04 | cmd          1=ACK 2=确认 3=取消
```

编码：16 kHz 单声道 IMA ADPCM，120 字节/帧 = 15 ms，2 帧/包 → 33 包/秒，净码率 64 kbps。
编码器 predictor 跨帧连续（不能每包 reset，否则每包开头一个爆音）。

## 电脑端

**推荐用图形版**：`VoiceKeyBridge.exe`（双击运行，自带运行时，无需 Python）。详见 `../voicekey-bridge-pc/README.md`。

下面是命令行版，用于跨平台或调试：

```bash
pip install bleak sounddevice numpy      # 只有 BLE / 播放才需要

# Wi-Fi（推荐先试这个）
python pc/voicekey_receiver.py --wav last.wav

# 蓝牙
python pc/voicekey_receiver.py --ble
```

要在 WorkBuddy 里真正"用上"这段语音，需要把音频喂成它的麦克风：

1. 装 [VB-CABLE](https://vb-audio.com/Cable/)，系统里会出现 `CABLE Input` / `CABLE Output` 一对虚拟声卡。
2. 接收器加参数 `--play "CABLE Input"`，音频就从虚拟声卡"输出"。
3. WorkBuddy（或任何听写工具）的麦克风选 `CABLE Output`。
4. 手机按住说话 → 电脑这边听到的就是你的声音。

**这一步绕不过去。** 手机没有任何办法把音频"直接塞进"WorkBuddy——它没有开放音频接入接口，中间必须有个东西把网络音频变成系统麦克风。VB-CABLE 就是那个东西。如果你不想装虚拟声卡，也可以只落盘 WAV，再手动拖进 WorkBuddy。

## 编译

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

## 在小米 15 上的首次配置

1. 装 APK → 授予录音、通知权限。
2. ① 开启无障碍（设置 → 更多设置 → 无障碍 → 已下载的服务 → 音量键语音桥）。
3. ② 授予"显示在其他应用上层"（HyperOS 在 设置 → 应用设置 → 应用管理 → 权限 → 特殊权限）。
4. ③ 点"启动常驻服务"，通知栏出现"语音桥待命"。
5. 传输方式选 Wi-Fi，IP 留空（走局域网广播），端口 47823。
6. **HyperOS 额外要做的**：设置 → 应用设置 → 应用管理 → 音量键语音桥 → 省电策略选"无限制"；自启动打开；最近任务里上滑锁定。

## 已知边界

1. **只在亮屏时工作**（`PowerManager.isInteractive`）。息屏下无障碍拿不到按键事件，这是系统行为，改不了。
2. **Wi-Fi 广播依赖同一网段**，且 Win10 防火墙首次会拦 UDP 47823，需要放行。
3. **BLE 通道在 Windows 上吞吐一般**，64 kbps 贴着上限，偶发丢包。丢一两包对语音可懂度影响很小（ADPCM 无重传），但如果你听到断续，切回 UDP。
4. **来电/闹钟期间**音量键会被系统接管，本 App 不介入。
5. 我这边没有真机，只验证了：完整编译通过、APK 可安装、帧协议与 ADPCM 端到端回环（1.0 秒输入 → 0.99 秒 WAV，符合 66 帧截断预期）。**真机手感需要你上手调**，尤其是 300ms / 600ms 两个阈值。
