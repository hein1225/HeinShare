#!/usr/bin/env bash
# 生成各屏截图：chromium headless 逐个 id 截取
set -e
cd "$(dirname "$0")"
OUT=../screenshots
mkdir -p "$OUT"
scale=2

shot() {
  local id="$1" name="$2" w="$3" h="$4"
  # 用 JS 隐藏其它屏，只显示目标屏
  cat > /tmp/shot_one.html <<EOF
<!DOCTYPE html><html><head><meta charset="utf-8"><style>
html,body{margin:0;padding:0;background:#F4F8FD}
$(cat style.css | sed 's/^/  /' )
</style></head><body>
$(python3 - "$id" <<'PY'
import sys, re, pathlib
hid = sys.argv[1]
h = pathlib.Path("index.html").read_text()
# 抽出 body 内容
body = h.split("<body>",1)[1].rsplit("</body>",1)[0]
# 给所有 .phone 加样式：只显示目标
body = body.replace('<div class="phone"', '<div class="phone __screen"')
body = body.replace('<div class="phone land"', '<div class="phone land __screen"')
body = body.replace('</body>', '')
PY
)
</body></html>
EOF
  chromium --headless --disable-gpu --no-sandbox --hide-scrollbars \
    --force-device-scale-factor=$scale \
    --screenshot="$OUT/$name.png" --window-size="$w,$h" \
    "file://$PWD/index.html#$id" 2>/dev/null
  echo "  -> $name.png"
}

echo "渲染截图..."
shot home      01-首页           390 844
shot home-land 02-首页-横屏两栏   844 390
shot records   03-传输记录        390 844
shot settings  04-设置           390 844
shot sending   05-正在发送        390 844
shot receiving 06-接收文件        390 844
ls -la "$OUT"
