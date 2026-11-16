#!/usr/bin/env python3
"""由 index.html 拆出每屏单独的 HTML，供逐屏截图。

单屏文件把 <body class="only"> 和对应的 #id 显示规则写死在 <style> 里，
不依赖运行时 JS，截图时不会因为加载时序截错屏。
"""
import os
import re

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, ".."))
SRC = os.path.join(ROOT, "index.html")
OUT = os.path.join(ROOT, "screens")

SCREENS = [
    ("s-home", "01-首页"),
    ("s-home-list", "02-首页-扫到设备"),
    ("s-send", "03-正在发送"),
    ("s-recv", "04-接收确认"),
    ("s-recv-running", "05-接收中"),
    ("s-recv-done", "06-接收完成"),
    ("s-records", "07-传输记录"),
    ("s-settings", "08-设置"),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    html = open(SRC, encoding="utf-8").read()

    # 去掉运行时过滤脚本（拆页后不需要）
    html = re.sub(r"\n<script>.*?</script>\n", "\n", html, flags=re.S)

    for sid, name in SCREENS:
        css = (
            "<style>\n"
            "body.only { padding: 0; background: transparent; }\n"
            "body.only .page-head { display: none; }\n"
            "body.only .screen { display: none; }\n"
            "body.only .screen > figcaption { display: none; }\n"
            f"body.only #{sid} {{ display: flex; }}\n"
            "</style>\n"
        )
        page = html.replace("</head>", css + "</head>")
        page = page.replace('<body>', '<body class="only">')
        # 资源在上一级目录
        page = page.replace('"assets/', '"../assets/')
        page = page.replace('href="assets/', 'href="../assets/')
        path = os.path.join(OUT, f"{name}.html")
        with open(path, "w", encoding="utf-8") as f:
            f.write(page)
        print(f"[ok] {path}")


if __name__ == "__main__":
    main()
