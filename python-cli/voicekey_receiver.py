#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
音量键语音桥 —— 电脑端接收器（Win10 / macOS / Linux 通用）

两种通道，二选一：
    UDP   python voicekey_receiver.py               # 默认，监听 47823
    BLE   python voicekey_receiver.py --ble

可选动作：
    --play "CABLE Input"   把解码后的音频实时播到指定设备（通常是 VB-CABLE 虚拟声卡，
                           再把 WorkBuddy 的麦克风设成 CABLE Output）
    --wav out.wav          同时落盘一段 WAV
    --confirm enter        收到"确认"手势时按一次回车

依赖：
    pip install bleak sounddevice numpy      # BLE / 播放 才需要
"""

from __future__ import annotations

import argparse
import socket
import struct
import sys
import threading
import time
import wave

SERVICE_UUID = "5a3c0001-7e1d-4c8b-9f2a-3d6e5b1c0a97"
CHAR_AUDIO = "5a3c0003-7e1d-4c8b-9f2a-3d6e5b1c0a97"
CHAR_CTRL = "5a3c0002-7e1d-4c8b-9f2a-3d6e5b1c0a97"

T_START, T_AUDIO, T_END, T_CTRL = 0x01, 0x02, 0x03, 0x04
CTRL_ACK, CTRL_CONFIRM, CTRL_CANCEL = 1, 2, 3

STEP_TABLE = [
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
    34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
    157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
    724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499,
    2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630,
    9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623,
    27086, 29794, 32767,
]
INDEX_TABLE = [-1, -1, -1, -1, 2, 4, 6, 8]


class AdpcmDecoder:
    """有状态：predictor 必须跨包连续，否则每包开头都会有爆音。"""

    def __init__(self):
        self.predictor = 0
        self.index = 0

    def reset(self):
        self.predictor = 0
        self.index = 0

    def _nib(self, code: int) -> int:
        step = STEP_TABLE[self.index]
        diff = step >> 3
        if code & 4:
            diff += step
        if code & 2:
            diff += step >> 1
        if code & 1:
            diff += step >> 2
        self.predictor += -diff if (code & 8) else diff
        self.predictor = max(-32768, min(32767, self.predictor))
        self.index = max(0, min(88, self.index + INDEX_TABLE[code & 7]))
        return self.predictor

    def decode(self, data: bytes) -> bytes:
        out = bytearray(len(data) * 4)
        pos = 0
        for b in data:
            struct.pack_into("<h", out, pos, self._nib((b >> 4) & 0x0F))
            struct.pack_into("<h", out, pos + 2, self._nib(b & 0x0F))
            pos += 4
        return bytes(out)


class Sink:
    """把解码后的 PCM 交给声卡 / 写文件 / 计数"""

    def __init__(self, play_device: str | None, wav_path: str | None):
        self.play_device = play_device
        self.wav_path = wav_path
        self.stream = None
        self.sd = None
        self.pcm = bytearray() if wav_path else None
        if play_device:
            try:
                import sounddevice as sd
                import numpy as np
                self.sd = sd
                names = [d["name"] for d in sd.query_devices()]
                idx = next((i for i, n in enumerate(names) if play_device.lower() in n.lower()), None)
                if idx is None:
                    print(f"!! 找不到输出设备「{play_device}」，可用设备：")
                    for i, n in enumerate(names):
                        print(f"   [{i}] {n}")
                    self.play_device = None
                else:
                    self.stream = sd.OutputStream(
                        samplerate=16000, channels=1, dtype="int16", device=idx
                    )
                    self.stream.start()
                    print(f"播放设备: [{idx}] {names[idx]}")
            except ImportError:
                print("!! 未安装 sounddevice / numpy，改为只落盘")
                self.play_device = None

    def feed(self, pcm: bytes):
        if self.pcm is not None:
            self.pcm.extend(pcm)
        if self.stream is not None:
            import numpy as np
            self.stream.write(np.frombuffer(pcm, dtype=np.int16).reshape(-1, 1))

    def flush(self, secs: float):
        """一段语音结束：落盘、清空缓冲，但**保持声卡流常开**（避免每段开关流产生的爆音）。"""
        if self.wav_path and self.pcm is not None:
            with wave.open(self.wav_path, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(16000)
                w.writeframes(bytes(self.pcm))
            print(f"已保存 {self.wav_path}（{secs:.2f} 秒）")
            self.pcm = bytearray()

    def shutdown(self):
        if self.stream is not None:
            try:
                self.stream.stop()
                self.stream.close()
            except Exception:
                pass
            self.stream = None


def press_enter():
    if sys.platform != "win32":
        return
    import ctypes
    KEYEVENTF_KEYUP = 0x0002
    VK_RETURN = 0x0D
    ctypes.windll.user32.keybd_event(VK_RETURN, 0, 0, 0)
    ctypes.windll.user32.keybd_event(VK_RETURN, 0, KEYEVENTF_KEYUP, 0)


class Session:
    """一个语音片段"""

    def __init__(self, sid: int, sink: Sink):
        self.sid = sid
        self.dec = AdpcmDecoder()
        self.sink = sink
        self.bytes = 0
        self.t0 = time.time()

    def feed(self, data: bytes):
        self.bytes += len(data)
        self.sink.feed(self.dec.decode(data))

    def close(self, cancelled: bool):
        secs = self.bytes / 8000.0
        self.sink.flush(secs)
        tag = "取消" if cancelled else "完成"
        print(f"  [{self.sid:02X}] {tag}：{secs:.2f} 秒（{self.bytes} 字节）")


class Bridge:
    def __init__(self, args):
        self.args = args
        self.sessions: dict[int, Session] = {}
        self.ble_client = None
        self.lock = threading.Lock()
        self.sink = Sink(args.play, args.wav)

    def handle(self, data: bytes):
        if not data:
            return
        t = data[0]

        if t == T_START and len(data) >= 7:
            sid = data[1]
            with self.lock:
                self.sessions[sid] = Session(sid, self.sink)
            print(f"\n▶ [{sid:02X}] 开始接收（{data[3]} kHz, 帧 {data[4]<<8|data[5]}B / {data[6]}ms）")

        elif t == T_AUDIO and len(data) > 4:
            sid = data[1]
            s = self.sessions.get(sid)
            if s:
                s.feed(data[4:])

        elif t == T_END and len(data) >= 3:
            sid = data[1]
            cancelled = data[2] == 1
            with self.lock:
                s = self.sessions.pop(sid, None)
            if s:
                s.close(cancelled)

        elif t == T_CTRL and len(data) >= 2:
            cmd = data[1]
            if cmd == CTRL_CONFIRM:
                print("✓ 收到「确认」手势")
                press_enter()
            elif cmd == CTRL_CANCEL:
                print("✗ 收到「取消」手势")
                with self.lock:
                    for s in list(self.sessions.values()):
                        s.close(True)
                    self.sessions.clear()

    # ---------------------------------------------------------- UDP

    def run_udp(self):
        port = self.args.port
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.bind(("0.0.0.0", port))
        print(f"UDP 监听 0.0.0.0:{port}  —— 手机端选 Wi-Fi，IP 留空即可（局域网广播）")
        print("等待手机按键…  Ctrl+C 退出\n")
        try:
            while True:
                data, addr = sock.recvfrom(2048)
                self.handle(data)
        except KeyboardInterrupt:
            print("\n退出")
        finally:
            self.sink.shutdown()

    # ---------------------------------------------------------- BLE

    def run_ble(self):
        import asyncio
        from bleak import BleakClient, BleakScanner

        async def main():
            print("扫描 BLE 设备…（手机端选「蓝牙 GATT」并保持服务运行）")
            found = None
            for _ in range(6):
                devices = await BleakScanner.discover(timeout=6.0, return_adv=True)
                for dev, adv in devices.values():
                    if SERVICE_UUID in [u.lower() for u in (adv.service_uuids or [])]:
                        found = dev
                        break
                if found:
                    break
            if not found:
                print("没找到。检查：手机是否选了蓝牙模式、蓝牙是否开启、是否已connected过。")
                return

            print(f"连接 {found.address} …")
            async with BleakClient(found.address, timeout=25.0) as client:
                self.ble_client = client
                print("已连接")

                def on_audio(_c, data: bytearray):
                    self.handle(bytes(data))

                await client.start_notify(CHAR_AUDIO, on_audio)
                print("已订阅音频，等待手机按键…\n")
                try:
                    while client.is_connected:
                        await asyncio.sleep(0.5)
                except KeyboardInterrupt:
                    pass

        asyncio.run(main())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ble", action="store_true", help="用蓝牙 GATT 而不是 UDP")
    ap.add_argument("--port", type=int, default=47823)
    ap.add_argument("--play", default=None, help="输出设备名片段，如 CABLE Input")
    ap.add_argument("--wav", default=None, help="落盘 WAV 路径")
    args = ap.parse_args()

    b = Bridge(args)
    if args.ble:
        b.run_ble()
    else:
        b.run_udp()


if __name__ == "__main__":
    main()
