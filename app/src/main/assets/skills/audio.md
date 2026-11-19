# audio.md · 音频

## 分类

- BGM：循环，淡入淡出 1-2s，切场景交叉淡化。
- SFX：短促 < 300ms，同一时刻最多 3 个，避免糊成一团。
- 语音：按角色分目录，采样 44.1k / 48k，单声道够用。

## 工程约定

```
assets/audio/bgm/  loop_*.ogg
assets/audio/sfx/  ui_*.ogg / hit_*.ogg
assets/audio/voice/<角色>/<章节>_<序号>.ogg
```

- 格式优先 OGG（体积小），无损才用 WAV。
- 音量统一归一化到 -3dBFS，代码里再调增益。
- 音频不阻塞主线程加载，预加载常用 SFX。

## 验收

- [ ] 静音开关生效
- [ ] 切后台自动暂停
- [ ] 无爆音（首尾 5ms 淡入淡出）
