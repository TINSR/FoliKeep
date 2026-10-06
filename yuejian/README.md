# 构建 FoliKeep

这是安卓工程目录。使用方法见[仓库首页](../README.md)，上下文逻辑见[Harness 说明](../docs/harness.md)。

## 环境

- JDK 17。
- Android SDK Platform 36，Build Tools 35.0.0。
- Gradle 8.11.1，由仓库里的 Wrapper 下载。
- AGP 8.10.1，Kotlin 2.0.20；最低 Android 8.0 / API 26。

用 Android Studio 打开当前目录即可。SDK 路径由 IDE 写进 `local.properties`，这个文件不提交。命令行也可以配置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。

## 构建与安装

Windows PowerShell，在本目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

Linux / macOS：

```sh
chmod +x gradlew
./gradlew :app:assembleDebug
```

Debug APK 在 `app/build/outputs/apk/debug/app-debug.apk`。连接设备后可运行 `:app:installDebug`。

Debug 包名是 `com.yuejian.app.rebuild.debug`，正式包名是 `com.yuejian.app`。它们可以并存，数据不共用。如果要迁移，用应用内备份恢复，不要直接复制数据库。

首次构建需要联网下载 Maven 依赖。保留了依赖锁文件和 Wrapper 校验和；改变依赖版本时请主动更新 lockfile。

## 测试

```powershell
.\gradlew.bat :core:model:test :core:ai:testDebugUnitTest :core:markdown:testDebugUnitTest
python tools/verify-source-sql.py
```

第二条使用 Python 3 的标准库 sqlite3，检查真实 DAO 查询以及 schema 10→11 迁移，不需要额外 Python 包。

有设备或模拟器时：

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest :core:database:connectedDebugAndroidTest :core:files:connectedDebugAndroidTest :core:pdf:connectedDebugAndroidTest
```

Linux / macOS 换成 `./gradlew`。GitHub Actions 的构建与设备测试是分开的，设备测试可以手动触发。没有真实 API Key 的测试不能证明服务商接口已联调成功。

## 正式包

```powershell
.\gradlew.bat :app:assembleRelease :app:bundleRelease
```

默认不带正式签名，输出不能作为正式版直接安装。需要签名时，通过构建进程提供四个环境变量：

| 变量 | 内容 |
|---|---|
| `YUEJIAN_STORE_FILE` | 本机签名文件路径 |
| `YUEJIAN_STORE_PASSWORD` | 签名库密码 |
| `YUEJIAN_KEY_ALIAS` | 签名别名 |
| `YUEJIAN_KEY_PASSWORD` | 签名密钥密码 |

必须全部配置或全部留空。仓库不含发布私钥、密码或本机加密凭据。自行签名的包不能覆盖作者签名的正式版。

正式版本 1.1.0 / versionCode 44。数据库 schema 11，备份格式 4，恢复兼容格式 1～3。历史 schema 文件必须保留，迁移时不要使用 destructive fallback。

## 主要入口

| 内容 | 文件 |
|---|---|
| 输入预算、历史整理、摘要校验 | `core/model/src/main/kotlin/com/yuejian/model/ConversationHarness.kt` |
| 发送、压缩调用、费用账本 | `feature/conversation/src/main/kotlin/com/yuejian/conversation/ConversationViewModel.kt` |
| 实际发送的 JSON、图片和流式响应 | `core/ai/src/main/kotlin/com/yuejian/ai/OpenAiCompatibleProvider.kt` |
| 原始资料与共享对话存储 | `core/files/src/main/kotlin/com/yuejian/files/LocalAnnotations.kt` |
| 备份关联 ID 的恢复 | `core/files/src/main/kotlin/com/yuejian/files/LocalBackup.kt` |

模块关系见[架构说明](../docs/architecture.md)。
