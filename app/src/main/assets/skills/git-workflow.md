# git-workflow.md · 版本管理

## 提交

- 一次提交一件事；message 用祈使句：`fix: 修复底部导航点击失效`。
- 前缀：feat / fix / docs / refactor / test / chore。
- 不提交：构建产物、IDE 配置、密钥、local.properties、大二进制（除非必要）。

## 分支

- 主干可用，短分支开发，合并前自测。
- 大改先开 issue 写清范围，避免边写边改需求。

## 回滚

```bash
git log --oneline -10
git revert <sha>        # 安全，保留历史
git reset --hard <sha>  # 只在本地未推送时用
```

## 与 AI 的约定

- 每次改完列出改动文件清单，方便一次性提交。
- 不在没有告知的情况下 force push。
