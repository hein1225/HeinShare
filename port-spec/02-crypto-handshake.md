# 握手与加密层移植规格（HiShare → Android/Kotlin）

> 本文件只覆盖「握手与加密层」：TCP 建链后的认证/控制通道握手、RSA 密钥协商、会话密钥派生、以及控制帧/信封帧的对称加解密。
> 数据通道（DFile 分包、预览、字节通道的裸协议）仅在本文件中作为「密码学套件选择」被引用，其逐字节分包协议另见 `03-data-channel.md`（待写）。
> 所有引用均为 `文件:行号`，基于 Rust 实现 `src/src/`。

---

## 0. 术语与角色

| 角色 | 本文称呼 | Rust 入口 |
|------|----------|-----------|
| 发送方（Windows PC / 将移植为 Android 发送端） | **Sender** | `outbound/session.rs::do_handshake` |
| 接收方（华为手机 / 鸿蒙设备） | **Receiver** | `receive/opening_payload.rs::accept_connection_inner` |

握手在 **TCP 控制通道**上完成。控制通道端口为 `5684`（发现 UDP 与 TCP 控制共用同一端口号，见 `discovery.rs:13`）。握手完成后，`session_key`(32B) 作为后续所有控制/信封帧、字节通道、文件通道的对称密钥。

---

## 1. 帧封装（所有握手报文都包在这套帧里）

### 1.1 标准帧头 `FRAME_HEADER_SIZE = 24` 字节（`lib.rs:68`, `frame_build` `lib.rs:259`）

```
| 偏移 | 长度 | 字段            | 类型/编码        | 说明 |
|------|------|-----------------|------------------|------|
| 0    | 4    | magic_word      | u32 LE           | 0xBABE_FACE (lib.rs:67) |
| 4    | 4    | module          | u32 LE           | 见下方命令号表 |
| 8    | 8    | sequence        | u64 LE           | 帧序号，见 1.4 |
| 16   | 4    | flags           | u32 LE           | 见各步骤 |
| 20   | 4    | payload_len     | u32 LE           | 后续载荷字节数 |
| 24   | ...  | payload         | 原始字节         |      |
```

- 校验：`frame_decode` (`lib.rs:314`) 要求 `data.len() >= 24`，`magic == 0xBABE_FACE`，`payload_len <= 上限`。
- 上限：普通模块 `FRAME_MAX_PAYLOAD = 65536` (`lib.rs:70`)；仅 `ENVELOPE_MODULE=9` 允许放宽到 `FRAME_MAX_ENVELOPE = 16 MiB` (`lib.rs:75`)。

### 1.2 握手信封帧（module 9，认证专用容器）

信封帧 = 标准帧头（module=9）+ payload，payload 布局（`auth.rs:48` `seal_handshake_envelope`，`auth.rs:75` `open_envelope`）：

```
| 偏移(相对 payload 起点) | 长度 | 字段            | 编码 |
|------------------------|------|-----------------|------|
| 0                      | 16   | device_hash     | 16 字节，见 2.4 |
| 16                     | 4    | magic+version+mode 三元组（varint×3） | 见下 |
| 20+                    | ...  | body（变长）    | 见各步骤 |
```

三元组（注意是 **3 个 varint，紧跟 16 字节 device_hash 之后**，不是 4 个）：
- `magic = 1`（varint，`auth.rs:69` 校验值）
- `version = 101`（varint，`auth.rs:69` 校验值）
- `mode` = u8 语义见步骤：`0`=能力/公钥协商，`2`=加密信封（AES-GCM 包体），`3`=RSA 挑战，`4`=挑战回应。
- 紧接着 `mode` 之后还有一个 varint `len`，是 **body 的字节长度**（`open_envelope` `auth.rs:100` 中 `offset += values[3]` 跳过的就是它），再之后才是 body。

> 注意 `envelope_mode`（`auth.rs:57`）只解析到 `mode`（`values[2]`），用于「探针探测」；完整解析用 `open_envelope`（`auth.rs:75`）返回 `(mode, body)`。

信封帧的 module/sequence/flags 由 `frame_encode(9, sequence, 0, &envelope)` 写入（`auth.rs:54`）。

### 1.3 TLV 编码（`lib.rs:235`）

```
| tag    | len    | value |
| varint | varint | bytes |
```
- `varint_encode`（`lib.rs:203`）：小端 7-bit 分组，**最高位为 1 表示结束字节**，最大 `VARINT_MAX = (1<<28)-1`（`lib.rs:78`）。
- 嵌套 TLV 通过 `tlv_encode(outer_tag, tlv_encode(inner_tag, ...))` 实现（`auth.rs:191`）。

### 1.4 sequence 来源（`outbound/mod.rs:227`)

握手阶段所有信封帧的 sequence 由 `rand_seq()` = `OsRng.next_u64()` 随机生成（`outbound/mod.rs:227-229`）。控制通道后续帧的 sequence 是单调自增计数器（`outbound/session.rs:269` 起 `sequence=6`，每发一帧 +1）。

---

## 2. 密码学原语

### 2.1 RSA 身份密钥（2048 位，`crypto.rs`）

- 生成：`RsaPrivateKey::new(&mut OsRng, 2048)`（`crypto.rs:12-14`）。**密钥位数固定 RSA-2048**，加载时强制校验 `key.size() == 256`（即 256 字节 / 2048 位，`crypto.rs:24`、`crypto.rs:41`）。
- 持久化：PKCS#8 PEM，存于 `home()/Huawei/identity.pem`（`transmit.rs:178-180`），首次运行生成、后续复用（`transmit.rs:115` `load_or_create_identity`）。
- 公钥导出：SPKI DER（`to_public_key_der`，`crypto.rs:31-36`）。**公钥来自本机内置身份（本地生成），不是从对端获取；对端公钥在握手第 3 步通过协议交换获得**（`auth.rs:148` `send_public_key` / `auth.rs:154` `extract_peer_public` / `opening_payload.rs:177-179`）。

### 2.2 RSA 填充：OAEP（SHA-256 摘要，MGF1 可切换 SHA-1）

- `oaep_encrypt(public, plain, mgf_sha1: bool)`（`crypto.rs:47`）：
  - `mgf_sha1 == false` → `Oaep::new::<Sha256>()`：**摘要 SHA-256 + MGF1-SHA256**（默认）。
  - `mgf_sha1 == true` → `Oaep::new_with_mgf_hash::<Sha256, Sha1>()`：**摘要 SHA-256 + MGF1-SHA1**。
  - 即 **OAEP 的哈希函数永远是 SHA-256，仅 MGF1 的哈希在 SHA-256/SHA-1 之间切换**。
- 解密 `oaep_decrypt`（`crypto.rs:56`）对称对应。
- OAEP 密文长度固定 256 字节（RSA-2048）。

### 2.3 AES-GCM

| 函数 | key 长度 | nonce 长度 | tag | AAD | 用途 | 源 |
|------|----------|------------|-----|-----|------|-----|
| `aesgcm_encrypt/decrypt` | 32 B (AES-256) | **12 B** | 16 B | 无 | 控制通道帧（`seal_channel_frame`）、字节通道帧（`bytes_frame`） | `lib.rs:421`、`lib.rs:429`、`transfer.rs:102`、`transfer.rs:724` |
| `aesgcm_encrypt_n16/decrypt_n16` | 32 B (AES-256) | **16 B** | 16 B | 无 | 握手信封的加密体（`encrypted_envelope`）、RSA 挑战里的会话密钥封装 | `lib.rs:465`、`lib.rs:477`、`transfer.rs:42`、`auth.rs:214` |
| `aes128gcm_encrypt_aad/decrypt_aad` | 16 B (AES-128) | 12 B | 16 B | **有**（DFILE_AAD） | 数据通道（DFile）帧，仅当协商到 AES 套件时 | `lib.rs:440`、`lib.rs:451`、`transfer.rs:557` |

- 常量：`GCM_KEY=32`(`lib.rs:79`)、`GCM_NONCE=12`(`lib.rs:80`)、`GCM_TAG=16`(`lib.rs:81`)。
- `aesgcm_encrypt_n16` 用的是 **Aes256Gcm + Nonce<16>**（即 `AesGcm<Aes256, U16>`，`lib.rs:471`），nonce 取 16 字节中的全部。
- 密钥即 `session_key`（握手产出的 32 字节），或直接用对端发来的 `nonce||key` 48 字节块（`auth.rs:214`、`opening_payload.rs:202`）。

### 2.4 ChaCha20-Poly1305（RFC 8439，标准实现，`chacha.rs`）

- 手写实现（`chacha.rs:1` 注释「RFC 8439，12 字节 nonce」），**不是任何私有变体**：
  - ChaCha20 20 轮、64 字节块、计数器从 1 开始（`chacha.rs:44`、`chacha.rs:249` `chacha20_xor(key,nonce,1,...)`）。
  - Poly1305 key 由 `chacha20_block(key, nonce, 0)` 取前 32 字节（`chacha.rs:226-231`）。
  - AAD 处理：AAD 与密文各自按 16 字节零填充对齐后，追加上各自的 64-bit 小端长度（`poly_input`，`chacha.rs:233-242`）。
  - tag 16 字节，附在密文尾部（`chacha.rs:253-254`）。
- 仅用于 **数据通道（DFile）** 当协商到 ChaCha 套件时（`aead_seal`/`aead_open`，`transfer.rs:554-571`）。
- key 由 `session_key` 前 32 字节直接取用（`chacha_key`，`transfer.rs:545-552`）；**key 长度固定 32 字节**，nonce 12 字节，AAD = `DFILE_AAD`（`transfer.rs:363`）。

### 2.5 HMAC-SHA256 / SHA256（仅用于身份派生，不参与帧加密）

- `sha256(data)`（`lib.rs:342`）。
- `hmac_sha256(key, data)`（`lib.rs:349`）。
- `derive_key_fingerprint(public_der)` = `HMAC-SHA256(public_der[0..16], public_der)`（`lib.rs:355-360`）→ 32 字节。
  - `identity_v2` = 该指纹前 6 字节大写十六进制（`lib.rs:362-365`）。
  - `device_hash` = 该指纹前 8 字节大写十六进制（`lib.rs:379-382`），即握手信封里的 16 字符 device_hash 字符串；`opening_payload.rs:155`、`.into_bytes()` 转成 16 字节 ASCII。
  - `derive_node_identifier` = `HMAC-SHA256(public_der[..16], META_PREFIX || public_der)` 小写十六进制（`lib.rs:367-377`），`META_PREFIX` 是 `meta_prefix()`（`lib.rs:83-98`，一段异或掩码常量）。
- **这些只用于身份字符串，不进入加密运算**；加密密钥全部来自 `session_key`。

### 2.6 随机数来源

- Rust：`rand::rngs::OsRng`（系统 CSPRNG），用于 RSA key/nonce/session_key/metadata（`crypto.rs:3`、`auth.rs:185-186`、`transfer.rs:40`、`transfer.rs:105` 等）。
- Android 等价：**`java.security.SecureRandom`**（见第 5 节）。

---

## 3. 握手流程（逐字节报文序列）

> 以下 module 值：`8`=opening、`9`=信封、`21`=控制通道帧、`1`=华为账号链路（被忽略，见 3.1）。

### 3.0 建链

- Sender `Channel::connect(peer_ip, 5684, 8s)`（`outbound/session.rs:46`）。
- 对端身份由 Sender 本地生成：`load_or_create_identity` → `public_der` → `device_hash`(16B) / `identity_v2` / `node_id`（`outbound/session.rs:36-41`）。

### 3.1 步骤 1 — Opening（明文 JSON，module 8）

- **Sender → Receiver**：`auth::opening_payload(identity)`（`auth.rs:17-27`）：
  - body = JSON `{CODE:4, DEVICE_ID, PEER_NETWORK_ID, PKG_NAME:"ohos.InterConnection.iShare", SRC_BUS_NAME:"IShareEcologyAuthSession", DST_BUS_NAME:"IShareEcologyAuthSession", REQ_ID:"0", API_VERSION:2, MTU_SIZE:39968, ROUTE_TYPE:2}`，末尾追加 **1 字节 0 终止符**（`auth.rs:25`）。
  - 帧：`frame_encode(8, 1, 0, payload)`（`auth.rs:26`）。即 module=8, sequence=1, flags=0。
- **Receiver → Sender**：`build_opening_ack`（`opening_payload.rs:32-43`）：
  - body = 同上 JSON（注意 `ROUTE_TYPE:2`，`SRC_BUS_NAME:"ohos.InterConnection.iShare"`，`DST_BUS_NAME:"IShareEcologyAuthSession"`，`DEVICE_ID = identity.to_lowercase()`），末尾 1 字节 0。
  - 帧：`frame_encode(8, 0, 1, payload)` → **flags = 1**（`opening_payload.rs:42`）。
- Sender 校验：读帧后要求 `module==8 && flags==1`，否则拒绝（`outbound/session.rs:55-59`）。
- 若收到 `CODE != 4` 或 `module==1` 或含 `AUTH_START_STATE` 的「华为账号链路」帧，Receiver 直接丢弃，最多 `MAX_AUTH_ROUNDS=6` 轮（`opening_payload.rs:113-136`）。**移植时务必同样丢弃这些帧，否则会卡死。**

### 3.2 步骤 2 — 能力协商（信封，mode=0）

- **Sender → Receiver**：`auth::local_capability_frame` 或 `capabilities_phone`（`auth.rs:29-46`），封装：
  - TLV 结构：`tlv(2, [ tlv(1,b"1"), tlv(2,b"1.0.0"), tlv(3,os_version), tlv(4,machine) ])`（鸿蒙）/ `[ tlv(1,b""), tlv(5,b"TRUE"), tlv(6,b"ENABLE") ]`（手机形态）。
  - 信封：`seal_handshake_envelope(device_hash, payload, 2)`（`auth.rs:48-55`）→ module=9, sequence=随机, flags=0, payload 内 `device_hash(16) | varint(1) varint(101) varint(0) varint(payload_len) | payload`。**mode=0**。
- **Receiver → Sender**：`opening_payload.rs:168` 回 `seal_handshake_envelope(device_hash, tlv(3, tlv(1, b"")) , 0)` → **mode=0**，表示「已回应能力」。
- Sender 读取对端能力并解析 `mode`、TLV tag 3（capability 版本/secure_version），决定后续 `mgf_sha1`（`auth.rs:122-146` `secure_version`：取 TLV(3)→TLV(1) 文本解析为数字，`& 1` → 1 表示启用 mgf_sha1）。注意：若收到 `mode=2` 探针则跳过回显（`outbound/session.rs:109-147`）。

### 3.3 步骤 3 — 公钥交换（信封，mode=0）

- **Sender → Receiver**：`auth::send_public_key(device_hash, public_der)`（`auth.rs:148-152`）：
  - 信封体 = `tlv(6, tlv(1, public_der))`，`seal_handshake_envelope(..., 3)` → **mode=0, sequence=随机**。
- **Receiver → Sender**：`opening_payload.rs:181-185` 回 `seal_handshake_envelope(device_hash, tlv(6, tlv(2, public_der)), 0)` → 对端用 `require_tlv(require_tlv(body,6),2)` 取 DER（`auth.rs:159`）。
- 双方此时各持有对端 RSA-2048 公钥 DER，用 `public_from_der` 解析并再次校验 size==256（`crypto.rs:38-45`）。

### 3.4 步骤 4 — RSA 挑战 / 会话密钥建立（信封，mode=3 → 4）

**Sender 侧（`auth.rs:163-221` `auth_challenge_payload`）：**
1. 取对端公钥 `peer_key`；决定 `mgf_sha1`（Apple 客户端强制 false；否则取 `secure_version` 的 `&1`，解析失败默认 true，`auth.rs:174-181`）。
2. 生成 `nonce[16]` 与 `key[32]`（OsRng，`auth.rs:183-186`）——这 32 字节 `key` 就是最终 `session_key` 的雏形。
3. 构造 `inner = nonce(16) || key(32)`（48 字节），`plaintext = tlv(6, tlv(3, inner))`（`auth.rs:188-191`）。
4. `ciphertext = oaep_encrypt(peer_key, plaintext, mgf_sha1)`（256 B，`auth.rs:192`）。
5. 信封体 = `metadata(8 随机字节, tlv(1, meta)) || varint(ciphertext.len) || ciphertext`，前缀 `0x81,0xE5,0x83`，`frame_encode(9, 4, 0, envelope)` → **mode=3, sequence=4**（`auth.rs:194-205`）。`0x83` 是 mode 的 ASCII 编码位置（`auth.rs:200`），等价于 `varint(1) varint(101) varint(3)`。
6. 发送后读回应帧，要求 `open_envelope` 返回 `mode==3`（`auth.rs:208-211`）。
7. `decrypted = oaep_decrypt(private, body, mgf_sha1)`（`auth.rs:212`）。
8. `encrypted_session = require_tlv(require_tlv(decrypted,6),4)`（`auth.rs:213`）。
9. `session = aesgcm_decrypt_n16(key, nonce, encrypted_session)`（`auth.rs:214`）——即用步骤 2 的 `(nonce,key)` 解密 Receiver 回传的会话密钥；校验长度 == 32（`auth.rs:215-217`）。**解密得到的 32 字节即为最终 `session_key`**（`auth.rs:218-220`）。

**Receiver 侧（`opening_payload.rs:188-220`）：**
1. 读挑战帧，`open_envelope` 要求 `mode==3`（`opening_payload.rs:190-193`）。
2. `decrypted = oaep_decrypt(private, ciphertext, false)`（Receiver 侧 **mgf_sha1 固定 false**，`opening_payload.rs:194`）。
3. `auth_challenge_payload = require_tlv(require_tlv(decrypted,6),3)`；校验长度 == 48（`opening_payload.rs:195-198`）；拆出 `nonce = decrypted[0..16]`，`key = decrypted[16..48]`。
4. 生成最终 `session_key[32]`（OsRng，`opening_payload.rs:200-201`）。
5. `encrypted = aesgcm_encrypt_n16(key, nonce, session_key)`（用对端发来的 nonce+key 封装，`opening_payload.rs:202`）。
6. `wrapped = oaep_encrypt(peer_key, tlv(6, tlv(4, encrypted)), false)`（`opening_payload.rs:203-208`）。
7. 回信封装：`metadata(8 随机, tlv(1,meta)) || varint(wrapped.len) || wrapped`，前缀 `0x81,0xE5,0x83`，`frame_encode(9, 0, 0, envelope)` → **mode=3**（`opening_payload.rs:210-220`）。

> ⚠️ **mgf_sha1 不一致风险**：Sender 用 `secure_version` 推断（默认 true），Receiver 固定用 false。移植时若实现双向角色，需保持与 Rust 一致：Receiver 永远 false；Sender 向鸿蒙对端默认 true（Apple 强制 false）。这套不对称是华为原有协议的行为，必须复刻。

### 3.5 握手完成

- Sender 得到 `Handshake { session_key: [u8;32], mgf_sha1: bool }`（`auth.rs:117-120`）。
- Receiver 得到 `Session { session_key, device_hash, ... }`（`opening_payload.rs:242`）。
- 此后每条控制/信封帧都用 `session_key` 加解密（见第 4 节）。

---

## 4. 握手后控制/信封帧加解密

### 4.1 信封加密帧（module 9，`encrypted_envelope` `transfer.rs:38-52`）

- 生成随机 `nonce[16]`；`ciphertext = aesgcm_encrypt_n16(key, nonce, body)`。
- 信封体 = `device_hash(16) | varint(1) varint(101) varint(2) | varint(metadata_len) metadata(tlv(1,nonce)) | varint(ciphertext_len) ciphertext`。**mode=2**。
- 帧：`frame_encode(9, sequence, 0, envelope)`。
- 解密 `open_channel_frame`（`transfer.rs:54-80`）：解析出 metadata 里的 nonce；若 nonce 长度==16 用 `aesgcm_decrypt_n16`，==12 用 `aesgcm_decrypt`；要求 nonce 长度 ∈ {12,16}。
- 用途：发送 offer（`outbound/session.rs:239` `seal_encrypted_frame(device_hash, key, offer, 5)`），以及 Receiver 收 offer（`opening_payload.rs:225`）。

### 4.2 控制通道帧（module 21，`seal_channel_frame`/`parse_channel_frame` `transfer.rs:102-170`）

- 加密体布局（内层，不含标准帧头）：
  ```
  | header: u32 type | u32 sub | u64 seq | u32 flags | u32 len | nonce(12) | aesgcm_sealed(plain+16) |
  ```
  header 5 个字段各 4/8/4/4/4 字节共 24 字节，再接 12 字节 nonce 与密文（`transfer.rs:113-118`）。
- 外层 `frame_encode(21, sequence, 0, inner)`（`transfer.rs:120`）：magic=0xBABE_FACE, module=21。
- 解密 `parse_channel_frame`（`transfer.rs:143-170`）：要求 `outer_module==21`，`outer_len == data.len()-24`，`inner_header.4 == data.len()-48`；取 `nonce=inner[24..36]`，`aesgcm_decrypt(key, nonce, sealed)`。
- flags 语义（握手/控制阶段）：`0x94` = 字节通道请求（`handshake.rs:16` `BYTES_FLAG_REQUEST`）；`0x90` = Apple 字节通道（`handshake.rs:50`）；回应 `0x95`/`0x91` 表示接受（`outbound/session.rs:484`）。
- 常见 header 常量（`handshake.rs:11-13`）：
  - `CHANNEL_BYTES = 0xFFFF_0004`（字节通道请求/应答）
  - `PEER_META_FRAME = 0xFFFF_0002`
  - `PEER_META_ACK_FRAME = 0xFFFF_0008`，其 ACK 体 = `"META_DEVICE_INFO_ACK\0"`（`handshake.rs:14`）。

### 4.3 字节通道帧（`bytes_frame` `transfer.rs:724-741`）

- 裸帧（**无 24 字节标准帧头**，前缀 16 字节）：
  ```
  | magic u32 LE = 0xBABE_FACE | seq u32 LE | 0 u32 | len u32 LE | nonce(12) | aesgcm_sealed(plain+16) |
  ```
  （`BYTES_MAGIC=0xBABE_FACE`，`transfer.rs:722`）。
- nonce：`nonce[0..4] = seq.to_le_bytes()`，`nonce[4..12]` 随机（`transfer.rs:725-727`）。
- 解密 `try_read_bytes_frame`（`transfer.rs:743-792`）：要求 `magic==0xBABE_FACE` 且 `13 <= size <= 8 MiB`。

---

## 5. Kotlin / Android 等价实现建议

### 5.1 随机数
- ✅ `java.security.SecureRandom`（等价 OsRng）。`val rng = SecureRandom()`，用 `rng.nextBytes(buf)` 生成 nonce/key/metadata。

### 5.2 RSA-2048 + OAEP（`javax.crypto` JCE 原生支持）
- 密钥：`KeyPairGenerator.getInstance("RSA")` → `initialize(2048)`。公钥导出 `SubjectPublicKeyInfo`（SPKI）DER 等价 `public_der`：`SubjectPublicKeyInfo.getInstance(publicKey.encoded).encoded` 或 `publicKey.encoded`（X.509 SubjectPublicKeyInfo 即 SPKI DER，与 Rust `to_public_key_der` 同构）。私钥 PKCS#8：`privateKey.encoded`。
- OAEP 加密/解密原生支持：
  ```kotlin
  // 默认（mgf_sha1=false）：SHA-256 摘要 + MGF1-SHA256
  val spec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.EMPTY)
  // Apple/旧鸿蒙（mgf_sha1=true）：SHA-256 摘要 + MGF1-SHA1
  val specOld = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.EMPTY)
  Cipher.getInstance("RSA/ECB/OAEPWithSHA256AndMGF1Padding") // 注意：此别名固定 MGF1=SHA256
  // 要切 MGF1=SHA1 必须用上面带 OAEPParameterSpec 的 "RSA/ECB/OAEPPadding" + init(.., spec)
  ```
  ⚠️ `"RSA/ECB/OAEPWithSHA256AndMGF1Padding"` **无法**表达 MGF1-SHA1；必须用 `Cipher.getInstance("RSA/ECB/OAEPPadding")` 并显式传 `OAEPParameterSpec`（摘要 SHA-256、MGF1 取 SHA1 或 SHA256）。这与 Rust 的「摘要永远 SHA-256、仅 MGF1 切换」完全对应。
- 密文长度恒 256 字节。注意 OAEP 输入上限 = 2048/8 − 2·32 − 2 = 190 字节；挑战明文 48 字节 + TLV 开销 < 190，安全。

### 5.3 AES-GCM
- `Cipher.getInstance("AES/GCM/NoPadding")`，`GCMParameterSpec(128, nonce)`（tag 128 位 = 16 字节）。
- AES-256（key 32B）/nonce 12B：直接支持（`aesgcm_encrypt`）。
- **AES-256/nonce 16B**（`aesgcm_encrypt_n16`）：JCE 同样支持 **12 或 16 字节 nonce** 的 GCM；传 `GCMParameterSpec(128, nonce16)` 即可，无需改算法（AES-256-GCM 的 nonce 在 JCE 允许 12/16 等长度）。
- AES-128（key 16B）/nonce 12B + AAD（`aes128gcm_*_aad`）：`GCMParameterSpec(128, nonce)` + `cipher.init(.., ivSpec, AEADParameterSpec(aad))`（API 26+ 用 `GCMParameterSpec` 的 AAD 通过 `cipher.updateAAD(aad)` 注入）。
- ⚠️ 默认 JCE GCM tag 为 128 位（16B），与 Rust 一致；**不要**用 96/120/默认以外位数。

### 5.4 ChaCha20-Poly1305（RFC 8439）
- JCE **原生不支持** ChaCha20-Poly1305。两种方案：
  1. **BouncyCastle**：`org.bouncycastle.jcajce.provider.BouncyCastleProvider`，`Cipher.getInstance("ChaCha20-Poly1305", BC)`。其 RFC-8439 模式默认 nonce=12B、tag=16B、AAD 处理与 `chacha.rs` 完全兼容（Poly1305 key 取 block0 前 32B、AAD/密文 16B 零填充对齐 + 长度）。✅ 推荐，最贴近手写实现。
  2. **手写**：直接移植 `chacha.rs` 的 `chacha20_block`/`poly1305`/`aead_encrypt` 到 Kotlin（纯算法，无 JNI）。当前 Rust 实现即「RFC 8439 标准变体」，与 BouncyCastle 互通。
- 注意：数据通道只有在协商到 `CIPHER_CHACHA=0x2` 时才用它（见 5.5），且 AAD 恒为 `DFILE_AAD`（64 字节 ASCII `"A"*64`，`transfer.rs:363`）。

### 5.5 套件协商（仅数据通道相关，握手层需理解以正确选 AEAD）
- 能力位：`CIPHER_CLEAR=0x1`、`CIPHER_CHACHA=0x2`、`CIPHER_AES=0x4`（`transfer.rs:365-367`）；本实现 `OUR_CIPHER_CAPS = 0x7`。
- 选择逻辑 `choose_cipher`（`transfer.rs:409-431`）：有 AES 优先 AES-128-GCM，否则 ChaCha，都没有则明文。Receiver 端默认给 `cipher()==None` 时鸿蒙→AES、Apple→ChaCha（`transfer.rs:412-416`）。
- **对握手层的影响**：仅决定后续数据通道（DFile/预览/字节）用哪种 AEAD；握手本身的控制/信封帧永远用 AES-256-GCM（12B 或 16B nonce，依函数而定），与套件协商无关。

### 5.6 HMAC-SHA256 / SHA256
- `Mac.getInstance("HmacSHA256")` / `MessageDigest.getInstance("SHA-256")`。`derive_key_fingerprint` 用 `HmacSHA256(der[0..16], der)`。

### 5.7 帧/TVarint/JSON 实现要点
- 标准帧头自己按 1.1 表拼 `ByteBuffer`（LITTLE_ENDIAN）。magic `0xBABE_FACE` 在 LE 字节流中为 `CE FA BE BA`（`lib.rs:523` 测试）。
- varint：手写 7-bit 小端、终止位 0x80（见 `lib.rs:203-215`）。
- JSON 报文：Kotlin 用 `org.json` 或 Moshi 拼装，注意 **末尾补 1 字节 0x00**，且 `ROUTE_TYPE`、`CODE` 等字段名/值为整数（不加引号）。

---

## 6. 时钟 / 时间戳校验

- 握手与加密层 **本身不做时钟偏移/时间戳校验**（认证关键在 RSA 挑战 + AES-GCM tag）。
- 时钟相关函数集中在 `clock.rs`，用途是 **显示**而不是鉴权：
  - `now_secs`/`now_millis`（`clock.rs:10-23`）= Unix 时间。
  - 给华为设备解析的时间字符串用 `format_at_wire`（`YYYY:MM:DD HH:MM:SS`，冒号分隔年/月/日，`clock.rs:63-65`），**不要改成短横线**，否则对端可能不认（`clock.rs:59-62`）。
  - 时区偏移：Windows 用 `GetLocalTime - GetSystemTime`（`clock.rs:26-52`），非 Windows 回退 0（UTC）。Android 移植用 `java.time.ZoneId` 取偏移即可。
- 数据通道 DFile 的时间戳：`dfile_stamp()`（`transfer.rs:491-501`）——鸿蒙用 Unix 微秒、Apple 用 boot 微秒，仅作排序/调试，无安全校验；移植数据通道时再处理，握手层无需实现。

---

## 7. 常量速查表

| 常量 | 值 | 源 |
|------|----|----|
| 帧头长度 FRAME_HEADER_SIZE | 24 | lib.rs:68 |
| 帧 magic | 0xBABE_FACE (LE: CE FA BE BA) | lib.rs:67 |
| 标准载荷上限 FRAME_MAX_PAYLOAD | 65536 | lib.rs:70 |
| 信封载荷上限 FRAME_MAX_ENVELOPE | 16 MiB | lib.rs:75 |
| 信封 module | 9 | lib.rs:77 |
| VARINT 上限 | (1<<28)-1 | lib.rs:78 |
| GCM key / nonce / tag | 32 / 12 / 16 | lib.rs:79-81 |
| RSA 位数 | 2048 (size==256) | crypto.rs:13,24,41 |
| OAEP 摘要 / MGF1 | SHA-256 / SHA-256 或 SHA-1 | crypto.rs:47-63 |
| OAEP 密文长度 | 256 | crypto.rs |
| 信封三元组 | magic=1, version=101, mode | auth.rs:69 |
| device_hash 长度 | 16 字节 (前 8 字节指纹大写十六进制) | lib.rs:379-382 |
| opening module / flags | 8 / 接收 flags==1 | auth.rs:26, opening_payload.rs:42, outbound/session.rs:57 |
| opening JSON 字段 | CODE:4, MTU_SIZE:39968, ROUTE_TYPE:2, API_VERSION:2 | auth.rs:19 |
| 控制通道 module | 21 | transfer.rs:120 |
| 字节通道 magic | 0xBABE_FACE (裸 16B 头) | transfer.rs:722 |
| CHANNEL_BYTES | 0xFFFF_0004 | handshake.rs:11 |
| PEER_META_FRAME / ACK | 0xFFFF_0002 / 0xFFFF_0008 | handshake.rs:12 |
| 字节通道请求 flags | 0x94 (鸿蒙) / 0x90 (Apple) | handshake.rs:16,50 |
| 字节通道回应 flags | 0x95 / 0x91 | outbound/session.rs:484 |
| 会话密钥长度 | 32 | auth.rs:215 |
| 挑战 inner 长度 | 48 (nonce16+key32) | opening_payload.rs:196 |
| DFILE_AAD | 64 字节 'A' 重复 | transfer.rs:363 |
| CIPHER_CLEAR/CHACHA/AES | 0x1 / 0x2 / 0x4 | transfer.rs:365-367 |
| OUR_CIPHER_CAPS | 0x7 | transfer.rs:369 |
| TCP/UDP 端口 | 5684 | discovery.rs:13 |
| 连接超时 | 8s (connect) / 10s (opening ack) / 12s (能力) / 15s (公钥) / 10s (挑战) | outbound/session.rs:46-164 |
| MAX_AUTH_ROUNDS | 6 | opening_payload.rs:19 |
| 公钥 tlv | tlv(6, tlv(1, der)) 发送 / tlv(6, tlv(2, der)) 回应 | auth.rs:148-160 |
| 挑战 tlv | tlv(6, tlv(3, nonce||key)) 发送 / tlv(6, tlv(4, enc)) 回应 | auth.rs:191, opening_payload.rs:205 |

---

## 8. 移植检查清单（照着写 Kotlin 的最小步骤）

1. 实现 `SecureRandom` 包装；`RSA-2048` KeyPair 生成 + PKCS#8/SPKI 持久化。
2. 实现 varint 编解码（小端 7-bit，终止位 0x80）。
3. 实现 24 字节帧头读写（LITTLE_ENDIAN，magic 0xBABE_FACE）。
4. 实现 TLV 编解码 + 嵌套。
5. 实现信封帧（module 9）：`device_hash(16) + varint(1)+varint(101)+varint(mode) + varint(bodyLen)+body`，配合 frame 头。
6. 实现 RSA OAEP：`RSA/ECB/OAEPPadding` + 显式 `OAEPParameterSpec(SHA-256, MGF1, SHA256|SHA1)`（**关键：用参数对象控制 MGF1**）。
7. 实现 AES-256-GCM：JCE `AES/GCM/NoPadding`，nonce 12B（控制帧）与 16B（信封体）两种，tag 16B。
8. 实现 ChaCha20-Poly1305：引入 BouncyCastle（`ChaCha20-Poly1305`，RFC 8439），仅数据通道用，AAD=DFILE_AAD。
9. 严格按 3.1–3.4 的步骤顺序收发，注意 Receiver 固定 `mgf_sha1=false`、Sender 按 `secure_version` 推断（默认 true）；注意丢弃 `CODE!=4`/module 1 的华为账号链路帧。
10. 握手完成后 `session_key`(32B) 用于 module 9 信封帧与 module 21 控制帧、字节通道帧的全部 AES-GCM 加解密。
