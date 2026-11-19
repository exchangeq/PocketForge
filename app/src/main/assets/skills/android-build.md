# android-build.md · Android 构建与出包

> 本 App 内不编译 APK。出包路径：GitHub Actions，或 Termux 本地 gradle。

## 关键点

1. **签名**：release 用固定 keystore，`apksigner verify --print-certs` 校验指纹，升级必须同签名。
2. **体积**：资源优先（字体/图片/音频），`shrinkResources` + `minify` 慎开（反射会挂）。
3. **版本**：versionCode 单调递增；versionName 语义化。
4. **16KB 页对齐**：targetSdk 35+ 需注意 so 对齐，纯 Kotlin 项目无此问题。
5. **minSdk**：写清最低版本，API 分支用 `Build.VERSION.SDK_INT` 判断。

## Gradle 常用

```bash
./gradlew assembleRelease
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

## 出包检查

- [ ] `apksigner verify` 通过，指纹与上一版一致
- [ ] 安装后能启动，不闪退（logcat 无 FATAL）
- [ ] 包体变化有解释（新增字体/依赖要说明）
