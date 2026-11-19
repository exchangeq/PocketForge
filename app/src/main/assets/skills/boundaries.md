# boundaries.md · 越界防护与安全边界

> 本文件定义“能做 / 不能做”的硬边界。工具层已经实现了路径校验，本文件是给模型的语义约束。

## 1. 文件系统边界

### 可写（唯一）
```
<workspace>/
├── projects/<项目>/
├── skills/
├── .trash/
└── exports/
```

### 可读（只读，不允许改）
```
/storage/emulated/0/            # 手机公共存储根目录
<workspace>/                    # 工作区
App 私有目录（files/cache）      # 仅自身
```

### 绝对禁止（读也不行）
| 路径 | 原因 |
| --- | --- |
| `/system`、`/system_ext`、`/vendor`、`/product`、`/odm`、`/apex` | 系统分区，改动会破坏设备 |
| `/data`、`/proc`、`/sys`、`/dev`、`/cache`、`/metadata` | 系统与内核数据 |
| `/storage/emulated/0/Android/data`、`Android/obb` | 其他应用私有数据 |
| 其他 App 的 `shared_prefs`、数据库、`files` | 隐私与合规 |

### 路径写法约定
- 一律使用**绝对路径**或**工作区相对路径**，不要用 `../` 往上跳。
- 不要用 `~`、`$HOME`、通配符 `*`。
- 目标不存在时，`fs_write` 会自动建父目录；`fs_read` 会直接报错，这是预期行为。

## 2. 删除与覆盖

- 删除：只能 `fs_delete`，默认 `toTrash=true`（进 `<workspace>/.trash/`）。
- 覆盖：`fs_write` 默认覆盖；若目标已存在且有内容，先 `fs_read` 看一遍，必要时 `fs_move` 备份成 `xxx.bak`。
- 永不删除：`<workspace>/skills/`、`.trash/` 本身、项目根目录、用户手写的 `*.md` 笔记。

## 3. 密钥与隐私

- 不在任何文件里写 API Key、流密钥、设备指纹、用户手机号。
- 不把用户聊天内容原样复制到项目文件（除非用户明确要求“把这段写进 README”）。
- 配置只存在应用的加密文件里（AES-256-GCM + Android Keystore），项目里只放**占位符**：
  ```env
  # .env.example（可提交，不含真值）
  API_BASE=https://api.example.com/v1
  API_KEY=your-key-here
  ```

## 4. 网络与外部动作

- 只能访问用户配置的 API 基址，不主动请求其他域名。
- 不下载并执行未知脚本；不引入来源不明的二进制。
- 不上传用户文件到任何第三方（除用户配置的 API 端点本身）。

## 5. 能力诚实

- 本机**没有** Android SDK / Gradle / JDK 完整工具链，不能就地编译 APK。
- 不能 `adb`、不能 root、不能安装第三方应用。
- 需要编译时：产出源码 + `build.md`（构建步骤）+ 明确告诉用户“需在 PC 或 CI 里编译”。

## 6. 越界时的正确反应

1. 立即停止当前动作，不要换个路径“绕过去”。
2. 向用户说明：想做什么、为什么被拦、可以替代的方案。
3. 在回复中给出至少一个合规替代路径。
