# app-dev.md · 应用程序专用技能书（Android / Kotlin / Compose）

> 做 App 只认这套顺序：先定数据和页面，再定权限和生命周期，最后才是样式。
> 写完必须能 `assembleDebug` 通过；编不过的不算交付。

## 1. 工程骨架（新建 App 必有的文件）

```
app/
  build.gradle.kts            # 插件、compileSdk、依赖、签名
  src/main/AndroidManifest.xml
  src/main/java/<pkg>/
    MainActivity.kt           # 只负责 setContent
    ui/AppRoot.kt             # 顶层导航与状态
    ui/<Screen>.kt            # 每个页面一个文件
    data/Models.kt            # 数据类 + JSON 序列化
    data/Repository.kt        # 读写与持久化
    core/                     # 纯逻辑，不依赖 Compose
  src/main/res/values/        # strings / themes
```

目录职责写进 `PROJECT.md`，别把所有东西塞进 MainActivity。

## 2. Gradle（Kotlin DSL）要点

- `plugins { id("com.android.application") version "8.6.1"; id("org.jetbrains.kotlin.android") version "2.0.21" }`
- `android { compileSdk = 34; defaultConfig { minSdk = 26; targetSdk = 34 } }`
- Compose 必须三件套：`buildFeatures { compose = true }`、`composeOptions`（Kotlin 2.x 用 `kotlin("plugin.compose")`）、`implementation(platform("androidx.compose:compose-bom:2024.09.03"))`
- 依赖版本要钉死，禁止 `+` 或 `latest.release`
- 签名：release 从 `keystore.properties` 或环境变量读，**密钥文件绝不进仓库**

## 3. 页面与状态

- 页面状态用 `data class UiState`，一次性描述「消息、忙碌、错误、标题」，不要散落十几个 `mutableStateOf`。
- 跨页面共享的对象（引擎、仓库）放 `Application` 或单例，**不要**放在 `remember{}` 里——Activity 重建会连带销毁正在跑的任务。
- 列表用 `LazyColumn`；会话类列表用 `reverseLayout = true`，新消息贴底，避免"一直往上拉"。
- 长文本必须 `SelectionContainer` 或可复制，别让用户没法摘。

## 4. 生命周期与后台

- 长任务：前台服务（`foregroundServiceType` 明确声明）+ `START_STICKY` + `PARTIAL_WAKE_LOCK` 定时续租；Android 13+ 要 `POST_NOTIFICATIONS`。
- 服务里不要直接跑网络请求，任务留在进程内的引擎作用域，服务只负责"别被杀"。
- Activity 销毁不等于任务结束：引擎必须在进程级。

## 5. 权限清单（按需声明，缺一不可）

| 能力 | 权限 / 入口 |
| --- | --- |
| 读写公共存储 | `MANAGE_EXTERNAL_STORAGE` + 设置页跳 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` |
| 前台服务 | `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` |
| 常驻通知 | `POST_NOTIFICATIONS`（运行时申请） |
| 开机自启 | `RECEIVE_BOOT_COMPLETED` |
| 装 APK | `REQUEST_INSTALL_PACKAGES` + FileProvider |
| 电池白名单 | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` |

## 6. 网络与数据

- 只用 `HttpURLConnection`/OkHttp 直连用户配置的 `/v1`，不要偷偷往第三方发数据。
- 流式响应按 SSE 逐行解析，`[DONE]` 结束；读超时给足 240s，别 30s 就断。
- 配置、会话落盘前加密（AES-GCM，密钥放 Keystore）。
- 大文件下载用 Range 并行分片（4 段）加速，失败回退单流。

## 7. 验收（不过不算交付）

1. `gradle assembleDebug` 通过，`assembleRelease` 通过。
2. 冷启动 3 秒内出首屏，不 ANR。
3. 每个按钮都有实际行为；没有"点了没反应"的控件。
4. 权限缺失时有明确提示和跳转入口，不是静默失败。
5. 断网、限流、空响应都有可读的错误文案与重试路径。
6. README 写清怎么构建、怎么签名、怎么发版。
