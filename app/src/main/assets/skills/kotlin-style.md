# kotlin-style.md · Kotlin 代码风格

## 硬规则

1. 文件一个主类，命名 `PascalCase`；函数 `camelCase`；常量 `UPPER_SNAKE`。
2. 禁止 `!!`；用 `?.let`、`require`、`check` 或早返回。
3. 函数 ≤ 40 行，参数 ≤ 5 个；超了就拆。
4. IO 必须 `Dispatchers.IO`，UI 只在主线程。
5. 异常不要吞：`runCatching` 后要么处理，要么日志 + 上抛。

## 惯用法

```kotlin
require(path.isNotEmpty()) { "缺少参数 path" }
runCatching { file.readText() }.getOrDefault("")
val name = raw.trim().takeIf { it.isNotBlank() } ?: return null
```

## 反例

- `try { ... } catch (e: Exception) {}` —— 静默失败，最难查。
- 超长链式调用跨 3 屏 —— 中间变量命名，可读优先。
- 在 Composable 里做 IO —— 必卡帧。
