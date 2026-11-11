# 海因快传 · Android 端

在 Android 手机上，直接和**鸿蒙手机**、**iPad / iPhone**、**Windows 电脑**互传文件。

不需要装助手客户端、不需要登录、不需要云盘中转，走的是局域网直连。

- 双向：安卓发给设备、设备发给安卓
- 单文件、多文件、整个文件夹（保留目录结构）一次发
- 界面按设计稿实现：首页 / 选文件 / 正在发送 / 记录 / 设置
- 协议层与 Windows 版 HiShare 完全一致

> **本项目基于原作者 HiShare 二次修改开发**：以原 Windows 版（Rust 实现）的协议为蓝本，
> 移植并重写为 Android 端（Kotlin），新增移动端界面、媒体选择、前台服务等能力。
>
> 这是一个**第三方互操作性实现**，与华为、苹果公司没有任何隶属或合作关系。
> 名称仅用于说明它能和谁互通。

---

## 仓库结构

```
android/        安卓工程（Kotlin）
  core/         协议层：发现 / 握手加密 / 数据通道
  app/          界面层：五屏 + 底部导航
port-spec/      逐字节协议规格（发现 / 握手加密 / 数据通道）
plan/           设计稿、HTML 原型、Windows 版源码参考
```

Windows 版（Rust 实现）的源码放在 `plan/HiShare-windows/`，作为协议实现的参照，
本仓库的 `android/core` 与它逐字段对齐。
---

## 快速开始

```bash
source /opt/team/tool-deps/scripts/env.sh   # 工具链统一在团队公共目录 /opt/team/tool-deps
cd android && bash setup-env.sh              # 首次：补齐 JDK 17 + Android SDK 34（国内镜像）
./gradlew :app:assembleDebug                 # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease               # 产物 app/build/outputs/apk/release/app-release.apk（自动用团队密钥签名）
./gradlew :core:testDebugUnitTest            # 密码学 / 协议单元测试
```

> release 签名密钥在团队目录 `/opt/team/keys/hishare/`，不随仓库分发；
> `app/build.gradle.kts` 找不到密钥时会退化成未签名包，不会中断构建。

安装后打开 App，首页「开启快传」打开即可对外广播。设备名、接收目录在设置页改。

---

## 协议要点

安卓端对外宣称的身份是 **`type=12`（电脑）**，不是手机 —— 华为手机把本机当成「可接收分享的
电脑」时，才会把本机列进可发送目标；宣称成手机反而可能被对端当同类设备过滤掉。

| 项 | 值 |
|---|---|
| 发现端口 | UDP 5684（组播 `224.0.0.1` + 广播 `255.255.255.255`） |
| 传输端口 | TCP 5684 |
| `capabilityBitmap` | `[455]`（含接收位 256） |
| 帧头 magic | `0xBABEFACE`（小端），24 字节头 |
| 加密 | RSA-2048 OAEP / AES-256-GCM / AES-128-GCM（AAD = `DFILE_AAD`）/ ChaCha20-Poly1305 |
| 身份派生 | `device_hash` = hex_upper(HMAC-SHA256(pubDer[0..16], pubDer)[0..8]) |

收到华为发来的查询包**必须立即应答**（同一对端 2 秒一次），解析失败也要应答；
CON 包回 4 字节 ACK。组播锁由前台服务持有，界面上的「可被发现」就是这个能力的对外反馈。

逐字节细节见 [`port-spec/`](port-spec/)。

---

## 文档导航

| 你想做什么 | 看哪份 |
|---|---|
| 装上就用 | 本文件 + [`android/使用说明.md`](android/使用说明.md) |
| 自己构建 | [`android/构建说明.md`](android/构建说明.md) |
| 改协议 | [`port-spec/`](port-spec/) + `plan/HiShare-windows/src/src` |

---

## 已知限制

- **不支持断点续传**。中断了要重发（临时文件以 `.part` 结尾）。
- 接收目录可选：应用私有目录 / 系统「下载」目录 / 用户用系统目录选择器指定的任意目录（设置页切换）。
- 只支持 Android 7.0（API 24）及以上。
- 公司网络、访客 Wi-Fi 常开「AP 隔离」，设备之间互相看不见，这种网络下用不了。

「华为分享」「鸿蒙 / HarmonyOS」「华为」「iPad / iPhone」等名称是其各自所有者的商标，
本项目仅用来说明兼容对象，与权利人无隶属、合作或背书关系。
