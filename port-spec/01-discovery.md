# 发现层移植规格（HiShare → Android/Kotlin）

> 本文件只覆盖「局域网发现层」：基于 UDP `5684` 的 CoAP 风格 beacon / query / ACK 报文、三类报文的触发时机、逐字节布局、option 编码规则、设备能力位图（`capabilityBitmap`）与设备 JSON 负载的解析、设备去重/更新策略，以及 Android 侧的移植注意事项与最小实现建议。
> 握手与加密层（TCP 控制通道、RSA 协商、AES/ChaCha）不在本文件范围，见 `02-crypto-handshake.md`；数据通道（DFile 分包、预览、字节通道）另行成稿。
> 所有引用均为 `文件:行号`，基于 Rust 实现 `src/src/`。行号来自阅读当时的源码，移植时若上游改动请重新核对。

---

## 0. 术语与角色

| 角色 | 本文称呼 | Rust 入口 |
|------|----------|-----------|
| 本机（Windows PC / 将移植为 Android 端） | **本端 / Local** | `discovery.rs` |
| 对端（华为手机 / 鸿蒙设备） | **对端 / Peer** | 入站报文经 `parse_discovery_beacon` 解析 |

发现层只做「彼此知道对方在线 + 拿到对方的 IP:PORT 与能力位」，**不**做任何加密或认证；真正的认证在 TCP 握手层（见 `02-crypto-handshake.md`）。

---

## 1. 传输层与寻址

### 1.1 端口

- 发现通道与 TCP 控制/数据通道**共用同一个端口号** `5684`：`pub const PORT: u16 = 5684;`（`discovery.rs:13`）。上层建链时 `Channel::connect(peer_ip, 5684, ...)` 也用这个值（见 `02-crypto-handshake.md` 3.0 节）。

### 1.2 组播地址与广播地址

- 组播组：`pub const MULTICAST: Ipv4Addr = Ipv4Addr::new(224, 0, 0, 1);`（`discovery.rs:15`）。这是 `224.0.0.1`（本地网络控制块组播，TTL 受限，只在本地子网）。
- 广播地址：`255.255.255.255`，在 `transmit` 里以字符串 `"broadcast"` 选择（`discovery.rs:351`：`"broadcast" => SocketAddr::new(IpAddr::V4(Ipv4Addr::BROADCAST), PORT)`）。`Ipv4Addr::BROADCAST` 即 `255.255.255.255`（`std::net`）。
- **没有**除组播/广播之外的第三种寻址方式：`transmit` 只识别 `"broadcast"`、`"multicast"`、以及「其它直接当作单播 IP 解析」三种（`discovery.rs:350-354`）。

### 1.3 socket 选项与绑定

`DiscoverySocket::bind`（`discovery.rs:336-347`）建立 socket 时依次：

1. `UdpSocket::bind((local_ip, PORT))` —— **绑定到本机某个具体 IPv4 地址的 5684**，不是 `0.0.0.0`（`discovery.rs:337`）。这意味着一台机器只能跑一个发现实例，第二个会绑定失败（`app.rs:132-138` 的错误提示也说明了这点）。
2. `set_broadcast(true)` —— 允许发送广播（`discovery.rs:340`）。
3. `set_read_timeout(Some(400ms))` —— 每次 `recv` 最多阻塞 400ms，便于主循环轮询（`discovery.rs:343`）。
4. `join_multicast_v4(&MULTICAST, &local_ip)` —— 加入组播组，绑定到本机网卡（`discovery.rs:345`，返回值用 `_ =` 忽略，即加入失败也不致命）。

`recv` 用 8192 字节缓冲读取（`discovery.rs:363`），`recv_from` 返回 `(n, addr)`，截断到实际长度（`discovery.rs:364-368`）。

> **Android 侧注意事项**：Android 上组播接收默认被系统丢弃，必须持有 `WifiManager.MulticastLock`（对应权限 `CHANGE_WIFI_MULTICAST_STATE`，`AndroidManifest` 中声明）才能收到 `224.0.0.1` 的组播包；广播 `255.255.255.255` 在部分厂商 ROM（尤其 Android 9+ 的后台/省电策略）上被直接丢弃，需要前台服务保持活忊（见第 7 节）。

### 1.4 报文发送节奏（send 端）

两个发送场景：

- **一次性扫描** `scan()`（`app.rs:130-186`）：在 `seconds` 秒内，每 `900ms` 向 `broadcast` 和 `multicast` 各发一次 beacon 与 query（`app.rs:155-160`）。
- **常驻广播** `spawn_advertiser()`（`app.rs:702-783`）：每 `900ms` 发一次 beacon（广播+组播，`app.rs:765-768`）；query 的发送间隔不同——没见过任何对端时每 `1500ms` 发一次，见过之后降到每 `10s` 一次（`app.rs:769-777`）；此外 `request_scan()` 被调用（`take_scan_request()` 返回 true）时立即强制发一次（`app.rs:764`、`tray.rs:457`）。

---

## 2. 三类报文

### 2.1 beacon（设备主动广播）

- **发送方**：本端（PC / Android 端）主动发出。
- **触发时机**：周期性（见 1.4），代表「我在线、我能干什么」。
- **两种子形态**（由 `discovery_beacon(..., discovering: bool)` 的 bool 控制，`discovery.rs:45`）：
  - `discovering == true`（主动发现态）：首字节 `0x50`，`mode:1`，`extendServiceData` 带 `summary:<sha1>`、`OH:1,icon:1,abl:31`（`discovery.rs:49,54-56,88-93`）。
  - `discovering == false`（应答态 / 仅宣告在线）：首字节 `0x42`，`mode:10`，`extendServiceData` 为 `osType:2,nbVer:9,rssi:-58,abl:8`，**且**额外带一个 `option 12 = "2"`（`discovery.rs:49,71-73,94-102`）。
- **接收方**：对端（华为手机）通常不直接回 beacon；手机回的是「query/应答风格」的报文（见 2.3）。

### 2.2 query（探测请求 / Harmony 风格）

- **函数**：`advertisement_query_harmony(local_ip, port, identity, name)`（`discovery.rs:120`）。
- **发送方**：本端。
- **触发时机**：与 beacon 同期发送（`app.rs:148,158,712,775`）。其 JSON 多出 `coapUri:"coap://<local_ip>/device_discover"` 字段（`discovery.rs:129-130`）。
- **首字节**：`0x42`（同非发现态 beacon），带 4 字节随机 token、option 11 `device_discover`、option 12 `"2"`（`discovery.rs:143-148`）。

### 2.3 ACK（应答）

- **触发时机与角色**：**本端在收到对端发来的 CON 类型（首字节 `0x4x`）报文时回 ACK**。源码注释明确：对端用 CON 发来的应答「必须回 4 字节 ACK，否则会按 0s / 1.2s / 3.5s 重传三次；补上 ACK 后一次 query 只收到一个应答」（`discovery.rs:224-225`）。
- **函数**：`discovery_ack(message_id: [u8;2]) -> [u8;4]`（`discovery.rs:226`），返回 `[0x60, 0x00, message_id[0], message_id[1]]`（`discovery.rs:227`）。
  - `0x60` = `0110_0000`：版本 1、类型 `2`（ACK）、token 长度 0（`discovery.rs:424` 测试 `(ack[0]>>4)&3 == 2`）。
  - `0x00` = code（空）。
  - 后两字节**原样回显**收到的 message id（`data[2]、data[3]`，见 `discovery.rs:317`）。
- **本端决定要不要回 ACK**：在 `parse_discovery_beacon` 里，`confirmable = message_type == 0`（CON），仅当 `confirmable` 为真时才调用 `discovery_ack(message_id)`（`app.rs:169-170,729-730`、`discovery.rs:316`）。

### 2.4 报文识别

入站数据先用 `is_discovery_packet` 粗筛：`data.len() >= 32 && data.windows(15).any(|w| w == b"device_discover")`（`discovery.rs:41-43`）。注意 `b"device_discover"` 是 **15 字节**（`d-e-v-i-c-e-_-d-i-s-c-o-v-e-r` 共 15 字符），所以窗口取 `windows(15)`；长度不足 32 或不含该串的（如 keepalive `0x60 0x00 ...`）直接丢弃（`discovery.rs:382` 测试验证）。

---

## 3. 报文逐字节布局

### 3.1 CoAP 风格头部（4 字节起，但不完全标准）

所有发现报文都以 4 字节「伪 CoAP 头」开头，结构如下（以 `discovery_beacon` 与 `advertisement_query_harmony` 的实际写入为准）：

| 偏移 | 长度 | 字段 | 编码 / 值 | 说明 | 源 |
|------|------|------|-----------|------|----|
| 0 | 1 | 首字节 | 见下 | 版本(2bit) + 类型(2bit) + token长度(4bit) | `discovery.rs:88,97,143` |
| 1 | 1 | Code | `0x02` | 固定 POST（`discovery.rs:89,98,144`、解析要求 `data[1]==2` `discovery.rs:240`） | |
| 2 | 2 或 4 | token / message id | 随机 | beacon(discovering) 写 2 字节 `u16` 大端；beacon(非discovering) 与 query 写 4 字节（OsRng） | `discovery.rs:90,96,121` |
| 4+ | 变长 | Options | CoAP option 编码 | 见 3.2 | `discovery.rs:91-92,99-101,145-147` |
| … | 1 | Payload Marker | `0xFF` | 分隔 option 与 JSON | `discovery.rs:93,102,148` |
| … | 变长 | JSON 负载 | UTF-8 文本 | 见第 4、5 节 | `discovery.rs:104,149` |

首字节取值（实测）：

| 报文 | 首字节 | 二进制 | 版本 | 类型 | token长度 | token实际字节 |
|------|--------|--------|------|------|-----------|---------------|
| beacon（discovering） | `0x50` | `0101_0000` | 1 | 1（NON） | 声明 0 | 写 2 字节 |
| beacon（非 discovering / answer） | `0x42` | `0100_0010` | 1 | 0（CON） | 2 | 写 4 字节 |
| query（harmony） | `0x42` | `0100_0010` | 1 | 0（CON） | 2 | 写 4 字节 |

> 注意一个**非标准点**：beacon（discovering）首字节 `0x50` 声明 token 长度为 0，但紧接着写了 2 字节 token。解析侧 `token_size = data[0] & 0x0F`（`discovery.rs:239`）得到 0，于是 `offset = 4 + 0 = 4`，恰好跳过这 2 字节（它们在偏移 2-3），option 解析从偏移 4 开始，自洽。**Android 侧必须照此实现**：解析时不要因为「声明 token 长度 0 但后面有 2 字节」而报错，直接 `offset = 4 + token_size` 即可。

### 3.2 option 编码规则

`push_option`（`discovery.rs:109-118`）的写入规则：

- header 字节 = `(delta << 4) | len`（delta 是「相对上一个 option 号的增量」，首 option 的 delta 即绝对号；len 是 value 字节数）。
- **当 `len < 13`**：header 低 4 位直接放 `len`，后面紧跟 value（长度 len）。（`discovery.rs:111-112`）
- **当 `len >= 13`**：header 低 4 位固定放 `13`，后面再跟 **1 个扩展字节** = `len - 13`，再跟 value（`discovery.rs:113-116`）。
- 本实现的编码器**只用到「<13」和「==13 + 1 扩展字节」两种形态**，不涉及 delta/len == 14（269+）的 2 字节扩展；但解析侧支持（见下）。

对照表（实际出现的 option）：

| option 号 | 出现在 | 值（字节） | header 字节计算 | 源 |
|-----------|--------|-----------|-----------------|----|
| 3（Uri-Host） | beacon(discovering) | 本机 IP，如 `192.168.1.5`（11 字节） | `(3<<4)\|11 = 0x3B` | `discovery.rs:91` |
| 11（Uri-Path） | 所有三类 | 字面串 `device_discover`（15 字节） | `(8<<4)\|13 = 0x8D` + 扩展字节 `2`（`15-13`）→ 解析侧 number 从 3 累加到 11 | `discovery.rs:92,100,146` |
| 12 | beacon(非discovering)、query | 单字节 `'2'`（`0x32`） | `(1<<4)\|1 = 0x11`，值 `0x32` | `discovery.rs:101,147` |

> 这里有个**关键累计语义**：`push_option` 的 `delta` 参数在解析侧被当「增量」累加。beacon 先 `push_option(3, ...)`（号 3），再 `push_option(8, "device_discover")`（号 3+8=11）；query 先 `0xBD`（`11` 高半字节 → 号 11），再 `0x11`（增量 1 → 号 12）。因此两类报文的 `device_discover` 都落在 **option 11**，与 `parse_discovery_beacon` 的 `options.get(&11) == b"device_discover"` 校验一致（`discovery.rs:248-251`）。**解析时务必用「号累加」而非「固定号」去读**，否则会错位。

`parse_options`（`discovery.rs:182-222`）的解析规则（与写入对称，但更宽松）：

- 循环条件：`offset < data.len() && data[offset] != 0xFF`（`discovery.rs:210`），即遇到 `0xFF` 结束。
- 每轮读 1 个 header 字节：高 4 位是 delta（累加到 `number`），低 4 位是 length（`discovery.rs:211-214`）。
- `read_extended`（`discovery.rs:187-208`）对高/低 nibble 统一解释：
  - `n < 13` → 值就是 `n`；
  - `n == 13` → 再读 1 字节 `v`，值 = `v + 13`（`discovery.rs:190-197`）；
  - `n == 14` → 读 2 字节大端 `v`，值 = `v + 269`（`discovery.rs:198-205`，**解析支持但写入不产出**）；
  - 其它（`>14`）→ `Error::Format`（`discovery.rs:206`）。
- 越界或 option 数 ≥ 16 时返回 `Error::Truncated`（`discovery.rs:215-217`）。

### 3.3 ACK 帧（4 字节）

`[0x60, 0x00, mid0, mid1]`，见 2.3 与 `discovery.rs:226-228`。

---

## 4. 设备 JSON 负载字段

JSON 紧跟 `0xFF` 之后（`discovery.rs:104,149`），**结尾可能多带 1 字节 `0x00`**，解析时会被剥掉（`discovery.rs:255-258`：`if payload.last() == Some(&0) { payload = &payload[..len-1]; }`）。整段用 `serde_json` 解析（`discovery.rs:260`）。

### 4.1 字段总表（以 discovering beacon 为准，`discovery.rs:51-67`）

| JSON 字段 | 类型 | 示例值 | 来源 / 生成 | 说明 |
|-----------|------|--------|-------------|------|
| `deviceId` | string（其值是 JSON） | `{"UDID":"AABB"}` | `format!(r#"{{"UDID":"{identity}"}}"#)`（`discovery.rs:48`） | 本身是个字符串化的 JSON 对象，解析时要二次 `serde_json::from_str` 取 `UDID`（`discovery.rs:298-303`） |
| `devicename` | string | `"我的电脑"` | `json_string(clip_chars(name, 60))`（`discovery.rs:59,164`） | 先按**字符**截断到 60（`clip_chars`，`lib.rs:307`），再做 JSON 转义 |
| `type` | number | `12` | `DEVICE_TYPE_PC`（`discovery.rs:60,162`） | 设备类型号，见 5.1 |
| `hicomversion` | string | `""` | 字面空串（`discovery.rs:54`） | |
| `mode` | number | `1`（discovering）/ `10`（非 discovering） | `discovery.rs:49` | 发现态标识 |
| `deviceHash` | string | `""` | 字面空串（`discovery.rs:55`） | 与握手层的 device_hash 不是一回事 |
| `serviceData` | string | `"port:41234,"` | `discovery.rs:55,72` | **端口号从这里面取**（见 4.3） |
| `extendServiceData` | string | discovering: `summary:<sha1hex>,nbVer:9,OH:1,icon:1,abl:31,rssi:-58`；非 discovering/query: `osType:2,nbVer:9,rssi:-58,abl:8` | `discovery.rs:56,73,128` | `summary:` 后是 identity 的 SHA1 大写十六进制（`summary_hex`，`discovery.rs:153-158`） |
| `wlanIp` | string | `"192.168.1.5"` | 本机 IP（`discovery.rs:57,64`） | **必须 == 源 IP**，否则丢包（`discovery.rs:263-265`） |
| `capabilityBitmap` | array of number | `[455]` | `CAPABILITY_BITS`（`discovery.rs:65,160`） | 见第 5 节 |
| `bType` | number | `5` | 字面（`discovery.rs:56,73,129`） | 固定 5，校验失败丢包（`discovery.rs:266-268`） |
| `seqNo` | number | 随机 u32 | `(rand::random::<u16>()) as u32`（`discovery.rs:47,66`） | |
| `coapUri`（仅 query） | string | `"coap://192.168.1.5/device_discover"` | `discovery.rs:130` | 仅 `advertisement_query_harmony` 携带 |

### 4.2 设备名转义与截断（Android 必实现）

- `clip_chars(text, 60)`：按**字符**（Unicode scalar value）截断，不是按字节，且**不补省略号**（`lib.rs:307-312`）。例：`clip_chars("华为分享", 2) == "华为"`，`clip_chars("a我🙂b", 3) == "a我🙂"`（`lib.rs:557-563` 测试）。Android 用 `String.take(n)` 之类的按码点截取，绝不能 `substring` 按 UTF-16 或 `slice` 按字节。
- `json_string`（`discovery.rs:164-180`）：输出带首尾双引号的 JSON 字符串，转义 `"` → `\"`、`\` → `\\`、`\n \r \t` 以及所有 `< 0x20` 的控制字符 → `\u00xx`。Android 端构造 JSON 时**必须**复刻这套转义，否则对端 `serde_json` 解析失败。

### 4.3 端口解析

端口不在独立字段，而在 `serviceData` 字符串里：按 `,` 拆分，找以 `port:` 开头的一段，取其后缀 `parse::<u16>()`（`discovery.rs:290-295`）。例：`"port:41234,"` → `41234`。解析失败直接 `Error::Format`（即该包作废）。

### 4.4 设备标识（id / UDID）

`Peer.id` 的取法（`discovery.rs:298-303`）：
1. 读 `deviceId` 字符串 → 再 `serde_json::from_str` 成对象 → 取 `UDID` 字段作为 id；
2. 取不到时回退为 `"{host}:{port}"`。
注意：同一个物理设备的不同 IP 会生成不同 id（回退情形），但正常 phone 都会带 UDID，所以以 UDID 为主键。

### 4.5 其它解析校验（解析失败即丢包）

- 源 IP 必须和本机同网段（`same_subnet`，`discovery.rs:231-234,322-328`），否则 `Error::Protocol("源地址不在本网段")`；`prefix` 由调用方传 `24`（`app.rs:167,727`）。
- 首字节版本位（`data[0]>>6`）必须为 1，token 长度 ≤ 8（`discovery.rs:235`）。
- 类型 `message_type = (data[0]>>4)&3` 必须 ≤ 1，`data[1]==2`，`4+token_size <= len`（`discovery.rs:238-242`）。
- 必须有 option 11 == `device_discover`（`discovery.rs:248-251`）。
- `wlanIp` 必须等于源 `host`（`discovery.rs:263-265`）。
- `bType` 必须等于 5（`discovery.rs:266-268`）。
- `capabilityBitmap` 必须是长度 1~4 的数组（`discovery.rs:274-276`），取 `caps[0]` 为能力值。

---

## 5. 能力位图与设备类型

### 5.1 常量

| 常量 | 值 | 含义 / 来源 | 源 |
|------|----|------------|----|
| `SHARE_CAPABILITY_BIT` | `256`（`0x100`，即 2 的 8 次方） | 「现在会接收分享」对应的位，**位号 8（0-based，即第 9 位）** | `discovery.rs:26` |
| `CAPABILITY_BITS` | `455` | 本端广播时写进 `capabilityBitmap` 的固定值 | `discovery.rs:160` |
| `DEVICE_TYPE_PC` | `12` | 本端 `type` 字段固定值 | `discovery.rs:162` |

`455` 的二进制 = `0b1_1100_0111` = 256+128+64+4+2+1，即设置了 **bit0, bit1, bit2, bit6, bit7, bit8**。其中 **bit8（=256）= SHARE_CAPABILITY_BIT，表示「我当前可以接收分享」**（`discovery.rs:288`：`accepts = first_cap & SHARE_CAPABILITY_BIT != 0`）。也就是说 PC 端默认广播自己「可接收」。

### 5.2 设备类型 `type` → 展示类型 `kind` 映射

`parse_discovery_beacon` 把 JSON 的 `type` 映射成 `Peer.kind`（`discovery.rs:279-284`）：

| `type` 值 | `kind` 字符串 |
|-----------|---------------|
| 12 | `"computer"` |
| 14 | `"phone"` |
| 17 | `"tablet"` |
| 其它 | `"unknown"` |

本端永远发 `12`（`DEVICE_TYPE_PC`）。

### 5.3 `accepts` 语义（重要）

- `accepts` 只看 `capabilityBitmap[0] & 256 != 0`（`discovery.rs:288`）。
- **没有 256 位不代表非法包**：手机在「关闭分享」或「仅分享过但没加你」时照样回设备信息，只是明说「我不接收」。注释明确要求这种设备**留在列表里、标记为不可发送，而不是当垃圾包丢掉**（`discovery.rs:286-287`）。
- Android 侧 UI 必须区分「在线但不可发送（accepts=false）」与「完全没发现」两种状态。

### 5.4 `Peer` 数据结构

`discovery.rs:28-39` 定义：

| 字段 | 类型 | 含义 | 源 |
|------|------|------|----|
| `id` | String | UDID 或 `{host}:{port}` 回退 | `discovery.rs:30,298-303` |
| `host` | String | 对端 IP | `discovery.rs:31` |
| `port` | u16 | 从 `serviceData` 解析 | `discovery.rs:32,291-295` |
| `name` | String | `devicename`，缺省 `"Huawei"` | `discovery.rs:33,297` |
| `brand` | String | 硬编码 `"Huawei"` | `discovery.rs:34,310` |
| `kind` | String | computer/phone/tablet/unknown | `discovery.rs:35,279-284,311` |
| `last_seen` | Instant | 解析成功时刻 | `discovery.rs:36,312` |
| `accepts` | bool | 是否有 256 位 | `discovery.rs:38,288,313` |

---

## 6. 设备去重 / 更新策略

### 6.1 一次性扫描 `scan()` —— 按 id 去重，不更新

`app.rs:172-182`：

```
if peers.iter().any(|p| p.id == peer.id) { continue; }  // 已存在就跳过
...
peers.push(peer);
```

- 主键是 `Peer.id`（UDID）。同一设备即使换了 IP/端口，只要 UDID 相同就只保留**第一次**出现的那份，后续包被忽略（不更新端口）。
- 这是 CLI 扫描路径，用完即弃，不做 TTL 维护。

### 6.2 常驻广播 `spawn_advertiser()` —— 按 host 更新，TTL 淘汰

核心是 `peers::upsert(name, host, port, kind, accepts)`（`peers.rs:150-204`）：

- **主键是 `host`（IP）**（`peers.rs:163`），不是 id。
- 已存在同 host：逐项比较 `name/port/kind/accepts`，**只有真变了才 `bump()` 增加 revision**，并刷新 `seen = Instant::now()`（`peers.rs:167-190`）。注释解释：发现包每 1~2 秒来一次、绝大多数内容相同，若每次都 bump 会让 UI 的「revision 没变就不重建视图」短路失效（`peers.rs:164-166`）。
- 不存在：容量上限 32，超出 `remove(0)` 删最老的再 `push`（`peers.rs:192-194`）。
- 列表 TTL：`LIST_TTL = 300s`（5 分钟，`peers.rs:148`）。`snapshot(max_age)`（`peers.rs:206-217`）只返回 `seen` 在 `max_age` 内的设备；`targets()`（`peers.rs:223+`）会清掉离线超过 `LIST_TTL` 的目标。
- 另外还有两个本地限流集合：`seen_hosts`（上限 32，仅用于「首次出现才写日志」，`app.rs:733-743`）和 `last_answer`（上限 64，对同一个 host 的「answer beacon（非 discovering 形态）」应答**至少间隔 2 秒**，避免刷屏，`app.rs:745-760`）。

### 6.3 Android 侧对应

- 用一个 `PeerStore`（见第 8 节）保存 `host -> Peer`，键用 IP。
- 收到包：先 `parse_discovery_beacon`，成功则 `store.upsert(...)`，刷新 `lastSeen = now`。
- 后台定时（建议 5s 一次）扫描 `lastSeen` 超过 300s 的设备并移除，UI 列表随之刷新。
- 去重/更新逻辑**严格按 host 主键 + 「变化才通知 UI」**，否则高频发现包会拖垮 UI。

---

## 7. Android 平台注意事项

1. **组播锁（MulticastLock）**：Android 默认丢弃 `224.0.0.1` 组播包。必须在 `WifiManager` 上 `createMulticastLock(...).acquire()`，并在 `AndroidManifest` 声明 `android.permission.CHANGE_WIFI_MULTICAST_STATE`（`discovery.rs:15,345` 对应的组播接收能力）。不持锁时 `join_multicast_v4` 看似成功但收不到包。
2. **前台服务**：发现循环是常驻的（`spawn_advertiser` 是独立线程 `std::thread::spawn`，`app.rs:703`）。Android 上后台进程会被 Doze / 厂商杀进程，必须用 `foregroundServiceType="location"?` 不，用 `foregroundServiceType="connectedDevice"` 或至少前台通知保活，否则 900ms 周期广播与接收会中断。
3. **网络切换重试**：发现绑死在某个 `local_ip`（`discovery.rs:337`）。Wi-Fi 切换 / 重连后 IP 变化，旧 socket 失效，必须**重建 `DiscoverySocket`** 并重新 `join_multicast_v4`。建议在 `ConnectivityManager.NetworkCallback` 的 `onCapabilitiesChanged / onLost` 里重建。
4. **UDP 广播限制**：`255.255.255.255` 广播在 Android 8+ 的许多 ROM 上被直接丢弃（尤其后台 / 无前台服务时）。对策：优先依赖组播 `224.0.0.1`（持锁可收），发送时仍按 Rust 行为同时发 broadcast+multicast（`app.rs:156-159`）；若某些机型广播发不出去，至少组播能补位。
5. **绑定具体 IP 而非 0.0.0.0**：Rust 绑 `local_ip:PORT`（`discovery.rs:337`），Android 也要绑到当前 Wi-Fi 分配的 IPv4，不能绑 `0.0.0.0`，否则组播加入与源地址判断（`discovery.rs:231-234`）会乱。
6. **端口独占**：`5684` 同机只能一个实例（`app.rs:132-138`）。Android 上若主进程与 `:discovery` 进程都想绑，会冲突；建议只在主进程跑一个发现线程。
7. **read timeout**：Rust 设 400ms（`discovery.rs:343`），Android 用 `setSoTimeout(400)` 让 `recv` 周期返回，便于在循环里做「是否强制扫描 / 是否到 900ms 该重发」的判断，而不是永久阻塞。
8. **权限**：`CHANGE_WIFI_MULTICAST_STATE`、`ACCESS_WIFI_STATE`、`INTERNET`（UDP 收发）、`ACCESS_NETWORK_STATE`（网络切换监听）。Android 12+ 还需 `CHANGE_NETWORK_STATE` 监听。
9. **子网校验**：解析时 `same_subnet(src, local, 24)`（`discovery.rs:322-328`，前缀 24）会丢弃非同网段来源。Android 的 `local_prefix` 应从当前 Wi-Fi 的掩码推导（多数 `/24`），不要硬编码错。
10. **ACK 必须回**：收到 CON（`0x4x`）报文一定要回 4 字节 ACK（`discovery.rs:226-228`、`app.rs:169-170`），否则对端按 0/1.2s/3.5s 重传，造成风暴。

---

## 8. 最小可用实现建议（Kotlin 文件与 Rust 一一对应）

建议把发现层放在 `android/core/src/main/java/com/hishare/core/` 下，文件与 Rust 函数映射如下：

| Rust 侧 | Android / Kotlin 侧 | 文件 |
|---------|---------------------|------|
| `discovery.rs:13 PORT`、`discovery.rs:15 MULTICAST`、`discovery.rs:26 SHARE_CAPABILITY_BIT`、`discovery.rs:160 CAPABILITY_BITS`、`discovery.rs:162 DEVICE_TYPE_PC` | `const val PORT = 5684` 等常量 | `Discovery.kt` |
| `DiscoverySocket::bind` (`discovery.rs:336`) | `fun bind(localIp: String): DiscoverySocket` | `Discovery.kt` |
| `DiscoverySocket::transmit` (`discovery.rs:349`) | `fun transmit(target: String, data: ByteArray)` | `Discovery.kt` |
| `DiscoverySocket::recv` (`discovery.rs:362`) | `fun recv(): Pair<String, ByteArray>?` | `Discovery.kt` |
| `discovery_beacon` (`discovery.rs:45`) | `fun buildBeacon(localIp, port, identity, name, discovering): ByteArray` | `Discovery.kt` |
| `advertisement_query_harmony` (`discovery.rs:120`) | `fun buildQuery(localIp, port, identity, name): ByteArray` | `Discovery.kt` |
| `discovery_ack` (`discovery.rs:226`) | `fun buildAck(messageId: ByteArray): ByteArray` | `Discovery.kt` |
| `is_discovery_packet` (`discovery.rs:41`) | `fun isDiscoveryPacket(data: ByteArray): Boolean` | `Discovery.kt` |
| `push_option` (`discovery.rs:109`) / `parse_options` (`discovery.rs:182`) | `fun pushOption(out: ByteArrayOutputStream, delta: Int, value: ByteArray)` / `fun parseOptions(data: ByteArray): Map<Long, ByteArray>` | `Discovery.kt`（option 编解码工具） |
| `parse_discovery_beacon` (`discovery.rs:230`) | `fun parseBeacon(data: ByteArray, host: String, localNet: String, prefix: Int): ParsedBeacon?` | `Discovery.kt` |
| `same_subnet` (`discovery.rs:322`) | `fun sameSubnet(a: String, b: String, prefix: Int): Boolean` | `Discovery.kt` / `NetUtil.kt` |
| `json_string` (`discovery.rs:164`) / `clip_chars` (`lib.rs:307`) | `fun jsonString(v: String): String` / `fun clipChars(text: String, max: Int): String` | `JsonUtil.kt` |
| `summary_hex` (`discovery.rs:153`) | `fun summaryHex(identity: String): String`（SHA1 → 大写十六进制） | `JsonUtil.kt` |
| `Peer` 结构 (`discovery.rs:28`) | `data class Peer(...)` | `model/Peer.kt` |
| `peers::upsert` (`peers.rs:150`) / `LIST_TTL` (`peers.rs:148`) | `class PeerStore { fun upsert(...); fun snapshot(maxAgeMs); fun purgeStale() }` | `PeerStore.kt` |
| `request_scan` / `take_scan_request` (`discovery.rs:19,23`) | `object ScanSignal { fun request(); fun take(): Boolean }` | `ScanController.kt` |
| `spawn_advertiser` 主循环 (`app.rs:702`) | `class Advertiser(private val localIp: String) : Thread()`（含 900ms 周期、query 间隔、强制扫描） | `Advertiser.kt` |
| `scan` 一次性扫描 (`app.rs:130`) | `suspend fun scan(localIp: String, seconds: Long): List<Peer>` | `Scanner.kt` |

### 8.1 Kotlin 端最小骨架（伪代码）

```kotlin
// Discovery.kt
object Discovery {
    const val PORT = 5684
    const val MULTICAST = "224.0.0.1"
    const val SHARE_CAPABILITY_BIT = 256L   // = 2^8，第 9 位（0-based 位号 8）
    const val CAPABILITY_BITS = 455L
    const val DEVICE_TYPE_PC = 12

    // 绑定到具体本机 IP，setBroadcast(true)、setSoTimeout(400)、joinGroup(224.0.0.1)
    fun bind(localIp: String): DatagramSocket { ... }

    // 首字节 0x50(discovering)/0x42(非 discovering/query)，code 0x02
    fun buildBeacon(localIp: String, port: Int, identity: String, name: String, discovering: Boolean): ByteArray { ... }

    fun buildQuery(localIp: String, port: Int, identity: String, name: String): ByteArray { ... }

    fun buildAck(messageId: ByteArray): ByteArray = byteArrayOf(0x60, 0x00, messageId[0], messageId[1])

    fun isDiscoveryPacket(data: ByteArray): Boolean =
        data.size >= 32 && data.windowed(15).any { it.contentEquals("device_discover".toByteArray()) }

    // 解析：校验版本/类型、option 11 == "device_discover"、wlanIp==host、bType==5、
    // capabilityBitmap[0]、accepts = (caps[0] and SHARE_CAPABILITY_BIT) != 0L
    fun parseBeacon(data: ByteArray, host: String, localNet: String, prefix: Int): ParsedBeacon? { ... }
}
```

### 8.2 实现要点（逐条对应源码）

1. **option 编码**必须实现「高 nibble = delta（相对上一个号）、低 nibble = len 或 13+扩展字节」（`discovery.rs:109-118`）；解析用「号累加」（`discovery.rs:213`）。
2. **首字节 token 长度陷阱**：discovering beacon 声明 token 长度 0 却写 2 字节，解析按 `offset = 4 + (data[0] & 0x0F)` 跳过（`discovery.rs:239,244`），不要硬编码跳过 2 或 4 字节。
3. **JSON 转义与 60 字符截断**必须复刻（`discovery.rs:59,164`、`lib.rs:307`），否则对端解析失败。
4. **端口从 `serviceData` 的 `port:` 前缀取**（`discovery.rs:290-295`），不是独立字段。
5. **`deviceId` 是字符串化的 JSON**，需二次解析取 `UDID`（`discovery.rs:298-303`）。
6. **`accepts` 只看 bit8**；无此位也保留设备、仅标记不可发（`discovery.rs:286-288`）。
7. **收到 CON（`0x4x`）必回 ACK**（`discovery.rs:226-228`）。
8. **去重主键用 host（IP）**，变化才通知 UI，刷新 `lastSeen`；TTL 300s（`peers.rs:148,150`）。
9. **发送节奏**：beacon 每 900ms（广播+组播各一次），query 在「无对端时 1500ms、有对端时 10s」（`app.rs:765-777`）。

---

## 9. 常量速查表

| 常量 / 字段 | 值 | 源 |
|-------------|----|----|
| 发现 UDP / TCP 端口 PORT | 5684 | `discovery.rs:13` |
| 组播地址 MULTICAST | 224.0.0.1 | `discovery.rs:15` |
| 广播地址 | 255.255.255.255 | `discovery.rs:351`（`Ipv4Addr::BROADCAST`） |
| 接收缓冲 | 8192 B | `discovery.rs:363` |
| read timeout | 400 ms | `discovery.rs:343` |
| 广播/重发周期 | 900 ms | `app.rs:155,765` |
| query 周期（无对端 / 有对端） | 1500 ms / 10 s | `app.rs:769-772` |
| answer 应答最小间隔 | 2 s | `app.rs:745-748` |
| 强制扫描信号 | `request_scan` / `take_scan_request` | `discovery.rs:19,23`、`tray.rs:457` |
| 发现包最小长度（粗筛） | 32 B | `discovery.rs:42` |
| device_discover 串长 | 15 B | `discovery.rs:42`（`b"device_discover"` 窗口 15） |
| 伪 CoAP 首字节 | beacon:0x50 / answer·query:0x42 | `discovery.rs:89,97,143` |
| Code | 0x02 | `discovery.rs:89,98,144` |
| Payload Marker | 0xFF | `discovery.rs:93,102,148` |
| ACK 帧 | `[0x60,0x00,mid0,mid1]` | `discovery.rs:227` |
| SHARE_CAPABILITY_BIT | 256（bit8 / 第9位） | `discovery.rs:26` |
| CAPABILITY_BITS | 455 | `discovery.rs:160` |
| DEVICE_TYPE_PC | 12 | `discovery.rs:162` |
| type→kind | 12:computer / 14:phone / 17:tablet / 其它:unknown | `discovery.rs:279-284` |
| bType 校验值 | 5 | `discovery.rs:266` |
| capabilityBitmap 数组长度上限 | 4 | `discovery.rs:274` |
| 设备名截断 | 60 字符 | `discovery.rs:59`（`clip_chars`，`lib.rs:307`） |
| LIST_TTL（设备保留） | 300 s | `peers.rs:148` |
| PeerStore 容量上限 | 32 | `peers.rs:192` |
| 设备名缺省 | "Huawei" | `discovery.rs:297` |
| brand 硬编码 | "Huawei" | `discovery.rs:310` |
| 子网前缀（调用方传入） | 24 | `app.rs:167,727` |

---

## 10. 移植检查清单

1. 实现常量 `PORT=5684`、`MULTICAST=224.0.0.1`、`SHARE_CAPABILITY_BIT=256`、`CAPABILITY_BITS=455`、`DEVICE_TYPE_PC=12`。
2. 实现 `DatagramSocket` 绑定到**具体本机 IP**，开启广播、`setSoTimeout(400)`、加入组播组。
3. 实现 option 编解码（高 nibble=delta 累加、低 nibble=len 或 13+扩展字节，见 `discovery.rs:109-118,182-222`）。
4. 实现 `buildBeacon`：`0x50`/`0x42` + code `0x02` + token + option3(IP) + option11(`device_discover`) + `0xFF` + JSON；注意 discovering 与非 discovering 两种 JSON 差异（`discovery.rs:51-104`）。
5. 实现 `buildQuery`：与 answer beacon 几乎一致，JSON 多 `coapUri`（`discovery.rs:120-151`）。
6. 实现 `buildAck`：`[0x60,0x00,mid0,mid1]`（`discovery.rs:226`）。
7. 实现 `parseBeacon`：版本/类型/option11/`wlanIp`==host/`bType`==5/`capabilityBitmap` 全套校验（`discovery.rs:230-320`）。
8. 实现 JSON 转义 + 60 字符按码点截断（`discovery.rs:164`、`lib.rs:307`）；端口从 `serviceData` 的 `port:` 取（`discovery.rs:290`）。
9. `accepts = (caps[0] and 256) != 0`；无此位也保留设备（`discovery.rs:288`）。
10. 收 CON 必回 ACK；`isDiscoveryPacket` 粗筛（`discovery.rs:41`）。
11. `PeerStore` 按 host 去重/更新，刷新 `lastSeen`，300s TTL（`peers.rs:148-204`）。
12. Android：申请 MulticastLock、前台服务、网络切换重建 socket、广播+组播双发（`discovery.rs:15,345,349-354`、`app.rs:156-159`）。

---

## 11. 「能被华为手机的华为分享扫描到」的实现要点

Windows 版能出现在华为手机「华为分享 → 选设备」列表里，靠的是下面这一整套行为。移植时**任何一条漏掉都会导致扫不到**：

### 11.1 协议侧（必须逐条对齐）

| 编号 | 要求 | Rust 依据 | 安卓实现 |
|---|---|---|---|
| A1 | 监听 UDP **5684** 并绑定到**具体本机 IP**（不是 0.0.0.0），否则华为的单播应答打不进来 | `discovery.rs:13`、`DiscoverySocket::bind(local)` | `Discovery.kt:243-246` |
| A2 | 开启广播 + 加入组播组 **224.0.0.1**；华为既可能广播也可能组播 discovery | `discovery.rs:15,345,349-354` | `Discovery.kt:247-252` |
| A3 | 主动周期广播 `beacon(discovering=true)`（0x50 首字节 = NON）每 **900ms**，广播 + 组播各一份 | `app.rs:702-783` | `Discovery.kt:264-268` |
| A4 | 周期广播**鸿蒙风格 query**（0x42 + 4 字节 token、mode=1、JSON 带 `coapUri`）：**无对端时 1.5s 一次，见到对端后 10s 一次** | `app.rs:729-740` | `Discovery.kt:270-276` |
| A5 | **收到任何含 `device_discover` 标记的对端包，就单播 answer**（0x42 + JSON mode=10），同一对端 **2s** 一次 | `app.rs:750-768` | `Discovery.kt:289,312-319` |
| A6 | 关键细节：应答判断用 `is_discovery_packet`（**只查是否含 `device_discover` 字符串**），**不要求解析成功**。华为的 query 变体解析失败时仍必须应答，否则华为端超时后不再问，设备就消失了 | `app.rs:741` 与解析块分离 | `Discovery.kt:289` 与 `:291` 分离 |
| A7 | 对端用 **CON**（首字节 0x4x）发来的包，必须回 4 字节 ACK `[0x60,0x00,mid0,mid1]`，否则对端按 0/1.2/3.5s 重传三次 | `discovery.rs:226-228`、`app.rs:744-747` | `Discovery.kt:302-309` |
| A8 | 应答 JSON 里 **`type=12`（电脑）**、**`capabilityBitmap=[455]`**（含 `SHARE_CAPABILITY_BIT=256` 接收位）、`bType=5`、`wlanIp` 等于本机 IP、`serviceData="port:5684,"` | `discovery.rs:55-103,160-162` | `Discovery.kt:56-93` |
| A9 | `deviceId` 为嵌套 JSON `{"UDID":"<identity>"}`，`devicename` 截断 60 字符 | `discovery.rs:58-60` | `Discovery.kt:59,61` |

> **为什么是 type=12 而不是 14（手机）**：华为端按设备类型决定「谁可以给我发」。宣称成手机时可能被当成同类设备过滤掉；宣称电脑时才会被列进可发送目标。安卓端沿用 Windows 版的 12。

### 11.2 安卓平台侧（协议之外的硬前提）

| 编号 | 要求 | 说明 |
|---|---|---|
| B1 | **MulticastLock**：前台服务持有 `WifiManager.MulticastLock`，否则系统在省电/后台时丢弃组播包 → 华为扫不到 | 已实现：`HiShareService.kt:125-131`；需 `CHANGE_WIFI_MULTICAST_STATE` 权限 |
| B2 | **前台服务 + `foregroundServiceType="dataSync"`**：发现套接字必须常驻，被杀即失联 | 已实现：`AndroidManifest.xml:47`、`HiShareService.kt:65` |
| B3 | **Wi-Fi 通道常连**：`device_discover` 走 Wi-Fi 承载，AP/流量节省会切断；需保持 Wi-Fi 开启（不依赖移动数据） | 需在设置页给出提示 |
| B4 | **同网段**：华为手机与本机要在同一子网（Rust 解析侧按 /24 判定源地址） | `discovery.rs:238-241` |
| B5 | **网络切换重建 socket**：Wi-Fi 换网络 / IP 变化后必须重绑 socket 并重新广播 | 待实现 |
| B6 | **IP 不含 169.254 / VPN 虚拟网卡**：wlanIp 必须是真实 Wi-Fi 地址，否则华为端不回连 | 待实现（取 `WifiManager` 连接信息） |

### 11.3 自检方法

1. 本机抓包：`tcpdump -i wlan0 -n 'udp port 5684'`，确认**每 900ms 有 0x50 广播**，且收到华为包后有 **0x42 单播应答**。
2. 用安卓端抓包同样命令，确认**收到华为 query 后 2s 内有单播 answer**（哪怕解析不成功也要有）。
3. 华为手机「华为分享 → 选设备」应能刷出本机名称；名称出现 = A1/A5/A6/A8 全部成立。
4. 若名称出现了但发文件过去没反应 → 问题在传输层（握手/通道），不在发现层。
