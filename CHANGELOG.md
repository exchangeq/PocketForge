# 更新日志

## v1.4.0（发行版远程更新 + 搜索增强 + 47 技能）

**远程更新（应用内直接升级）**

- 新模块 `update/UpdateManager.kt`：读 GitHub Releases latest → 比对版本 → 下载 release APK → 校验 → 拉起系统安装器。
- 安全校验：包名必须一致 + 签名摘要必须与当前安装包一致，不符直接拒绝（防替换包）；只走 https/重定向跟随。
- 设置页新增「远程更新」：显示当前版本、更新源（owner/repo）、GitHub 令牌（加密存储）、检查更新 / 下载并安装（带百分比进度）。
- 新工具：`update_check` / `update_install`（仅用户明确要求时才调用）。
- Manifest 增加 `REQUEST_INSTALL_PACKAGES` 与 FileProvider（`cache/update` 目录）。

**GitHub 接入**

- 新增 `githubToken` 配置（AES-256-GCM 落盘），用于私有仓库或 API 限流场景。
- 设置页内置「生成令牌 →」链接，点开直接到 GitHub 令牌页：`https://github.com/settings/tokens/new?scopes=repo&description=PocketForge`。

**搜索增强**

- `fs_search` 支持 `scope=workspace|root`、`content=true`（默认）、`maxHits`，同时命中文件名与内容行号；根目录需「所有文件访问」权限，只读。
- 新增技能 `file-search.md`：先文件名再内容，避免全盘 grep。

**技能 45 → 47 份**

- 新增 `remote-update.md`（版本对比/签名校验/失败对照）、`file-search.md`。
- `skills-index.json` 升到 v4（47 条，含 sha256）。

**包体**：release 12MB / debug 15MB，签名沿用 `a5d8a11…f4b40`。


## v1.3.0（骨架扩容 + 45 技能 + 文档锁 + 推理强度）

**修掉「新建项目没反应」的真正原因**

- 根因：`project_new` 在写类工具闸门里，新装/重开后没读过技能，工具层直接返回 `gate=skills_not_loaded`，UI 只把错误塞进一行小字，看起来就是「点了没反应」。
- 修复：用户手动点「新建」视为已确认意图，引擎补记 `SKILLS.md` + 对应专项技能再派发；失败时弹窗用红字给出真实原因，成功则弹「骨架已生成」清单。

**项目骨架扩到「能直接接着写」**

- 游戏：新增 `src/engine/{GameLoop,World,Entity,Scene,Input,Renderer,Assets}.kt`、`src/system/{Config,SaveSystem,Logger}.kt`、`src/Main.kt`、`src/game/Scene.kt`、`docs/{tech,data-model}.md`。
- 写作：新增 `chapters/000-模板.md`、`world.md`、`cast/cast.md`、`continuity.md`（伏笔账本）、`style-guide.md`、`docs/publishing.md`、`plan/todo.md`；`world.md` 与 `style-guide.md` **创建后自动上锁**。
- 应用：新增 `src/main/{App,Router}.kt`、`src/system/Storage.kt`、`docs/architecture.md`。

**长文本不再被卡脖子**

- `max_tokens` 默认 8192，`max_steps` 40，单次写入上限提到 4MB。
- 系统提示强制「分卷写」：每卷 ≤300 行，`fs_write(append=true)` 追加，写完读回尾部校验；禁止一次硬塞整本书。

**文档锁（定稿保护）**

- 新工具：`doc_lock`（整篇软锁 / 指定章节锁 / 硬锁）、`doc_unlock`、`doc_locks`。
- 锁记录在 `<workspace>/.pocketforge/locks.json`；写入被锁文件时工具层直接拒绝并给出可读原因；章节锁只比对被登记章节，其余章节可正常续写。
- 项目页新增「锁定文档」区块，可一键解锁；写作项目创建时自动锁 `world.md`、`style-guide.md`。

**技能从 10 份扩到 45 份**

- 游戏线：engine-dev / system-dev / level-design / balance-design / save-load / pixel-art / asset-pipeline / audio / image-pipeline
- 写作线：longform-writing / narrative-structure / worldbuilding / script-dialogue / poetry-lyrics / editing / copywriting
- 工程线：kotlin-style / ui-design / data-model / api-protocol / security / testing / debugging / performance / refactor / project-structure / roadmap-planning / qa-checklist / release-checklist / android-build / cli-build-termux / git-workflow / accessibility-i18n / data-viz / docs-lock
- `skills-index.json` 更新到 version 3（45 条，含 sha256），远程注入可直接拉全量。

**推理强度可自选**

- 设置页五档：关 / 低 / 中 / 高 / 极高，透传 `reasoning_effort`（极高映射 high）；系统提示按档位调整「先列方案、自检次数」。

**包体**：release 12MB / debug 15MB，签名指纹沿用 `a5d8a11…f4b40`，可覆盖升级。


## v1.2.0（界面重做 + 技能注入 + Termux 桥接）

**界面全部重做**

1. **字体**：内嵌思源黑体（Noto Sans SC，裁剪到常用字，6.2MB），不再被 ROM 主题的手写体污染；标题/正文/标签都有固定字重与行高。
2. **配色与层次**：主色改为墨绿 `#0E7C6B`，背景 `#F3F6F8`，卡片白底细描边；深色模式同步适配。圆角统一 8/10/14/20dp。
3. **对话页**：用户气泡右对齐、助手消息带 26dp 方形头像；步骤卡改成左侧状态色条 + 「步骤 01」+ 状态胶囊；「思考中」换成三点呼吸动画；输入框改成胶囊形，发送键是 46dp 圆形（空输入灰、可发送主色、生成中淡红停止）。
4. **页头/底栏**：页头加 PF 方块标志与状态点（已加密 / 未配置密钥）；底栏改图标 + 文字，选中态跟随主题色。
5. **项目页**：卡片式项目列表 + 技能中心（已注入/内置状态标签、远程注入入口、强制注入开关）。
6. **设置页**：分区标题 + 说明文案，新增技能源、Termux 桥接整块配置与「测试 Termux / 信任新指纹」。

**Markdown 渲染（自研，无第三方库）**

- 支持 `**粗体**`、`*斜体*`、`` `行内代码` ``、`~~删除线~~`、`[链接](url)`、# 标题（1~4 级）、有序/无序列表、引用块、代码块（带语言标签 + 横向滚动）、表格（首行当表头）、`---` 分割线。
- 不会再出现裸露的 `**`。

**技能能力**

- `skills_index` / `skill_read` 改为工作区优先：`<workspace>/skills/` → 内置 assets → 兜底。
- 新增工具 `skill_install`：支持单个 `.md` 或 `skills-index.json` 清单，下载后校验大小与内容，写入工作区并生成 `skills-index.json` 锁文件（含 sha256）。
- 新增「强制注入」：`skills/.force` 标记 + 覆盖工作区技能，**只作用于当前工作区**，不影响其他项目；可在项目页「取消强制」恢复回退内置。
- 新增技能文件：`answer-style.md`（回答四段式）、`markdown-style.md`（排版规范）、`character-card.md`（角色卡与强制注入规则）、`termux.md`（SSH 加密栈与命令红线）。

**Termux 桥接**

- 新依赖 `com.github.mwiede:jsch:0.2.20`；`termux/SshClient.kt` 走 SSH-2 连接 `127.0.0.1:8022`。
- 加密栈：KEX `curve25519-sha256`，主机密钥 `ssh-ed25519`，对称 `chacha20-poly1305@openssh.com` / `aes256-gcm@openssh.com`，MAC `hmac-sha2-256`，用户认证 publickey(ed25519) 或 password。
- 主机指纹 TOFU：首次连接记录 SHA-256，之后不一致直接拒绝并提示「信任新指纹」。
- 新工具 `termux_status` / `termux_exec`（默认 30s 超时，破坏性命令黑名单：`rm -rf /`、`mkfs`、`dd if=`、`reboot`、`su/sudo`、`pm uninstall`、fork bomb 等）。
- 设置页可配置主机/端口/用户/密码或私钥/口令，`allowShell` 默认关闭。

**回答与稳定性**

- 去掉「（本轮无文本输出）」，改为「已执行 N 项操作，结果见上方步骤卡片」。
- 加看门狗：单轮 150s 超时主动断流，异常路径统一把 `streaming` 置 false，不会再永远停在「生成中」。
- 系统提示要求四段式完整回答（结论 → 关键点 → 文件 → 下一步），禁止半截话；`maxTokens` 默认 4096，`maxSteps` 默认 30。
- 输入框上方紫色进度条已移除。

**包体**

- release 12MB / debug 15MB（字体占约 6MB）；签名证书沿用 `a5d8a11…f4b40`。


## v1.1.0（手势修复版）

**修掉的三个真问题**

1. **底部导航点不动 / 页面跳不过去**
   - 根因：内容区用了 `fillMaxSize()` 放在 `Column` 里，把底部 Tab 的高度挤成 0，Tab 实际不可见也点不到。
   - 修复：内容区改为 `Box(Modifier.weight(1f))`，底部导航固定 52dp。

2. **发送按钮被挤出屏幕 / 点发送没反应**
   - 根因同上：输入栏在可视区外。
   - 修复：随上面一起解决；输入区加 `imePadding()`，键盘弹出时不再遮挡。

3. **「未配置密钥」点了没反应**
   - 根因：Header 的标签只是 `Surface`，没有点击事件；也没告诉用户该去哪。
   - 修复：整块可点击 → 直接跳「设置」页，文案改为「未配置密钥 · 去填 ›」。

**顺手补的**

- 未配置 Key 时发送，不再静默失败：直接在对话里给出配置四步。
- 首次进入显示「先连上模型」引导卡 + 3 个快捷示例，点一下就填进输入框。
- 请求失败显示可读错误条（含「设置」快捷入口）。
- 「停止」现在会真正断开连接，不用等超时。
- 设置页加「权限体检」：系统版本、所有文件访问、读存储、根目录可读性逐项列出，并提供「写入自检」（真写一个文件再删）。
- Base URL 智能补全：`https://host` 自动补 `/v1/chat/completions`；已填完整端点则不再重复拼接。
- 允许局域网明文 HTTP（`http://192.168.x.x:11434`），修掉自建 Ollama 连不上。
- Android 10 及以下补 `requestLegacyExternalStorage`。
- 流式输出按 55ms 节流，长回答不再卡顿。

## v1.0.1

- 首次可安装版本：加密配置、技能闸门、折叠步骤、文件沙箱、PF1 流解密。
