# file-search.md · 文件与内容搜索

## 工具

`fs_search(query, content=false, scope=workspace|root, limit=40)`

- 默认只搜**文件名**；`content=true` 才搜文件内容（返回行号 + 摘要）。
- `workspace` 可写区，`root` 是公共存储（只读，需全部文件权限）。
- 跳过 `.git`、`build`、`node_modules`、`.trash`、`Android`，避免噪声。

## 用法顺序

1. 先 `fs_search("world")` 找文件；
2. 命中多个再 `fs_search("硬规则", content=true, scope=root)` 缩小；
3. 确认路径后 `fs_read` 读全文，不要凭猜。

## 性能

- 单文件内容搜索上限 2MB、5000 行，超大文件改用 `fs_read` 分页。
- 结果上限 40 条，超过就细化关键词。

## 反模式

- 用 `fs_list` 递归扫整盘 —— 慢且吵。
- 在根目录做 `content=true` 全量 grep —— 只会超时。
- 搜到就写：先读，确认是目标文件再动手。
