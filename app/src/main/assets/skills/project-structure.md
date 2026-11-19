# project-structure.md · 项目目录规范

## 通用骨架

```
<project>/
  README.md          一分钟看懂
  PROJECT.md         项目卡与验收
  AGENTS.md          AI 开工须知
  plan/todo.md       进度
  docs/              设计与技术文档
  src/               源码
  assets/            资源（按类型分子目录）
  tests/             测试
  .pocketforge/      元数据（project.json / locks.json）
```

## 分类

- 游戏：`src/engine` `src/system` `src/game` `assets/images|audio|data` `design/gdd.md`
- 写作：`chapters/` `cast/` `world.md` `outline.md` `continuity.md` `export/`
- 应用：`src/main` `src/system` `src/feature` `docs/architecture.md`

## 规则

- 源码与资源分离，不把图片塞进 src。
- 一类资源一个目录，命名带类型前缀。
- 临时文件进 `.tmp/`，可整目录删。

## 验收

- [ ] 新人 1 分钟能找到入口文件
- [ ] 没有超过 3 层的无意义嵌套
