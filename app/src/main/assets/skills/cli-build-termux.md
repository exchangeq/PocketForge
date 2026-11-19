# cli-build-termux.md · 在 Termux 里构建

## 环境准备

```bash
pkg update && pkg upgrade -y
pkg install openjdk-17 gradle git unzip
```

## 构建

```bash
cd ~/PocketForge
gradle assembleRelease --no-daemon
ls -lh app/build/outputs/apk/release/
```

## 常见问题

| 问题 | 处理 |
| --- | --- |
| 内存不足 | 加 `-Xmx2g`，或改 `org.gradle.jvmargs` |
| SDK 缺失 | 装 termux 的 android-sdk 或直接下 commandline-tools |
| 编译超时 | 用 App 的 `termux_exec` 时把 timeoutSec 调到 120 |
| 依赖拉不动 | 配镜像源，或先 `--offline` |

## 建议

- 首次构建尽量在电脑/CI 做，Termux 只做增量。
- 构建脚本放 `scripts/build.sh`，参数化版本号。
