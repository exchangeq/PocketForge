# PocketForge

> 手机上的 AI 项目工坊：读手机目录、按技能规范建项目、把每一步操作折叠给你看。
> 配置与数据流全加密，AI 想写文件必须先读完「技能说明」，防止越界。

[![build-apk](https://github.com/exchangeq/PocketForge/actions/workflows/build-apk.yml/badge.svg)](https://github.com/exchangeq/PocketForge/actions/workflows/build-apk.yml)

---

## 它解决什么

在手机上跟 AI 说「做个像素风小游戏」「开一本 30 章的小说」，它会：

1. 先读 `SKILLS.md` → 类型技能 → `boundaries.md`（写文件前的硬闸门）
2. 在工作区建项目骨架：`README.md` / `PROJECT.md` / `AGENTS.md` / `plan/todo.md` + 类型专属目录
3. 一步一步写真实文件，每一步折叠成一张卡片，可展开看参数与输出
4. 写完自检，回复「结论 / 改动清单 / 下一步」

## 核心能力

| 能力 | 实现 |
| --- | --- |
| 读写手机目录 | `MANAGE_EXTERNAL_STORAGE` + 路径沙箱（可写仅工作区，可读公共存储根） |
| AI 建目录/写文件 | Function Calling 工具层：`fs_mkdir` / `fs_write` / `fs_move` / `fs_delete` / `fs_search` |
| 项目模板 | `project_new` 按 `game` / `writing` / `app` 生成结构与项目卡 |
| 配置加密 | AES-256-GCM，密钥存 Android Keystore（不可导出），密文 `PFK1.…` |
| 数据流解密 | SSE 帧支持 `PF1.<salt>.<iv>.<ct>`，PBKDF2 派生密钥，`StreamCipher.openFrame` |
| 操作折叠 | 每条工具调用 = 一张可展开的步骤卡（执行中 / 完成 / 失败） |
| 防越界 | 技能闸门 `skillsGate`：没读完技能，写操作直接返回 `gate=skills_not_loaded` |
| 会话加密 | 聊天与工具轨迹加密落盘（`session.enc`） |

## 界面

```
┌────────────────────────────────────┐
│ PocketForge      项目：雨夜小镇  🔒 │
├────────────────────────────────────┤
│ ① 读取技能总索引        完成  展开 ▸│
│ ② 创建项目骨架          完成  展开 ▸│
│ ③ 写文件 design/gdd.md  执行中…      │
│                                    │
│ 结论：项目骨架已就绪，先定核心循环  │
│ 改动：projects/rain-town/…          │
│ 下一步：1) 定玩法 2) 出 P1 可动版   │
├────────────────────────────────────┤
│ 描述目标，AI 先读技能再动手…    ➤   │
└────────────────────────────────────┘
│        对话          项目      设置 │
└────────────────────────────────────┘
```

## 安装

1. 打开 **Actions → build-apk → 最新一次成功构建**
2. 下载构件 `PocketForge-apk`，解压得到：
   - `PocketForge-debug.apk`（包名 `com.pocketforge.ai.debug`，可与正式版共存）
   - `PocketForge-release.apk`（包名 `com.pocketforge.ai`）
3. 传到手机安装（需允许「安装未知来源应用」）
4. 首次启动点「去授权」，授予 **所有文件访问权限**（Android 11+ 需手动在系统设置里开）

## 配置

| 字段 | 说明 |
| --- | --- |
| Base URL | 到 `/v1` 为止，如 `https://api.openai.com/v1`；程序自动补 `/chat/completions` |
| API Key | 只进内存与密文文件，不写日志、不进 prompt |
| 模型 | 任意 OpenAI 兼容模型名 |
| 工作区 | 留空 = `/storage/emulated/0/PocketForge` |
| 流密钥 | 上游输出 `PF1` 加密帧时填写；明文上游留空 |
| 技能闸门 | 默认开启；关闭后仍受路径沙箱限制 |

支持本机模型：`http://localhost:11434/v1`（Ollama）、`http://10.0.2.2:1234/v1`（模拟器访问宿主机 LM Studio）。

## 工具接口（给模型的）

```
skills_index | skill_read | fs_list | fs_read | fs_write | fs_mkdir
fs_move | fs_delete | fs_search | project_new | plan_set | config_info
```

完整说明见 `app/src/main/assets/skills/runtime-config.md`。

## 安全模型

- **写入边界**：只有 `<workspace>` 内可写；`/system`、`/data`、`Android/data` 等直接拒绝。
- **加密边界**：Keystore 密钥绑定设备，换机/卸载即失效；配置被拷走也无法解密。
- **删除边界**：默认进 `.trash`，不直接销毁。
- **技能边界**：闸门未开时，写操作会被工具层拒绝，模型无法用「换个说法」绕过。

## 本地构建

```bash
# 需要 JDK 17 + Android SDK 34
gradle assembleDebug          # 产物：app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease        # release 复用 debug 签名，便于自安装
```

仓库不含 `gradle-wrapper.jar`，CI 用 `gradle/actions/setup-gradle` 固定 Gradle 8.7；本地直接用已安装的 Gradle 即可。

## 目录

```
app/src/main/java/com/pocketforge/ai/
├── MainActivity.kt
├── core/       CryptoBox.kt（Keystore AES-GCM）  StreamCipher.kt（PF1 帧）  Sandbox.kt（路径沙箱）
├── data/       Models.kt  ConfigRepository.kt  SessionStore.kt
├── net/        AiClient.kt（SSE + function calling）
├── agent/      AgentEngine.kt（代理循环）  ToolRegistry.kt（12 个工具）  SkillLibrary.kt
└── ui/         AppRoot / ChatScreen / ProjectScreen / SettingsScreen / Theme / Components
app/src/main/assets/skills/   给 AI 的强制规范
.github/workflows/build-apk.yml
```

## 已知限制

- 不能在手机内编译 APK 或其他二进制（无 SDK/NDK），只产出源码与构建说明。
- 部分国产 ROM 的「所有文件访问」会随系统清理回收，需重新授权。
- Release 复用 debug 签名，仅适合自用；上架需换正式签名。

## 首次使用的正确顺序

1. **设置页**：Base URL 填到 `/v1` → API Key → 「保存配置」→「测试连接」看到成功
2. **权限体检**：点「授予所有文件访问」，回来点「写入自检」，确认真能写
3. **对话页**：点示例或自己描述目标；AI 会先读技能说明再动手

> 没配 Key 时发送不会静默失败，会直接在对话里给出配置步骤。
> 每次改动见 [CHANGELOG.md](CHANGELOG.md)。

## Roadmap

- [ ] 工具调用支持并发与超时
- [ ] 项目内 git 初始化与本地版本回滚
- [ ] 图片/音频素材的生成与入库
- [ ] 多项目上下文隔离与切换
