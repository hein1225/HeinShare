#!/usr/bin/env python3
"""极简 WebSocket 客户端（仅标准库），够用来跑 CDP。

支持：ws:// 客户端握手、文本帧收发、掩码编码。
不做 permessage-deflate 协商，CDP 服务端一般也不主动压缩。
"""
import base64
import os
import socket
import struct
from urllib.parse import urlparse


class WS:
    def __init__(self, url, timeout=30):
        u = urlparse(url)
        host = u.hostname
        port = u.port or 80
        path = u.path + (("?" + u.query) if u.query else "")
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self.sock.settimeout(timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET {path} HTTP/1.1\r\n"
            f"Host: {host}:{port}\r\n"
            "Upgrade: websocket\r\n"
            "Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            "Sec-WebSocket-Version: 13\r\n\r\n"
        )
        self.sock.sendall(req.encode())
        # 读握手响应
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise RuntimeError("ws 握手失败：连接被关闭")
            buf += chunk
        head, _, rest = buf.partition(b"\r\n\r\n")
        if b"101" not in head.split(b"\r\n")[0]:
            raise RuntimeError("ws 握手失败：" + head.decode(errors="replace")[:120])
        self.buf = rest

    # -------------------------------------------------- 底层收发
    def _read(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise RuntimeError("ws 连接被关闭")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send(self, text):
        payload = text.encode()
        head = bytearray([0x81])  # FIN + text
        mask = os.urandom(4)
        n = len(payload)
        if n < 126:
            head.append(0x80 | n)
        elif n < 65536:
            head.append(0x80 | 126)
            head += struct.pack(">H", n)
        else:
            head.append(0x80 | 127)
            head += struct.pack(">Q", n)
        head += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(head) + masked)

    def recv(self):
        while True:
            b1, b2 = self._read(2)
            opcode = b1 & 0x0F
            masked = b2 & 0x80
            length = b2 & 0x7F
            if length == 126:
                length = struct.unpack(">H", self._read(2))[0]
            elif length == 127:
                length = struct.unpack(">Q", self._read(8))[0]
            mask = self._read(4) if masked else None
            data = self._read(length) if length else b""
            if mask:
                data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
            if opcode == 0x8:
                raise RuntimeError("ws 收到关闭帧")
            if opcode == 0x9:  # ping -> pong
                self.sock.sendall(bytes([0x8A, 0x80]) + os.urandom(4))
                continue
            if opcode in (0x1, 0x2):
                return data.decode("utf-8", "replace")

    def close(self):
        try:
            self.sock.close()
        except Exception:
            pass
