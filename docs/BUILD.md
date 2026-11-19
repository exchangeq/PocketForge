# 构建说明

## 方式一：GitHub Actions（推荐，免本地环境）

工作流：`.github/workflows/build-apk.yml`，触发条件：push 到 `main` 或手动 `workflow_dispatch`。

产物在运行页面的 **Artifacts → PocketForge-apk**。

> **前置条件**：GitHub 账号至少要有**一个已验证的邮箱**。未验证时会出现：
> - `git push` 报 `You must verify your email address`
> - Actions 运行直接 `startup_failure`（0 个 job）
>
> 处理：Settings → Emails 里点验证链接，通过后重新推送即可。

## 方式二：本地构建

依赖：JDK 17、Android SDK Platform 34 + Build-Tools 34、Gradle 8.7。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties

gradle assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

说明：

- 本仓库不含 `gradle-wrapper.jar`，请用 `gradle 8.7` 命令，或先执行 `gradle wrapper --gradle-version 8.7`。
- Release 复用 debug 签名（`signingConfig = debug`），便于自安装；上架需替换正式签名。
- 内存紧张时加 `-Dkotlin.compiler.execution.strategy=in-process` 并调低 `org.gradle.jvmargs`（如 `-Xmx1536m`）。

## 产物校验

```bash
aapt2 dump badging app-release.apk | head -5      # 包名 / minSdk / targetSdk
apksigner verify --print-certs app-release.apk    # 签名
```
