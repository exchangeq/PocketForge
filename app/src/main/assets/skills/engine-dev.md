# engine-dev.md · 引擎层开发

> 适用：自研游戏引擎、渲染循环、ECS、资源管线。目标不是「能跑」，是「可替换、可测量」。

## 硬规则

1. **主循环固定步长**：逻辑按 1/60s 累加，渲染带插值；不要用 sleep 卡帧。
2. **渲染接口化**：`Renderer` 只暴露 clear/drawRect/drawText/present，具体 Canvas/OpenGL 实现在外层。
3. **世界与场景分离**：World 管实体表与调度，Scene 管流程；Scene 不直接持有全局单例。
4. **资源统一入口**：所有读取走 `Assets`，不许在业务里 `File(...)` 满天飞。
5. **每帧不分配**：update/render 里禁止 new 对象、禁止字符串拼接；预分配对象池。

## 目录

```
src/engine/   GameLoop / World / Entity / Scene / Input / Renderer / Assets
src/system/   Config / Logger / SaveSystem
src/game/     具体玩法（场景、实体、规则）
```

## 性能红线

- 单帧预算 16.6ms；逻辑 + 渲染分别计时，超了就降级（少画/少算）。
- 实体 > 2000 时要空间划分（网格或四叉树），不要 O(n²) 碰撞。
- 纹理尺寸 2 的幂，图集合并，避免每帧切纹理。

## 验收

- [ ] 循环在 60/30/15fps 下行为一致（逻辑不随帧率变）
- [ ] 无帧内分配（用 Profiler 或计数器验证）
- [ ] 换 Renderer 实现不需改游戏代码
