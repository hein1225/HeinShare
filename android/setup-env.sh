#!/usr/bin/env bash
# HiShare 安卓端构建环境准备
#
# 约定：所有依赖与工具都放在团队公共目录 /opt/team/tool-deps 下（持久盘，容器重建不丢），
# 本脚本只在缺失时补装，绝不在 /opt 或 /tmp 里另开一份。
#
#   /opt/team/tool-deps/jdk/jdk-17.0.20.1+1   JDK 17
#   /opt/team/tool-deps/android-sdk            Android SDK（platforms/build-tools/cmdline-tools）
#   /opt/team/tool-deps/cache/gradle-home      GRADLE_USER_HOME（Gradle 发行版与依赖缓存）
#
# 用法：bash setup-env.sh
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

TEAM_TOOLS=${TEAM_TOOLS:-/opt/team/tool-deps}
export TEAM_TOOLS
export JAVA_HOME="$TEAM_TOOLS/jdk/jdk-17.0.20.1+1"
export ANDROID_HOME="$TEAM_TOOLS/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$TEAM_TOOLS/cache/gradle-home"

echo "=== 1/4 JDK 17（$JAVA_HOME） ==="
if [ ! -x "$JAVA_HOME/bin/javac" ]; then
  echo "团队目录里没有 JDK 17，下载到 $JAVA_HOME"
  mkdir -p "$JAVA_HOME"
  cd /tmp
  curl -L --retry 3 -o jdk17.tar.gz \
    https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_linux-x64_bin.tar.gz
  tar -xzf jdk17.tar.gz -C "$JAVA_HOME" --strip-components=1
fi
export PATH="$JAVA_HOME/bin:$PATH"
javac -version || { echo "JDK 安装失败"; exit 1; }

echo "=== 2/4 cmdline-tools（$ANDROID_HOME） ==="
mkdir -p "$ANDROID_HOME/cmdline-tools"
if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  cd /tmp
  curl -L --retry 3 -o cmdline.zip https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip
  rm -rf /tmp/cmdline && mkdir -p /tmp/cmdline
  unzip -q cmdline.zip -d /tmp/cmdline
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv /tmp/cmdline/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
fi
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

echo "=== 3/4 SDK 组件（platform-tools / platforms;android-34 / build-tools;34.0.0） ==="
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0" || \
  sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

echo "=== 4/4 签名密钥 ==="
KEYS_DIR=${HISHARE_KEYS_DIR:-/opt/team/keys/hishare}
if [ -f "$KEYS_DIR/keystore.properties" ]; then
  echo "已存在：$KEYS_DIR/keystore.properties（release 包将自动签名）"
else
  echo "未找到 $KEYS_DIR/keystore.properties，release 将产出未签名包"
  echo "生成方式见 $KEYS_DIR/README.md"
fi

echo "=== 校验 ==="
ls "$ANDROID_HOME"
echo "SETUP OK"
echo "提示：source $TEAM_TOOLS/scripts/env.sh 可载入团队通用环境变量"
