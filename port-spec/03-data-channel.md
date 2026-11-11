# 数据通道移植规格（HiShare → Android/Kotlin）

> 本文件只覆盖「数据通道」：握手完成后，于三条 TCP 子通道（控制 / 字节 / 文件）上跑的 DFile 分包协议、字节通道（CH16）封帧、文件通道（CH17）封帧，以及发送端与接收端各自的状态机。
> 握手与加密材料（RSA 协商、`session_key`、AES-GCM 信封、ChaCha20-Poly1305 的 AAD）已在 `02-crypto-handshake.md` 写清，本文件只在密码套件协商一节引用其结论，不重复拷贝其字节细节。
> 所有引用均为 `文件:行号`，基于 Rust 实现 `src/src/`。

---

## 0. 范围与角色

本文件描述如下内容，全部以 `src/src/` 的 Rust 实现为准：

- 数据通道在已建成的 **TCP 控制通道**之上，再建立两条专用的 TCP 子连接：字节通道（CH16）与文件通道（CH17）。三条通道由发送端在一个会话内同时轮询（`receive/dfile/mod.rs:28-41`、`outbound/session.rs:281-322`）。
- DFile 是一套 8 字节头的裸分包协议，套在「控制通道的 module 21 加密帧」或「文件通道的裸 TCP 流」之上使用。
- 字节通道与文件通道的控制帧（握手 JSON、进度帧、previewOK 等）本身也是「外层 24 字节帧 + 内层 24 字节 channel 帧」的双层 AES-GCM 信封结构。

| 角色 | 本文称呼 | Rust 入口 |
|------|----------|-----------|
| 发送方（Windows PC / 将移植为 Android 发送端） | **Sender** | `outbound/session.rs::send_once` |
| 接收方（华为手机 / 鸿蒙设备 / 将移植为 Android 接收端） | **Receiver** | `receive/dfile/mod.rs::run_session_once` |

> 注意：当前真实产品形态里「手机/鸿蒙」是接收方。让 Android 既当发送也当接收，是本次移植的目标，但协议本身对两端对称，只是握手探测出的 `PeerKind`（鸿蒙 / Apple）会影响若干档位与字段。凡是「以对端为准」的地方，下面都标了判定来源。

---

## 1. 通道分层与建立顺序

一次完整传输占用 **三条 TCP 连接**，职责分工如下（`receive/dfile/mod.rs:30-37`）：

| 通道 | 作用 | 建立方向 |
|------|------|----------|
| `control`（握手时的那条 TCP） | 首帧回执（user_confirm/reject）、字节/文件通道的握手应答、取消与控制命令、previewOK | 一直存在 |
| `byte`（字节通道，CH16） | 预览载荷（图片缩略图等）、接收端主动上报的进度帧、名单帧 | 由 Sender 发起控制帧请求，Receiver 监听端口回应 |
| `file#1`（文件通道，CH17） | 文件清单（kind 1）、真正的数据块（kind 4）、收尾 DONE/DONE_ACK（kind 6/7） | 由 Sender 发起控制帧请求，Receiver 监听端口回应 |

### 1.1 建立顺序（以 Sender 主动发起为准）

1. 控制通道完成握手，得到 `session_key`(32B) 与 `device_hash`（`outbound/handshake.rs` 的 `do_handshake` 见 `outbound/session.rs:31`；密钥派生见 `02`）。
2. Sender 发 `offer`（module 9 信封，tag3/tag6 文件名与总数，`outbound/session.rs:238-239`），Receiver `user_confirm` 或 `user_reject` 回执（`receive/dfile/mod.rs:68-88`）。
3. 若确认，Sender 在控制通道发 **字节通道请求**（JSON，`CODE:1`，`outbound/handshake.rs:36` 的 `byte_handshake`），Receiver 收到后 `bind` 监听端口并应答（`receive/dfile/mod.rs:1259-1275`）。
4. 字节通道 TCP 连上后，Sender 发预览载荷（或等 previewOK，`outbound/preview.rs`）。
5. Sender 在控制通道发 **文件通道请求**（JSON，`CODE:1538`，`outbound/handshake.rs:71` 的 `file_channel_request`），Receiver 应答端口（`receive/dfile/mod.rs:1277-1346`）。
6. 文件通道 TCP 连上后，Sender 发 **SETTINGS 帧（kind 8）**，Receiver 回 SETTINGS，再进入清单/数据/收尾阶段（`outbound/session.rs:521-592`）。

> 关键坑：字节通道（CH16）的 JSON 请求里 `CODE` 必须是 **1**（`outbound/handshake.rs:56`），而文件通道（CH17）请求里 `CODE` 必须是 **1538**（`outbound/handshake.rs:90`、`receive/dfile/mod.rs:1278` 校验 `value["CODE"] != 1538`）。两者不可混用。

---

## 2. DFile 8 字节头

裸分包协议头由 `transfer::assemble_dfile_packet` 拼装（`transfer.rs:189-201`），由 `transfer::parse_dfile_header` 解析（`transfer.rs:347-361`）。

整帧布局（**大端 BE**）：

```
| 偏移 | 长度 | 字段        | 类型/编码  | 说明 |
|------|------|-------------|-----------|------|
| 0    | 1    | kind        | u8        | 帧类型，见 2.1 |
| 1    | 1    | flags       | u8        | 含义见各帧 |
| 2    | 2    | session     | u16 BE    | 会话号，通常 0 |
| 4    | 2    | transaction | u16 BE    | 事务号（一批文件一个 transaction） |
| 6    | 2    | size        | u16 BE    | 后续 payload 字节数 |
| 8    | size | payload     | 原始字节  |      |
```

校验约束（`transfer.rs:357-358`、`assemble_dfile_packet` 的 `transfer.rs:190`）：

- `kind` 必须在 `1..=31` 区间（含 22），否则 `Error::Format` / `Error::Argument`。
- `payload.len()` 不得超过 `u16::MAX`(65535)。
- 文件通道在裸 TCP 上读取时，`try_read_dfile` 先读 8 字节头、按 `size` 读到整帧（`channel.rs:470-500`）。

### 2.1 kind 编号总表

来自 `transfer::dfile_kind_name`（`transfer.rs:123-141`），这是唯一权威来源：

| kind | 名称 | 方向 | 含义 |
|------|------|------|------|
| 1 | LIST | S→R | 文件清单（含条目 id/size/name） |
| 2 | ACK | R→S | 清单确认，payload 为已接收条目 id 列表（BE u16 序列） |
| 3 | REQ | — | 请求重传某条目（本实现未主动使用） |
| 4 | DATA | S→R | 数据块，payload 内含 file_id/sequence/AEAD |
| 5 | DATA_ACK | R→S | 数据块确认（本实现收到后仅记录/忽略） |
| 6 | DONE | R→S | 一批文件收齐，payload 为已完成条目 id 列表 |
| 7 | DONE_ACK | S→R | 对 DONE 的确认，`build_done_ack`（`outbound/dfile.rs:69-71`） |
| 8 | SETTINGS | 双向 | 能力/档位协商帧（见第 5 节） |
| 9 | RST | 双向 | 中止/重置传输（`receive/dfile/mod.rs:716-721`） |
| 10 | FLOW | — | 流控（本实现未主动发送，仅 `frame_decode` 等占位） |
| 11 | CONGEST | R→S | 拥塞窗口通告（见第 8 节） |
| 12 | PEER_OFF | — | 对端离线通告（占位） |
| 13 | BACKPRESSURE | R→S | 背压：要求发送端减速（见第 8 节） |
| 22 | BIG_FRAME | 双向 | 大帧/未知信号，收到后暂停 20ms（见第 8 节） |
| 其它 | UNKNOWN | — | `Error::Format` 或忽略 |

> 坑：kind=10(FLOW) 在 Rust 源码里**没有对应的收发逻辑**（仅出现在 `dfile_kind_name` 名字表里），移植时不要臆造 FLOW 发送/接收分支；真实的限速靠 CONGEST(11) 与 BACKPRESSURE(13)。

### 2.2 flags 含义

`flags` 是 1 字节，含义随 kind 不同：

- **DATA 帧（kind 4）**：`build_data_block` 里的 `flags` 取值约束为 `0 | 1 | 3`（`transfer.rs:635`），由发送端按发送进度设置（`outbound/dfile.rs:170-176`）：
  - `0` = 文件中间块（非首非尾）；
  - `1` = 中间块（sequence>0 的常规块，源码里中间块实际用 `1`，首块 `sequence==0` 用 `0`）；
  - `3` = **最后一块**（文件收尾块，`offset >= file.size` 时设 `flag=3`）。
  - 接收端据此判定文件是否写完：`flags == 3 || written >= declared` 触发落盘收尾（`receive/dfile/mod.rs:626`）。
- 其它 kind 的 flags 一般为 0。

---

## 3. 外层封装：字节通道帧与文件通道帧

### 3.1 控制通道的 module 21 信封（CH16/CH17 握手与进度）

字节通道与文件通道上的「控制语义」（握手 JSON、previewOK、进度帧）不是 DFile 头，而是：

- 外层 24 字节帧，module = **21**（`ENVELOPE_MODULE` 之外的 `outbound/handshake.rs` 用 `CHANNEL_BYTES` 等，`transfer::parse_channel_frame` 校验 `outer_module == 21`，`transfer.rs:149`）。
- 内层再包一层 24 字节「channel 帧头」+ AES-GCM(12B nonce + 密文 + 16B tag)（`transfer::parse_channel_frame` 的 `transfer.rs:143-170`；`seal_channel_frame` 在 `transfer.rs:102-?`，`frame_encode(21, …)` 在 `transfer.rs:120`）。

内层 channel 帧头（BE）布局（`transfer.rs:154-160`）：

```
| 偏移 | 长度 | 字段        | 编码 |
|------|------|-------------|------|
| 0    | 4    | module/类型  | u32 BE（如 CHANNEL_BYTES=0xFFFF_0004） |
| 4    | 4    | sub         | u32 BE（子类型，如 6/16/17） |
| 8    | 8    | sequence    | u64 BE |
| 16   | 4    | flags       | u32 BE |
| 20   | 4    | payload_len | u32 BE（见 3.2 的 len 校验规则） |
```

`payload_len` 必须等于「整帧长度 − 2×24」（`transfer.rs:161-163`），否则 `Error::Format`。

### 3.2 字节通道的裸预览载荷（CH16 数据面）

真正的预览图片/名单帧是 `transfer::bytes_frame`（`transfer.rs:724-732`）：

- 12B nonce（**前 4 字节 = sequence 的 LE**，`transfer.rs:726`：`nonce[0..4].copy_from_slice(&sequence.to_le_bytes())`）＋ AES-GCM 密文（12B nonce 参与 AEAD，key 为 `byte_key`）。
- 注意 `BYTES_MAGIC = 0xBABE_FACE`（`transfer.rs:722`），但字节帧的读取走 `try_read_bytes_frame`（`transfer.rs:743-792`）：先读 **16 字节前缀**（magic 4 + sequence 4 + 0 + size 4，`CHANNEL_FRAME_PREFIX=16`，`transfer.rs:750`），再按 `size` 读满；`magic` 必须 `== BYTES_MAGIC`，`size` 必须在 `13..=8MiB`（`transfer.rs:760`）。

> 坑：`try_read_bytes_frame` 的「16 字节前缀」中 size 在 **偏移 12**（magic@0, sequence@4, size@12），与上面 8 字节 DFile 头不同，移植时别把两种前缀的偏移搞混。

---

## 4. SETTINGS 帧（kind 8）的三种档位

SETTINGS 帧在文件通道建立后最先交换，决定 MTU、数据帧长、加密头开关、以及最重要的 **密码套件能力（caps）**。构造逻辑在 `transfer::SettingsProfile`（`transfer.rs:211-328`）。

### 4.1 三种档位

| 档位 | 构造入口 | mtu | connection_type | version | capability | data_frame_size | caps_check | 尾部 caps |
|------|----------|-----|-----------------|---------|-----------|-----------------|------------|-----------|
| 发送端基线（鸿蒙） | `sender_baseline(1472, frame_size)` | 1472 | 2 | 1 | 0 | frame_size | `0x144` | `tail=(0, 1)` |
| 接收端 | `receiver(encrypt, mtu, dfs)` | mtu | 2 | 1 | 0 | dfs | `encrypt?0x100:0` | `tail=(0, OUR_CIPHER_CAPS=0x7)` |
| 手机精确档（Apple） | `phone_exact()` / `phone_exact_with(dfs)` | 1472 | 2 | 1 | `0x40` | 65535 | `0x36C` | `exact_tail=(1,10,OUR_CIPHER_CAPS=0x7)` |

- 默认值：鸿蒙发送端调用 `settings_profile`（`outbound/dfile.rs:28-34`）时，鸿蒙用 `sender_baseline(1472, frame_size)`，Apple 用 `phone_exact_with(frame_size)`；接收端回的 SETTINGS 用 `dfile_settings_with(true, recv_dfs)`（`receive/dfile/mod.rs:419`，`recv_dfs = env("HISHARE_RECV_DFS", 65485)`）。
- `OUR_CIPHER_CAPS = CIPHER_CLEAR|CIPHER_CHACHA|CIPHER_AES = 0x7`（`transfer.rs:365-369`）。

### 4.2 SETTINGS payload 字节布局（BE）

由 `SettingsProfile::encode`（`transfer.rs:287-327`）生成，结构：

```
| 偏移 | 长度 | 字段 | 编码 | 备注 |
|------|------|------|------|------|
| 0    | 2    | mtu | u16 BE | |
| 2    | 2    | connection_type | u16 BE | 恒 2 |
| 4    | 4    | version | u32 BE | 恒 1 |
| 8    | 4    | abm_capability | u32 BE | 恒 0 |
| 12   | 4    | capability | u32 BE | Apple 档为 0x40，其它 0 |
| 16   | 4    | data_frame_size | u32 BE | |
| 20   | 4    | caps_check | u32 BE | 见 4.3 |
| 24   | 65/67 | 尾部 | 见下 | 见 4.3 |
```

尾部两种形态（`transfer.rs:296-317`）：

- **有 `exact_tail`（Apple 档）**：65 字节 name（ASCII，不足补 0/`\0`），接着 1 字节 `encrypt_headers`，1 字节 0，然后 3 个 **LE** 的 u32：`a=1, b=10, c=OUR_CIPHER_CAPS`（`transfer.rs:303-308`）。整体长度 = 24 + 65 + 2 + 12 = **103 字节**。
- **无 `exact_tail`（鸿蒙档）**：67 字节全 0，接着 1 字节 `encrypt_headers`，然后 2 个 **BE** 的 u16 `tail.0, tail.1`（`transfer.rs:312-315`）。整体长度 = 24 + 67 + 1 + 4 = **96 字节**。

> 端序坑：caps 尾部的 `(1,10,caps)` 在 Apple 档是 **小端(LE)**（`transfer.rs:306-308`），而鸿蒙档的 `tail=(0, OUR_CIPHER_CAPS)` 是 **大端(BE)**（`transfer.rs:314-315`）。发送 Android 端若按鸿蒙档实现，必须按 BE 写尾部；按 Apple 档则必须按 LE 写。

### 4.3 caps_check 与尾部 caps 的含义

- `caps_check`（`transfer.rs:20..24`，即 payload 偏移 20，u32 BE）：发送端基线 `0x144`、接收端 `0x100`(加密开)/`0`(关)、Apple `0x36C`。它是对端用于**快速判定能力**的校验值，并非位掩码全集；源码在 `log_settings`（`receive/dfile/wire.rs:112-117`）里把它与 `payload[91]==1`（encrypt_headers）和 `payload[96..100] & 1`（是否支持加密传输）一起做**诊断日志**而非硬性拒绝（仅打印告警），所以移植时：收到 caps_check 不符不要直接断链，只告警。
- 尾部 caps（即 `OUR_CIPHER_CAPS` 的值 0x7）：表示本端支持的密码算法位（`CIPHER_CLEAR=0x1`、`CIPHER_CHACHA=0x2`、`CIPHER_AES=0x4`，`transfer.rs:365-367`）。

### 4.4 密码套件协商（caps 位含义 + 不对称问题）

协商函数 `negotiate_cipher` / `choose_cipher`（`transfer.rs:409-466`）。`choose_cipher` 的规则：

1. 若对端未声明 caps（`None`）：Apple 家族 → ChaCha20-Poly1305；其它 → **Aes128Gcm**（默认，`transfer.rs:412-416`、测试 `transfer.rs:825`）。
2. `has_aes = caps & 0x4`，`has_chacha = caps & 0x2`：
   - 仅 chacha（无 aes）→ ChaCha20Poly1305（`transfer.rs:420-422`）。
   - 有 aes → **Aes128Gcm**（优先级高于仅 chacha，`transfer.rs:423-425`、`transfer.rs:805`）。
   - 仅 chacha → ChaCha20Poly1305（`transfer.rs:426-427`）。
   - 都没有 → **Clear（明文）**（兜底，`transfer.rs:428-430`、`transfer.rs:820`）。

`negotiate_cipher`（`transfer.rs:433-461`）额外用 `has_chacha` 推断 `PeerKind`：有 chacha 且也有 aes → Harmony；有 chacha 无 aes → Apple（`transfer.rs:440-446`）。

**不对称问题（移植必须注意）：**

- Sender 端在 `outbound/session.rs:563-567` 有一段**硬性校验**：若对端非 Apple（`!peer.is_apple()`），则要求 `payload[96..100]`（即尾部 caps）的 bit0（CIPHER_CLEAR=0x1）**必须置位**，否则 `Error::Protocol("对端 DFile 能力不支持加密传输")`。即鸿蒙家族的接收端必须在其 SETTINGS 尾部 caps 里带上 `CLEAR` 位（值 0x7 含 0x1，满足）。
- 但 `choose_cipher` 在双方都有 AES 时**优先选 AES 而非明文**，所以实际走 AES-GCM，明文只是兜底。Android 接收端若声明 caps=0x7（含 clear+aes+chacha），则既能通过 Sender 的明文位校验，又能协商到 AES。
- 明文兜底链路：`Cipher::Clear` 下 `dfile_seal_control` 直接返回裸 packet（`transfer.rs:581-582`），`open_control_block` 也不解密（`transfer.rs:606-611`）。这意味着若协商到 Clear，DFile 头是明文裸传——这是与 02 文档「全程加密」不一致之处，移植时建议 Android 端**只声明 AES/ChaCha，不依赖明文兜底**以保证安全，但需兼容对端可能回 Clear。

> 坑：`settings_cipher_caps`（`transfer.rs:330-341`）读取尾部 caps 时**按 payload 长度选择端序**：`len >= 103` 用 **LE**（Apple 档），否则用 **BE**（鸿蒙档）。Android 端解析对端 SETTINGS 时必须用同一规则，否则 caps 位全错。

---

## 5. 清单（manifest）格式

清单通过多个 **kind=1 LIST 帧**发送，单帧有预算上限，超长会拆成多帧（`transfer::build_headers`，`transfer.rs:678-720`）。

### 5.1 单帧 payload 布局（BE）

首 2 字节是**总文件数 count**（u16 BE，每帧都带、且都是总数，非本帧条数；`transfer.rs:687`、测试 `transfer.rs:861-862`），随后是若干「单元」：

```
| 偏移 | 长度 | 字段 | 编码 |
|------|------|------|------|
| 0    | 2    | count | u16 BE（总文件数，每帧相同） |
| 2    | ...  | 单元1 | 见下 |
|      | ...  | 单元2 | ... |
```

每个单元（12 字节头 + 名字，`transfer.rs:694-698`、`receive/manifest.rs:25-38`）：

```
| 偏移(相对单元) | 长度 | 字段 | 编码 |
|----------------|------|------|------|
| 0 | 2 | file_id | u16 BE |
| 2 | 8 | size | u64 BE（声明字节数） |
| 10| 2 | name_len | u16 BE |
| 12| name_len | name | UTF-8 原始字节 |
```

- `build_headers` 的 `FRAME_BUDGET = 1423` 字节（`transfer.rs:684`），单帧 payload 超过即拆帧；单条文件名 >255 字节直接 `Error::Argument`（`transfer.rs:691`），单条装不进 1423 预算报 `Error::Protocol`（`transfer.rs:706-712`）。
- `MAX_MANIFEST_ENTRIES = 4096`（`transfer.rs:676`），超出拒绝（`transfer.rs:679`、`outbound/mod.rs:156-159`）。

### 5.2 接收端合并与 ACK

- `merge_manifest`（`receive/manifest.rs:9-69`）逐单元解析，**以 file_id 去重**（`seen` 集合，`receive/manifest.rs:18-22,40-43`）。
- **空文件**（size==0）不进 `expected_files`，而是进 `empty_files`（`receive/manifest.rs:44-52`）——它们不走数据通道，收尾时按名创建（`cleanup.rs:materialize_empty_files`）。
- 名字为空（`name.trim().is_empty()`）的条目直接忽略（`receive/manifest.rs:53-56`）。
- **ACK 条件**（`receive/dfile/mod.rs:467-478,511-521`）：每收到一帧 LIST，把 `expected_files.len() + empty_files.len()` 与 `manifest_count`（首 2 字节）比较；当 `manifest_count==0` 或已收齐 `>= manifest_count` 时，调用 `ack_manifest` 回 kind=2 ACK（`receive/manifest.rs:71-88`，payload 为已完成+空条目 id 的 BE u16 序列）。
- 若清单**全为空文件**，收齐后立即 `materialize_empty_files` 并 `finish_tx!` 收尾（`receive/dfile/mod.rs:484-498`）。

> 坑：测试 `transfer.rs:848-864` 证实「163 个文件会拆成多帧，且每帧 count 都是 163、transaction 恒为 1」。Android 端合并时**不能**按「本帧条数」判断收齐，必须用首 2 字节的总数与已收集 id 数比较。

### 5.3 空文件表示

空文件在清单里就是 `size==0` 的普通条目（`receive/manifest.rs:44`）。收尾阶段 `materialize_empty_files`（`cleanup.rs:12-51`）逐个 `create_part_atomic` + rename 创建 0 字节文件；特例：`clip::is_target(name)` 的空文件**不落盘**，只写剪贴板（`cleanup.rs:26-33`）。

---

## 6. 数据块分片与流控

### 6.1 数据块帧（kind 4）

构造：`build_data_block`（`transfer.rs:634-657`）。约束（`transfer.rs:635`）：content 非空、长度 ≤ `DFILE_MAX_CONTENT = u16::MAX - DFRAME_OVERHEAD(50)`（`transfer.rs:630-632`）、flags ∈ {0,1,3}、file_id ≠ 0。

加密 payload 布局（**BE**，`transfer.rs:650-656`）：

```
| 偏移 | 长度 | 字段 | 编码 |
|------|------|------|------|
| 0    | 2    | file_id | u16 BE |
| 2    | 4    | sequence | u32 BE（块序号，每块 +1，从 0 起） |
| 6    | ...  | AEAD密文 | AES-GCM/ChaCha（12B nonce 随后） |
|      | 12   | nonce | 12 字节（最后 12 字节） |
```

- 注意：密文**之前**先明文拼了 `file_id(u16 BE) + sequence(u32 BE)` 再整体 AEAD（`transfer.rs:641-648`），nonce 12B 附在密文尾部（`transfer.rs:653-654`）。
- 解密：`dfile_parse_chunk`（`transfer.rs:659-674`）先取前 6 字节得 file_id/sequence，再 split 出密文与末尾 12B nonce，AEAD 解开后 `plain[8..]` 才是真正的文件内容（`transfer.rs:673`）。

接收端处理（`receive/dfile/mod.rs:553-713`）：

- `kind==4 && header_seen && trans==transaction` 才接受（`receive/dfile/mod.rs:553`）。
- `dfile_parse_chunk` 解出 (file_id, sequence, content)（`receive/dfile/mod.rs:554`）。
- 若 `active != file_id`，开新文件：`ActiveFile::open`（`receive/paths.rs:67-98`）——普通文件落盘到 `.part`（`create_part_atomic`），名字是 `Huawei Share.txt` 的走内存缓冲（`clip::is_target`，`receive/paths.rs:74-85`）。
- 写入 `slot.write(content)`（`receive/paths.rs:100-119`），累计 `written`。
- 收尾判定：`flags==3 || written >= declared`（`receive/dfile/mod.rs:626`）；若 `flags==3` 但 `written < declared` → 报错「文件不完整」并保留残包（`receive/dfile/mod.rs:630-641`）。
- 收尾：`slot.finish(destination)` 把 `.part` rename 成目标名（`receive/paths.rs`），落盘的进 `saved`、文本的进 `clip_info`，`done_count += 1`（`receive/dfile/mod.rs:644-694`）。

### 6.2 发送端分块参数

- 单块大小：`chunk_size()`（`outbound/dfile.rs:13-21`），默认 `DFILE_MAX_CONTENT`，受 `HISHARE_CHUNK` 或 config 限制到 `[512, DFILE_MAX_CONTENT]`。
- 「帧长」`data_frame_size(chunk) = (chunk + DFRAME_OVERHEAD).min(u16::MAX)`（`outbound/dfile.rs:23-26`）。
- 批量发送：每 `BATCH_BYTES = 256*1024` 字节凑一批发出（`outbound/dfile.rs:11`、`outbound/dfile.rs:181`），每发 `sequence % 64 == 0` 或一批后尝试读对端回帧（`outbound/dfile.rs:216-217`）。

### 6.3 流控帧：CONGEST / BACKPRESSURE / FLOW / BIG_FRAME

- **CONGEST (kind 11)**：接收端在收到 SETTINGS 后立刻回一个固定 32 字节的 CONGEST 帧（`receive/dfile/wire.rs:144-152`、`build_congest_frame`；`receive/dfile/mod.rs:422-424`）；其内容 `11,0,0,0,0,0,0,24` + 24 字节固定体（窗口值 `0x1B0`=432）。发送端对 Apple 档也主动发同样内容的 CONGEST 作为初始窗口（`outbound/dfile.rs:318-327`、`send_batch`）。收到 CONGEST 在发送端**仅记录日志，不影响限速**（`receive/dfile/mod.rs:439` 的 `11 => {}` 空分支）。即 CONGEST 是「一次性窗口通告」，非持续流控。
- **BACKPRESSURE (kind 13)**：接收端在拥塞时回 kind=13（`outbound/dfile.rs:490-500` 收到后 `backpressure++`、暂停 50ms）。Rust 里接收端何时发 13 未在本批文件显式出现（仅在发送端有「收到 13 就减速」的处理），移植时建议：接收端缓冲水位高时发 kind=13，发送端收到即 `sleep(50ms)` 并计数（`outbound/dfile.rs:492-499`）。
- **FLOW (kind 10)**：源码**无收发逻辑**，仅名字表占位（见 2.1）。不要实现。
- **BIG_FRAME (kind 22)**：发送端收到 kind=22 当作「未知信号」，暂停 20ms（`outbound/dfile.rs:264-275`、`outbound/dfile.rs:485-489`）；接收端一般不发。移植时：收到 22 只需 `sleep(20ms)` 忽略即可。

> 坑：CONGEST 在**接收端**是 SETTINGS 之后立即回（`receive/dfile/mod.rs:422`），但**发送端**对 Apple 档是在 `send_batch` 开头发（`outbound/dfile.rs:318-327`）。双端都会发 CONGEST，不要只实现一端；且它的 32 字节二进制体是写死的，不必解析语义，照发照收。

---

## 7. 收尾、落盘与清理

### 7.1 目录结构与命名

- 目标目录由 Sender 的 `offer` 决定、Receiver 在 `run_session_once` 里 `create_dir_all(destination)`（`receive/dfile/mod.rs:90`）。
- 落盘写 `.part` 临时文件，收齐后 rename 成最终名（`receive/paths.rs` 的 `create_part_atomic` + `finish`）。失败清理由 `PartGuard`（Drop 时删未完成 `.part`，`cleanup.rs:53-88`）与 `OverlayGuard`（Drop 时 `ov::abort` 撤销浮窗，`cleanup.rs:90-101`）用 RAII 兜住——**成功失败都要清理**。

### 7.2 重名处理

`create_part_atomic`（`receive/paths.rs`，具体去重逻辑在其后段）负责文件名清洗：非法字符、Windows 保留名、重名去重。移植到 Android 时，路径清洗需改为适配 SAF/ScopedStorage 的规则（见第 9 节）。

### 7.3 DONE / DONE_ACK 收尾状态机

- 接收端收齐 `expected_files.len()` 个文件后，发 **DONE（kind 6）**（`finish_tx!` 宏，`receive/dfile/mod.rs:306-347`，payload 为已完成 id 的 BE u16 序列，`transfer::assemble_dfile_packet(6,…)`，`receive/dfile/mod.rs:313`）。
- 发送端收到 DONE（kind 6）后把 id 并入 `done`，当 `done.len() >= files.len()` 时发 **DONE_ACK（kind 7）** 并结束（`outbound/dfile.rs:450-477`、`finish_batch`，`build_done_ack` 在 `outbound/dfile.rs:69-71`）。DONE_ACK 会重试最多 3 次（`outbound/dfile.rs:83-96`）。
- 接收端收到 **DONE_ACK（kind 7，`done_sent` 后）** 置 `done_acked=true`、提交 PartGuard、结束（`receive/dfile/mod.rs:722-735`）。
- **多事务**：若还有更多文件（`done_count < total_expected`），DONE 之后 `pending_more=true`，清空 expected/empty/done_ids、重置 `header_seen` 等待下一个 transaction（`receive/dfile/mod.rs:317-345`）。

### 7.4 RST / 取消

- 任一方发 **RST（kind 9）** 即中止：`receive/dfile/mod.rs:716-721`、`outbound/dfile.rs:263,478,9`。
- 控制通道 JSON 里含 `"CANCEL"` 或 `"取消"` 也判为取消（`receive/dfile/mod.rs:1250-1254,1356-1359`）。
- `user_reject` 直接结束（`receive/dfile/mod.rs:81-88`）。

---

## 8. 两个状态机（发送端 / 接收端）逐帧分支

### 8.1 接收端状态机（`receive/dfile/mod.rs::run_session_once`）

主循环三路轮询，局部状态变量：`settings_seen / header_seen / active_file / done_sent / done_acked / pending_more / transaction / manifest_count`（`receive/dfile/mod.rs:38-41`）。文件通道的处理 `match kind`（`receive/dfile/mod.rs:412-745`）：

| 收到 kind | 条件 | 动作 | 行号 |
|-----------|------|------|------|
| 8 SETTINGS | `!settings_seen` | 记录日志、回本端 SETTINGS（明文）、回 CONGEST(11) | `receive/dfile/mod.rs:414-437` |
| 11 CONGEST | 任意 | 空处理（已发过） | `receive/dfile/mod.rs:439` |
| 1 LIST | `settings_seen && !header_seen` | 解密、合并清单、判定收齐则 ACK、空文件直接收尾 | `receive/dfile/mod.rs:441-509` |
| 1 LIST | `header_seen` | 续帧合并（同一 transaction 多帧） | `receive/dfile/mod.rs:511-551` |
| 4 DATA | `header_seen && trans==transaction` | 解密分块、写文件/缓冲、满足收尾条件则落盘、done_count 达标则发 DONE | `receive/dfile/mod.rs:553-713` |
| 9 RST | 任意 | 报错中止 | `receive/dfile/mod.rs:716-721` |
| 7 DONE_ACK | `done_sent` | 置 done_acked、提交清理、结束 | `receive/dfile/mod.rs:722-735` |
| 其它 | — | 调试日志忽略 | `receive/dfile/mod.rs:736-744` |

控制通道（`receive/dfile/mod.rs:909-1361`）额外处理：字节通道请求(CODE:1)、文件通道请求(CODE:1538)、取消 JSON、meta 帧。字节通道（`receive/dfile/mod.rs:749-894`）处理预览载荷、名单帧→previewOK、进度回报。

### 8.2 发送端状态机（`outbound/session.rs::send_once` + `outbound/dfile.rs`）

控制通道轮询（`outbound/session.rs:281-322`）：解析 JSON 头，处理字节通道应答(CODE:1)、文件通道应答(CODE:1538)，校验 `MY_IP` 为私有 IPv4（`is_private_ipv4`，`outbound/handshake.rs:22-34`）与 `P2P_PORT` 非零（`outbound/session.rs:524-529`），然后连文件通道、发 SETTINGS、等对端 SETTINGS 并协商密码（`outbound/session.rs:521-592`）。

文件通道内 `send_batch`（`outbound/dfile.rs:306-538`）循环读对端回帧，`match kind`：

| 收到 kind | 动作 | 行号 |
|-----------|------|------|
| 6 DONE | 合并完成 id，`done.len()>=files.len()` 则 `finish_batch` 发 DONE_ACK 结束；否则补发 DONE_ACK | `outbound/dfile.rs:450-477` |
| 8 SETTINGS | 记录 | （发送端在连上后等 SETTINGS，见 `outbound/session.rs:549-556`） |
| 9 RST | 报错「接收端中止」 | `outbound/dfile.rs:478` |
| 5 DATA_ACK | 仅记录/忽略 | `outbound/dfile.rs:479-484` |
| 22 BIG_FRAME | 暂停 20ms | `outbound/dfile.rs:485-489` |
| 13 BACKPRESSURE | 减速暂停 50ms | `outbound/dfile.rs:490-500` |
| 其它 | 调试日志忽略 | `outbound/dfile.rs:501-510` |

`send_file_chunks`（`outbound/dfile.rs:116-305`）单文件分块发送循环：读文件→`build_data_block`→攒批→`BATCH_BYTES` 后 flush，每 64 块或一批读一次对端回帧（处理 kind 5/22/9/13，`outbound/dfile.rs:216-292`）。

---

## 9. Android 侧注意事项

1. **SAF / ScopedStorage 写入路径**：Rust 端用 `create_dir_all` + `.part` + rename（`receive/dfile/mod.rs:90`、`receive/paths.rs`）。Android 10+ 对外部存储默认 ScopedStorage，不能自由写任意路径。建议：
   - 用 `ContentResolver` + `DocumentsContract` 在用户授权的目录（如 `Download/HiShare`）创建目标文件，`.part` 用同一 URI 下的临时文件，收齐后「重命名」（实际是 `ContentValues.DISPLAY_NAME` 修改或 copy）。
   - 文件名清洗规则（`receive/paths.rs` 处理非法字符/Windows 保留名/重名）必须移植并适配 Android 合法文件名（去掉 `/`、`\0`、 trailing `.`/空格等）。
2. **后台 / 前台服务**：三路通道要同时轮询且可能耗时数分钟，必须在 `ForegroundService`（如 `HiShareService.kt`）里跑，带常驻通知，否则系统会杀进程。Rust 端靠进程常驻，Android 端要显式用 `startForeground`。
3. **任务取消与清理**：
   - 取消分两路：用户取消发 RST(kind 9) 或控制通道 CANCEL JSON；传输中取消要保留/删除 `.part`（`PartGuard` 语义，`cleanup.rs:53-88`）。
   - Android 端要用 `Job`/`Coroutine` 的 `cancel()` 配合 RAII 式清理：未完成临时文件务必删除，浮窗/通知在 `finally` 里撤销（等价于 `OverlayGuard`，`cleanup.rs:90-101`）。
4. **进度上报**：Rust 端每 120ms `draw_progress`（`outbound/dfile.rs:211-214`）、每 5s 速率日志、接收端每 1s 发 `dfileRecvPercent#` 进度帧（`receive/dfile/wire.rs:83-110`，走字节通道）。Android 端建议：发送端用 `progress` lambda 更新 `LiveData`/通知；接收端把 `total_received` 通过字节通道进度帧回报给对端（使对端浮窗显示双向进度）。
5. **previewOK 时序**：Android 若作为接收端，必须在字节通道握手完成后、收到预览载荷（或名单帧 `is_name_list_frame`，`receive/dfile/wire.rs:36-42`）后回 `previewOK`（`receive/dfile/mod.rs:827-873`）；若作为发送端且是平板/Apple，预览在文件通道之前提前发（`outbound/session.rs:495-501`）。
6. **明文兜底安全**：见 4.4。Android 端建议只声明 AES/ChaCha 能力（caps 不含 CLEAR 也可协商到 AES），但若对端（老 Windows 发送端）协商到 Clear 必须能兼容裸传（不解密）。
7. **大端约定**：DFile 头、清单单元、数据块头、SETTINGS 主体、尾部 caps（鸿蒙档）全部 **BE**；仅 Apple 档 SETTINGS 尾部的 `(1,10,caps)` 与字节帧 nonce 前 4 字节（sequence LE）是 **LE**。`settings_cipher_caps` 按 payload 长度判端序（≥103 LE，否则 BE，`transfer.rs:330-341`），Android 解析必须照搬。

---

## 10. 最小可用实现建议（Android 端 Kotlin 文件映射）

下表给出 Android 端需要的最小函数集，并逐一对应到 Rust 端实现。Kotlin 骨架文件已存在于 `android/core/src/main/java/com/hishare/core/`（`Protocol.kt`/`Sender.kt`/`Receiver.kt`/`Channel.kt`/`Crypto.kt`/`Bytes.kt`）。

| Android 文件 / 函数 | 职责 | 对应 Rust 实现（文件:行） |
|---------------------|------|---------------------------|
| `Protocol.kt` 常量 | `CHANNEL_BYTES=-65532`、`PEER_META_FRAME` 等、`BYTES_FLAG_REQUEST=0x94`、`MODULE_*` | `outbound/handshake.rs:11-16`、`lib.rs:67-77` |
| `Protocol.kt::openingPayload/openingAck/localCapabilityFrame/capabilitiesPhone` | 握手 opening 与能力帧 | `outbound/session.rs:49-100`、`auth.rs` |
| `Protocol.kt::sealHandshakeEnvelope/openEnvelope/envelopeMode` | module 9 信封（见 02） | `transfer.rs:33-80`、`auth.rs` |
| `Channel.kt::connect/tryReadFrame/readChannelFrame` | TCP 连接与 24 字节帧读写 | `channel.rs:230,394,430` |
| `Crypto.kt::aesGcmEncrypt/Decrypt/chacha` | AEAD（数据通道用，AAD=DFILE_AAD） | `transfer.rs:363,551-568`、`02` 第 8-9 节 |
| `Bytes.kt::tlvEncode/varintEncode/frameHeader` | TLV / varint / 帧头 | `lib.rs:203-289` |
| **发送端** `Sender.kt::send` | 主发送流程（建链→offer→三路轮询） | `outbound/session.rs:195-322` |
| `Sender.kt::handshake` | 握手拿 `session_key`/`device_hash` | `outbound/session.rs:31-181` |
| `Sender.kt` 内 `byteHandshake`/`fileChannelRequest` | 生成 CH16/CH17 请求 JSON | `outbound/handshake.rs:36,71` |
| `Sender.kt` 内 `sendBatch`/`sendFileChunks` | 发 SETTINGS→清单→数据块→DONE/DONE_ACK | `outbound/dfile.rs:116-538` |
| `Sender.kt` 内 `buildHeaders` | 清单分帧（count+单元，1423 预算） | `transfer.rs:678-720` |
| `Sender.kt` 内 `buildDataBlock` | 数据块封装（file_id+seq+AEAD+nonce） | `transfer.rs:634-657` |
| `Sender.kt` 内 `assembleDfilePacket` | 8 字节 DFile 头 | `transfer.rs:189-201` |
| `Sender.kt` 内 `settingsProfile` | SETTINGS 三档（sender_baseline/phone_exact） | `transfer.rs:228-285` |
| `Sender.kt` 内 `negotiateCipher` | caps 协商（含明文兜底、端序） | `transfer.rs:409-466,330-341` |
| **接收端** `Receiver.kt::runSessionOnce` | 主接收流程（三路轮询状态机） | `receive/dfile/mod.rs:43-1395` |
| `Receiver.kt` 内 `mergeManifest` | 清单合并/去重/空文件 | `receive/manifest.rs:9-69` |
| `Receiver.kt` 内 `ackManifest` | 回 kind=2 ACK | `receive/manifest.rs:71-88` |
| `Receiver.kt` 内 `parseDfileHeader` | 解析 8 字节头 | `transfer.rs:347-361` |
| `Receiver.kt` 内 `dfileParseChunk` | 解密数据块 | `transfer.rs:659-674` |
| `Receiver.kt` 内 `openControlBlock` | 解密控制块（SETTINGS/DONE） | `transfer.rs:605-628` |
| `Receiver.kt` 内 `materializeEmptyFiles` | 创建 0 字节文件 | `receive/dfile/cleanup.rs:12-51` |
| `Receiver.kt` 内 `createPartAtomic`/`finish` | `.part` 写入与 rename | `receive/paths.rs:67-?` |
| `Receiver.kt` 内 `buildCongestFrame` | 回 CONGEST(11) | `receive/dfile/wire.rs:144-152` |
| `Receiver.kt` 内 `buildDone` | 发 DONE(6) | `receive/dfile/mod.rs:306-347` |
| `Receiver.kt` 内 `sendProgressFrame` | 进度回报（字节通道） | `receive/dfile/wire.rs:83-110` |

**最小跑通路径建议（先做能发通一条文件）：**

1. `Channel.kt` 实现 24 字节帧收发（复用 `Protocol.kt` 常量与 `Bytes.kt` 工具）。
2. `Crypto.kt` 实现 AES-GCM（nonce 12B/16B 两种）与 ChaCha20-Poly1305（AAD=`DFILE_AAD`）。
3. Sender：`handshake` → `send` 主循环 → `settingsProfile`(鸿蒙 baseline) → `buildHeaders` → `buildDataBlock` → 等 DONE/DONE_ACK。
4. Receiver：`runSessionOnce` → `parseDfileHeader` → `mergeManifest`+`ackManifest` → `dfileParseChunk` → `createPartAtomic`/`finish` → `buildDone`/`openControlBlock`(DONE_ACK)。
5. 流控先只实现「收到 CONGEST/BACKPRESSURE/kind22 就 sleep 忽略」，不必做精细窗口；密码协商先固定 AES（两端都声明 caps=0x7），暂不依赖明文兜底。
6. 取消/清理用 Kotlin `use{}`/Coroutine `finally` 模拟 Rust 的 `PartGuard`/`OverlayGuard` RAII。

---

## 11. 与预期不一致 / 需要注意的坑（速查）

- **FLOW(10) 无实现**：名字表里有，但收发逻辑都没有；不要凭名字造 FLOW 分支。
- **CONGEST(11) 是一次性写死的 32 字节体**，非持续流控；双端都会发（接收端在 SETTINGS 后、发送端对 Apple 在 send_batch 开头）。
- **SETTINGS 尾部 caps 端序不对称**：鸿蒙档尾部 `(0, caps)` 是 **BE**（`transfer.rs:314-315`），Apple 档 `(1,10,caps)` 是 **LE**（`transfer.rs:306-308`）；`settings_cipher_caps` 按 payload 长度 ≥103 判 LE 否则 BE（`transfer.rs:330-341`）。
- **Sender 对鸿蒙档强制校验尾部 caps 的 bit0(CLEAR)**（`outbound/session.rs:563-567`），所以鸿蒙接收端必须带 CLEAR 位（值 0x7 含 0x1），否则断链；但实际协商优先 AES。
- **明文兜底存在**：`Cipher::Clear` 下 DFile 头裸传（`transfer.rs:581-582,606-611`），与 02「全程加密」不完全一致；Android 端建议不主动用明文但需兼容对端。
- **字节帧 vs DFile 帧前缀不同**：字节帧前缀 16B（magic@0, seq@4, size@12，`transfer.rs:750-760`），DFile 头 8B（kind@0, size@6，`transfer.rs:347-355`），别混。
- **清单每帧都带总数 count、transaction 恒为 1**（`transfer.rs:687,861-862`），收齐判定用总数而非本帧条数。
- **DATA flags**：`0`=首块(sequence0)或中间、`1`=中间块、`3`=尾块（`transfer.rs:635,170-176`），接收端以 `flags==3 || written>=declared` 收尾（`receive/dfile/mod.rs:626`）；`flags==3` 但字节不足会报「文件不完整」并留残包。
- **空文件不走数据通道**，仅在收尾时创建；`Huawei Share.txt` 空内容只写剪贴板不落盘（`cleanup.rs:26-33`）。
- **文件名上限 255 字节**（`transfer.rs:691`），单条超 1423 预算直接失败（`transfer.rs:706-712`），Android 端需提前截断/报错而非静默。
- **DONE_ACK 重试 3 次**（`outbound/dfile.rs:83-96`），接收端收到 DONE_ACK 才真正提交清理（`receive/dfile/mod.rs:722-735`），Android 端不要收到 DONE 就立即删 `.part`。
