# syntax-kotlin.md · 语法重点（写代码前必须读）

> 这份是"编译不过"的高频雷区清单。写 Kotlin / Compose / Gradle 代码前先扫一遍，比事后修 20 个 `Unresolved reference` 快。

## 1. Kotlin 基础

- 字符串模板里想输出 `$` 用 `${'$'}`，想输出 `"` 用 `\"`。
- `?:` 只用于可空；`!!` 尽量不用，改用 `?: return`。
- `runCatching {}.getOrElse {}` 里 `return` 只能返回**当前函数**的类型，注意类型一致。
- `when` 作为表达式必须覆盖所有分支或写 `else`；作为语句可省。
- 块注释 `/* */` 在 Kotlin 里**可以嵌套**：文档里出现 `skills/*.md` 这种字样会开新注释，导致 "Unclosed comment"。写文档时避免连续 `/*`。
- 扩展函数不能访问 private 成员；需要就把逻辑放进类里。
- `data class` 的 `copy()` 只做浅拷贝，集合要重建。

## 2. 协程

- `suspend` 函数只能在协程或另一个 suspend 里调用；Compose 里用 `rememberCoroutineScope()` 或 `LaunchedEffect`。
- `Dispatchers.IO` 做文件/网络，`Dispatchers.Main` 更新 UI；跨线程回主线程用 `withContext(Dispatchers.Main)`。
- `launch` 抛异常会崩；`async` 要 `await()` 才抛出。长任务包 `try/catch`，别让一个失败拖垮整个作用域。
- 取消是协作式的：循环里要 `ensureActive()` 或 `delay()`，否则 cancel 不生效。
- `SupervisorJob` 让子任务互不影响；引擎作用域应挂它。

## 3. Compose

- 所有 `dp` / `sp` 必须是 `Float`：写 `16.dp`、`12.sp`，不要 `16.dp as Int`。
- `Typography` 的 `fontSize`/`lineHeight` 也是 `TextUnit`，不能传 Int。
- 忘记 import 是最常见的编译错误：`HorizontalDivider`、`LocalClipboardManager`、`AnnotatedString`、`PaddingValues` 都要显式导入。
- `LazyColumn` 里 `items(list, key = { it.id })` 的 key 必须唯一且稳定，否则滚动跳动。
- 需要长按：`combinedClickable(onClick=..., onLongClick=...)`，并且函数上加 `@OptIn(ExperimentalFoundationApi::class)`。
- 状态派生：`val x = state.field` 而不是再 `remember { mutableStateOf(...) }`，避免 UI 与引擎状态不一致。
- `AlertDialog` 的 `text` 里放 `Column` 要包 `verticalScroll` 并限制 `heightIn`，否则内容超屏。

## 4. Gradle Kotlin DSL

- 每个 `build.gradle.kts` 顶部要 `import java.util.Properties` 才能用 `Properties()`。
- `signingConfigs.create("release") { ... }` 之后用 `signingConfigs.getByName("release")`。
- `buildTypes.release` 里访问外部变量要先在 `android {}` 顶层声明，作用域别写错。
- 依赖坐标用 `implementation("group:artifact:version")`，不要漏引号。

## 5. JSON / 文件 / 网络

- `JSONObject.optString("k", "")` 比 `getString` 安全；数组用 `optJSONArray` 判空。
- 写文件前 `parentFile?.mkdirs()`；大文件分段 `append`，单次写入 ≤4MB。
- `HttpURLConnection` 要显式声明类型 `val conn: HttpURLConnection = ...`，`.apply{}` 里类型推断会翻车。
- `contentLengthLong` 是 API 24+；低版本用 `contentLength`。
- 读流要 `use {}`，否则句柄泄漏，第二次读就失败。

## 6. 提交前自检

1. `grep -rn "TODO\|FIXME\|..."` 无残留。
2. 所有 `when` 分支有 `else` 或穷尽。
3. 新增文件都进了 `git`/仓库清单。
4. 编译一次 `assembleDebug`，别靠猜。
