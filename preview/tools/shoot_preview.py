#!/usr/bin/env python3
"""用 CDP 逐屏截图海因分享的界面预览。

前置条件：
  1. 容器浏览器开着 CDP 端口 127.0.0.1:9222
  2. preview/ 目录上有静态服务：python3 -m http.server 8899

用法：python3 tools/shoot_preview.py
"""
import base64
import json
import os
import sys
import time
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ws_cdp import WS  # noqa: E402

CDP = "http://127.0.0.1:9222"
BASE = "http://127.0.0.1:8899/screens/"
SHOTS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "shots")

SCREENS = [
    ("01-首页", "01-首页.png"),
    ("02-首页-扫到设备", "02-首页-扫到设备.png"),
    ("03-正在发送", "03-正在发送.png"),
    ("04-接收确认", "04-接收确认.png"),
    ("05-接收中", "05-接收中.png"),
    ("06-接收完成", "06-接收完成.png"),
    ("07-传输记录", "07-传输记录.png"),
    ("08-设置", "08-设置.png"),
]

VIEW = {"width": 412, "height": 892, "deviceScaleFactor": 2, "mobile": False}


def new_tab(url):
    full = f"{CDP}/json/new?{urllib.parse.quote(url, safe=chr(38) + chr(35) + chr(61))}"
    req = urllib.request.Request(full, method="PUT")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())


def close_tab(tab_id):
    try:
        urllib.request.urlopen(f"{CDP}/json/close/{tab_id}", timeout=10).read()
    except Exception:
        pass


class Session:
    def __init__(self, ws_url):
        self.ws = WS(ws_url)
        self.n = 0

    def cmd(self, method, params=None):
        self.n += 1
        self.ws.send(json.dumps({"id": self.n, "method": method, "params": params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self.n:
                if "error" in msg:
                    raise RuntimeError(f"{method}: {msg['error']}")
                return msg.get("result", {})

    def close(self):
        self.ws.close()


def shoot(session, sid, name):
    session.cmd("Page.enable")
    session.cmd("Emulation.setDeviceMetricsOverride", VIEW)
    session.cmd("Page.navigate", {"url": BASE + urllib.parse.quote(sid) + ".html"})

    # 等导航真正完成、且目标屏可见，再量坐标
    rect = None
    for _ in range(40):
        time.sleep(0.25)
        box = session.cmd("Runtime.evaluate", {
            "expression": """(() => {
                const els = [...document.querySelectorAll('.screen')];
                const el = els.find(e => getComputedStyle(e).display !== 'none');
                if (!el) return 'null';
                const r = el.getBoundingClientRect();
                if (!r.width || !r.height) return 'null';
                return JSON.stringify({x:r.x, y:r.y, width:r.width, height:r.height});
            })()""",
            "returnByValue": True,
        })
        raw = box.get("result", {}).get("value")
        if raw and raw != "null":
            rect = json.loads(raw)
            break
    if rect is None:
        raise RuntimeError("等不到目标屏（.screen 一直是隐藏或空的）")

    data = session.cmd("Page.captureScreenshot", {
        "format": "png",
        "captureBeyondViewport": True,
        "clip": {**rect, "scale": 2},
    })["data"]

    out = os.path.join(SHOTS, name)
    with open(out, "wb") as f:
        f.write(base64.b64decode(data))
    return out


def shoot_overview(session):
    """总览页（index.html 整页并排七屏）。"""
    session.cmd("Page.enable")
    session.cmd("Emulation.setDeviceMetricsOverride", {
        "width": 1600, "height": 1000, "deviceScaleFactor": 1, "mobile": False,
    })
    session.cmd("Page.navigate", {"url": BASE.replace("/screens/", "/") + "index.html"})
    time.sleep(2.0)
    data = session.cmd("Page.captureScreenshot", {
        "format": "png", "captureBeyondViewport": True,
    })["data"]
    out = os.path.join(SHOTS, "00-总览.png")
    with open(out, "wb") as f:
        f.write(base64.b64decode(data))
    return out


def main():
    os.makedirs(SHOTS, exist_ok=True)
    # 总览页
    tab = new_tab(BASE.replace("/screens/", "/") + "index.html")
    tid = tab["id"]
    try:
        s = Session(tab["webSocketDebuggerUrl"])
        try:
            out = shoot_overview(s)
            print(f"[ok]   {os.path.basename(out)}  {os.path.getsize(out)} bytes")
        finally:
            s.close()
    except Exception as e:  # noqa: BLE001
        print(f"[fail] overview: {e}", file=sys.stderr)
    finally:
        close_tab(tid)
        time.sleep(0.3)

    for sid, name in SCREENS:
        tab = new_tab(BASE + sid)
        tid = tab["id"]
        try:
            s = Session(tab["webSocketDebuggerUrl"])
            try:
                out = shoot(s, sid, name)
                size = os.path.getsize(out)
                print(f"[ok]   {name}  {size} bytes")
            finally:
                s.close()
        except Exception as e:  # noqa: BLE001
            print(f"[fail] {sid}: {e}", file=sys.stderr)
        finally:
            close_tab(tid)
            time.sleep(0.3)


if __name__ == "__main__":
    main()
