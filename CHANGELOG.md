# 更新日志

## v1.9.0（项目上下文 + 应用技能书 + 下载加速）

**项目不再"失忆"**
- 每个项目自动维护 `.pocketforge/state.json` 与 `PROGRESS.md`：目标、计划、每一步执行结果（时间 · 动作 · 成功/失败 · 摘要）。
- 进入项目对话页时，系统提示自动注入「这个项目怎么做 / 上次做到哪一步 / 开工顺序 / 写入范围」。
- `plan_set` 的计划同步进 state；每次工具执行后自动追加 PROGRESS.md，最多保留 30 条。
- 新建项目自动生成 state 与 PROGRESS.md 初始段。

**写入范围收紧**
- 在项目对话页里，只允许写 `projects/<当前项目>/`；写到别的项目直接拒绝，并提示先切到对应对话页。

**防撤回**
- 默认关闭；开关状态存进 UiState，与引擎一致，随时可开可关，不再出现"显示关了但引擎还锁着"。

**技能（强制读取）**
- `app-dev.md` 重写为应用专用技能书：工程骨架、Gradle DSL、页面状态、生命周期、权限清单、网络与验收。
- 新增 `syntax-kotlin.md`：Kotlin/协程/Compose/Gradle/JSON 的高频编译坑清单。
- 新增 `project-progress.md`：开工先读 PROGRESS.md 的协议与交接模板。
- 闸门升级：没读 `boundaries.md` 直接拒绝写入；写代码还要读 `syntax-kotlin.md`。
- `skills-index.json` 升 v7，53 项。

**下载加速**
- Release 包改为 4 段 Range 并行下载（≥6MB 才启用），任一失败自动回退单流；进度条按总字节合并。

**包体**：release 约 12MB，签名延续 `a5d8a11…f4b40`。

## v1.8.0（续跑 + 打包导出 + 日志中心 + 稳定性）

**新功能**
- **任务断点续跑**：每次发送都会记下目标；进程被杀/断电/强停后重新打开，聊天页顶部出现「上次任务中断」条，一键「继续」（带原目标重发）或「忽略」，超过 24 小时自动过期。
- **项目打包导出**：项目文件弹窗新增「导出 ZIP」，整包落到 `exports/<项目>.zip`（跳过 8MB 以上大文件，避免卡死）。
- **日志中心**：全局崩溃捕获写入 `files/logs/`，设置页显示最近日志数量，可「查看日志」「清空日志」；只保留最近 20 份。
- **模型预设**：gpt-4o-mini / gpt-4o / deepseek-chat / qwen-plus 一键切换，不用手打。

**稳定性**
- **读文件路径宽松解析**：模型给 `/storage/emulated/0/PocketForge/xxx`、`/sdcard/xxx`、只给文件名时，自动尝试「原样 / 相对工作区 / 去前缀 / projects 下同名文件」，不再动不动「读文件失败」。
- **429 自动降额**：触发限流时把本次 `max_tokens` 砍半（最低 1024）再重试，给下一轮留配额。
- 续跑标记成功完成即清除，不会误报。

**包体**：release 约 12MB，签名延续 `a5d8a11…f4b40`。

## v1.7.1（稳定性修复：切页/锁屏继续跑）

- **引擎改进程级单例**（`PocketForgeApp`）：切 tab、转屏、Activity 被回收都不再销毁正在跑的任务。
- **前台服务锁强化**：`PARTIAL_WAKE_LOCK` 每 9 分钟自动续租（15 分钟有效期），新增 `WIFI_MODE_FULL_HIGH_PERF` WiFi 锁，锁屏后网络不断。
- **任务开始即拉起前台服务**：只要开过后台常驻，点发送就确保服务在跑。
- **空响应自动重试**：上游偶发返回空内容时等 3–6 秒重试 2 次，不再直接报「模型这轮没有返回任何内容」。
- **超时放宽**：单轮看门狗 150s → 300s，流式读超时 120s → 240s，长回答不再被误判断流。
- 429 仍保留 6s/12s 退避重试。

## v1.7.0（后台常驻 + 六个大功能）

**1. 后台常驻（系统允许范围内最强一档）**
- 前台服务 `KeepAliveService`（`foregroundServiceType=dataSync`，`START_STICKY`，`stopWithTask=false`）。
- 持有 `PARTIAL_WAKE_LOCK` 并每轮续租，长任务不因休眠断流。
- 从最近任务划掉后在 `onTaskRemoved` 用 `AlarmManager` 1.5s 后拉回。
- 开机 / 应用更新后由 `BootReceiver` 自动拉起（仅当用户开过开关）。
- 设置页开关 + 「电池优化白名单」+ 「自启动/省电设置」两个直达入口；Android 13+ 自动请求通知权限。
- 说明：第三方应用不可能"绝对杀不死"，手机厂商的省电策略仍可能强杀；这条链路是官方 API 能给到的最强组合。

**2. 限流/超时自动重试**
- 上游返回 429（如 `Cluster TPM rate limit`）、rate limit、超时、连接中断时，自动退避重试 2 次（6s / 12s）。
- 重试中在消息里显示「上游限流/超时，N 秒后自动重试…」；最终失败时把 429 翻译成人话（额度用完、等待或换模型、调低 max_tokens）。

**3. 全局对话搜索**
- 「切换」弹窗内新增搜索框，跨所有会话按全文检索，命中显示标题 + 片段，点一下直接切过去。

**4. 对话页管理**
- 每行加「改名」「删除」；删除当前会话会自动落回主对话。

**5. 应用内文件编辑器**
- 项目文件列表里点任意文件（非目录）打开编辑器，可直接改并保存；保存前走文档锁校验，锁住的文件会被拒绝并说明。

**6. 用量统计**
- 记录请求次数、输入/输出字数，设置页「用量」一行显示，可一键重置。

**包体**：release 约 12MB，签名延续 `a5d8a11…f4b40`。

## v1.6.0（固定签名 + 项目对话页 + 防撤回 + 高质量技能）

**签名与分发**
- 固定签名 keystore（`keystore/pocketforge.jks`，不入仓库），本地与 CI 同一把钥匙；证书摘要仍是 `a5d8a11…f4b40`，1.5.0 可直接覆盖升级。
- GitHub Actions 新增「还原 keystore」与「校验签名」两步，Secrets：`PF_KEYSTORE_BASE64` / `PF_KEYSTORE_PASSWORD` / `PF_KEY_ALIAS` / `PF_KEY_PASSWORD`。
- 仓库已转为 public，App 检查更新、下载安装包不再必须填 token。

**项目对话页**
- 新建项目自动预备 `project-<slug>` 对话页；点项目卡片直接进入该项目自己的对话，不再挤在同一个主会话里。
- 「切换」里可见全部对话页，显示消息条数与 key。

**对话体验**
- 消息列表改倒序渲染，新消息贴底，不会再出现"一直往上拉"的漂移。
- 标题栏底部新增「复制全文」，一次拷走整段会话。
- 长按任意消息可改内容：用户消息同步回上下文，助手消息就地重写；改完落盘加密会话。
- 「防撤回」开关（按会话保存）：开启后该会话已写内容禁止修改、禁止撤回，提示明确拒绝。

**读文档**
- 输入框「附件」：选 txt/md/json 等文本文件（≤200KB），以 `【附件：文件名】` 拼进消息，AI 直接可读。
- 项目页「导入文档」保留，落到 `docs/imported/`。

**技能**
- 新增 `quality-output.md`（交付自检清单、反 AI 味硬规则、代码硬要求）与 `document-ingest.md`（分段读取、事实清单、敏感信息打码）。
- 系统提示新增第 5、6 条：所有交付按质量规范自检；遇到附件按文档规程先建事实清单再动笔。
- `skills-index.json` 升 v6，51 项。

**包体**：release 约 12MB，签名与 1.5.0 一致。

## v1.6.0（项目对话页 + 固定签名 + 质量线）

**仓库与签名**
- 仓库转 public：更新检查、下载都不再需要 token。
- 固定签名：`keystore/pocketforge.jks`（不入仓库），口令走 `keystore.properties` 或 CI Secrets；证书 SHA-256 仍为 `a5d8a11…f4b40`，1.5.0 可直接覆盖升级。
- CI 增加「Restore signing keystore」「Verify release signature」两步，Actions 出的包与本地包同一签名。

**项目对话页**
- 新建项目自动开一个 `project-<slug>` 对话页；点项目卡片直接进入，历史独立保存。
- 项目卡右侧「文件」仍可看文件树。

**聊天修复**
- 上滑阅读时不再被强行拽到底部：只在贴近底部时才自动跟随。
- 点自己的消息气泡可把内容填回输入框修改后重发。
- 头部「撤回」：撤掉上一轮用户消息并回填输入框。
- 更新弹窗：启动检查到新版本会弹「发现新版本」，可选「去更新 / 稍后」。

**导入用户文档**
- 项目页「导入文档」：多选文本文件落到 `docs/inbox/`，提示 AI 先读再写。

**技能**
- 新增 `content-quality.md`（完整/具体/有据/去 AI 味，分体裁基线 + 自检五问）。
- 新增 `user-docs.md`（docs/inbox 读取顺序、引用规则、格式处理）。
- `SKILLS.md` 加通用技能区；`skills-index.json` 升 v6（49 份）。


## v1.6.0（公开仓库 + 固定签名 + 项目对话页）

**固定签名（可直接覆盖升级）**
- 引入 `keystore/pocketforge.jks`（仓库外，CI 走 `PF_KEYSTORE_BASE64` 等 4 个 Secret）。
- `app/build.gradle.kts` 支持 `signingConfigs.release`，口令从 `keystore.properties` 或环境变量读取。
- 证书 SHA-256 保持 `a5d8a11…f4b40`，已装 1.5.0 可直接覆盖，无需卸载。
- CI 增加「Restore signing keystore」与「Verify release signature」，每次构建自动核对签名。

**仓库转公开**
- `exchangeq/PocketForge` 已 public，更新检查与 Release 下载不再需要 token。

**一个项目一个对话页**
- 会话按项目隔离存储（`filesDir/sessions/session-<key>.enc`，AES 加密落盘）。
- Chat 顶部显示「会话 · 标题」+「切换」，可打开对话页列表：进入 / 新建。
- 项目卡弹窗增加「进入项目对话」，点一下直接切到该项目的对话页。
- 新建项目时自动预备对应对话页（`ensureProjectSession`），不必手动建。
- 切换会话会保存当前历史、恢复目标历史，技能闸门按会话重置。

**其它新功能**
- 会话导出 Markdown 到工作区 `exports/chat-*.md`（含工具调用摘要）。
- 设置页显示「工作区 / 会话 / 缓存」占用，可一键清理缓存（保留安装包）。
- 新建项目支持骨架规模：最小 / 标准 / 完整。
- 项目已存在时只补齐缺失文件，不覆盖已有内容。

**包体**：release 约 12MB，签名 `a5d8a116…f4b40`。


## v1.5.0（远程更新可用 + 文件注入 + 逻辑修正）

**GitHub Actions 已跑通**
- 邮箱验证后 `build-apk` 正常执行：assembleDebug → assembleRelease → 上传 artifact，全部 success。
- 以后 push 即自动出包，不再需要本地重编。

**远程更新真正可用**
- 版本比较改为**只比数字**：`v1.5.0-beta` → `[1,5,0]`，`1.4.0+2` → `[1,4,0,2]`，逐段比、短的补 0，字母/后缀一律忽略。
- 私有仓库下载修复：有 token 时走 `assets/{id}` + `Accept: application/octet-stream`（`browser_download_url` 不带认证会 404）。
- 更新源支持直接粘贴 `https://github.com/owner/repo` 或 `owner/repo.git`，自动规范化。
- 设置页下载时显示百分比进度条。

**注入：手机文件 + 链接**
- 项目页新增「手机文件注入」：系统文件选择器多选 `.md`，校验（≥16 字节、含 `#`、≤512KB）后落到当前工作区 `skills/`，并自动记为已读，绕不过技能闸门。
- 链接注入保留：单个 `.md` 或 `skills-index.json` 清单；已存在的文件默认跳过，只有 `force=true` 才覆盖。

**UI / 逻辑修复**
- 页头新增「新版本 vX.Y.Z」胶囊，启动时静默检查一次，点击直达设置页更新。
- 助手消息底部加「复制」与字数，长回答可整段拷走。
- 项目卡可点击：弹窗显示项目文件树（最多 200 项，目录带 `/`）。
- 新建项目：已存在时只补齐缺失文件，不覆盖已有内容，并明确提示「项目已存在」。
- 远程注入已存在文件时给出「N 个已存在，保持原样」，不再假报全部注入。
- 新建对话框关闭后重置引擎字段，避免下次残留。
- 技能文档 `remote-update.md` 补充数字比对与私有仓库下载说明；`skills-index.json` 升 v5。

**包体**：release 12MB，签名沿用 `a5d8a11…f4b40`。


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
