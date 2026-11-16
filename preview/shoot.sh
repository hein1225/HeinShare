#!/usr/bin/env bash
# 逐屏截图：shoot.sh <screen-id> <输出文件名>
set -e
ID="$1"; OUT="$2"
URL="http://127.0.0.1:8899/index.html?only=${ID}"
echo "${URL}"
