# runtime-config.md · 加密配置与数据流协议

> 本文件说明 PocketForge 自身如何存密钥、如何解密上游数据流。用户问「配置在哪 / 为什么安全 / 怎么接自建网关」时读本文件回答。

## 1. 配置存在哪

| 项 | 位置 | 保护方式 |
| --- | --- | --- |
| API Base / Key / 模型 / 工作区 | `files/config.enc`（应用私有目录） | AES-256-GCM，密钥在 Android Keystore，不可导出 |
| 会话轨迹 | `files/sessions/session.enc` | 同上 |
| 项目文件 | `<workspace>/projects/…` | 不加密（用户要能直接看/改） |

**关键点**：`config.enc` 是密文，即使拿到手机文件系统也读不出 Key；卸载应用后 Keystore 密钥销毁，密文无法再解。

密文格式：

```
PFK1.<base64(iv,12B)>.<base64(ciphertext+tag)>
```

- 算法：`AES/GCM/NoPadding`，256 位，128 位认证标签
- IV：每次加密随机生成，不复用
- 解密失败时：自动把损坏文件备份为 `config.corrupt.bak` 并降级为默认配置，不崩溃

## 2. 数据流解密（可选）

上游若是普通 OpenAI 兼容服务，SSE 返回明文 JSON 帧，直接解析。
若用户自建网关会加密输出，启用「流密钥」后按下面协议解：

```
PF1.<base64(salt,16B)>.<base64(iv,12B)>.<base64(ciphertext+tag)>
```

派生：`PBKDF2WithHmacSHA256(passphrase, salt, 12000 次) → 256bit AES key`

处理顺序：

```
SSE line → 去 "data:" 前缀 → 判断是否 PF1 帧
        → 是：用流密钥 AES-GCM 解密 → JSON
        → 否：直接当 JSON
        → 解析 choices[0].delta.content / tool_calls
        → 折叠成 UI 上的操作步骤
```

移动端对应实现：`core/StreamCipher.kt`（`sealFrame` / `openFrame`）。
网关侧用同样参数即可互通；密钥只存在手机本地，不随请求发送。

## 3. 传输安全

- 默认强制 HTTPS（`network_security_config` 只放行 `localhost` / `10.0.2.2` 明文，方便接本机模型）。
- `Authorization: Bearer <key>` 仅存内存与密文文件；日志不打印。
- 不做证书固定（避免用户自建网关无法接入），但拒绝明文公网地址。

## 4. 技能闸门（skillsGate）

- 开启（默认）：未读 `SKILLS.md` + 至少一份类型技能时，`fs_write` / `fs_mkdir` / `project_new` / `fs_move` / `fs_delete` 返回 `gate=skills_not_loaded`。
- 关闭：允许直接写文件，但仍受路径沙箱限制。
- 闸门状态是**会话级**的，清空会话后重置。

## 5. 工具接口一览

| 工具 | 作用 | 关键参数 |
| --- | --- | --- |
| `skills_index` | 读总索引 | — |
| `skill_read` | 读具体技能 | `name` |
| `fs_list` | 列目录 | `path`, `depth` |
| `fs_read` | 读文件 | `path`, `maxBytes` |
| `fs_write` | 写文件 | `path`, `content`, `append` |
| `fs_mkdir` | 建目录 | `path` |
| `fs_move` | 移动/改名 | `from`, `to` |
| `fs_delete` | 删除（默认回收站） | `path`, `toTrash` |
| `fs_search` | 全文搜索 | `query`, `path`, `maxHits` |
| `project_new` | 建项目骨架 | `name`, `type`, `engine` |
| `plan_set` | 更新 UI 计划 | `items[]` |
| `config_info` | 查看脱敏配置 | — |

## 6. 接入自建网关的最小示例（服务端）

```python
# 伪代码：把 OpenAI 的 SSE 帧加密后再下发
import base64, json, os
from Crypto.Cipher import AES
from Crypto.Protocol.KDF import PBKDF2

def seal(plain: str, passphrase: str) -> str:
    salt, iv = os.urandom(16), os.urandom(12)
    key = PBKDF2(passphrase, salt, dkLen=32, count=12000, hmac_hash_module=SHA256)
    ct = AES.new(key, AES.MODE_GCM, nonce=iv).encrypt_and_digest(plain.encode())[0]
    b = lambda x: base64.b64encode(x).decode()
    return f"PF1.{b(salt)}.{b(iv)}.{b(ct)}"
```

服务端只需保证：同一 passphrase、salt/iv 随机、帧前缀 `PF1.`。

## 7. 常见问题

**Q：为什么不用 EncryptedSharedPreferences？**
A：直接调 Keystore + GCM，少一层依赖，且能对任意字节（含会话）加密，格式自描述便于跨版本兼容。

**Q：换手机后配置能带走吗？**
不能。密钥绑定设备 Keystore，这是设计目标（防止配置文件被拷走复用）。

**Q：密钥会发给模型吗？**
不会进入 prompt，也不会写进任何项目文件；只在请求头里出现。
