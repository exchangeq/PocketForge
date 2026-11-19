# asset-pipeline.md · 资源管线

## 目录

```
assets/images/   图片（按场景）
assets/audio/    音频
assets/data/     JSON/CSV 表
assets/fonts/    字体
```

## 流程

1. 原始素材放 `assets/_raw/`（不入包或单独忽略）。
2. 处理后产物进正式目录，命名 `类型_对象_状态`。
3. 维护 `assets/manifest.json`：路径、尺寸、大小、来源。

## 检查

- [ ] 无重复文件（同名不同内容）
- [ ] 单文件体积达标（图 ≤500KB，音频 ≤2MB）
- [ ] 代码里引用的路径都存在（跑一遍引用扫描）

## 禁止

- 不用中文文件名（跨平台会炸）。
- 不在运行时下载关键资源（离线也要能玩）。
