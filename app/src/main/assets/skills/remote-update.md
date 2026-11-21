# remote-update.md · 发行版远程更新

## 链路

```
GitHub Releases（latest）
  → 读 tag / assets（挑非 debug 的 .apk）
  → 与已安装 versionName 比较
  → 下载到 cache/update
  → 校验包名 + 签名摘要（必须与当前包一致）
  → FileProvider + ACTION_VIEW 拉起系统安装器
```

## 硬规则

1. **只走 https**；重定向跟随，但最终仍校验包名与签名。
2. **签名不一致一律拒绝**，哪怕版本更新、哪怕用户催。
3. 下载前检查剩余空间（≥ 2 倍包体），不足就提示清理。
4. 不静默安装、不后台自动装；每一步都要用户看见。
5. 私有仓库才用 GitHub Token，且只存在加密配置里；私有仓库下载走 `assets/{id}` + `Accept: application/octet-stream`，`browser_download_url` 不带认证会 404。
6. **版本比较只比数字**：`v1.5.0-beta` → `[1,5,0]`，`1.4.0+2` → `[1,4,0,2]`，逐段比、短的补 0；字母/后缀一律忽略，不要用字符串比较。

## 失败对照

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 查询 404 | repo 名写错/没发 Release | 核对 owner/repo |
| 403 | 限流或 token 无 repo 权限 | 填 token 或稍后重试 |
| 下载卡住 | 网络代理/大文件超时 | 重试，或去 Release 页手动下 |
| 拉起安装器失败 | 未允许「安装未知应用」 | 去系统设置授权 |
| 签名不一致 | 包被替换 | 停止安装，改用官方渠道 |

## 与 AI 的约定

- 只有用户明确说「检查更新 / 升级」才调 `update_check` / `update_install`。
- 汇报时给：当前版本、最新 tag、包体大小、校验结果。

## 7. 交给 LM 下载器

- 检测到 `com.lm.downloader` / `com.lm.downloader.beta` 时，调用 `download_handoff(url, filename)` 把链接交给它，由它建任务（32 路并发更快）。
- 应用内更新点「下载并安装」也会优先跳下载器；想用内置下载就把设置里的开关关掉。
- 下载器装完后回到 PocketForge 的「设置 → 远程更新」再点安装，签名校验仍由本应用完成。
