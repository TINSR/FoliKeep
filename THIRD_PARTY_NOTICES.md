# 第三方依赖与资源

项目自己的代码许可与依赖许可是两回事。本文件列出关键资源，不替代各依赖发行包的原始许可。

| 依赖 / 资源 | 用途 | 许可 |
|---|---|---|
| Kotlin、AndroidX / Compose / Room / DataStore、协程、Hilt、OkHttp、Coil | 应用基础组件 | Apache-2.0 |
| PDFBox-Android 2.0.27.0 | PDF 文字坐标提取 | Apache-2.0 |
| KaTeX 0.16.11 及随包字体 | 本地公式排版 | MIT |
| JUnit 4 | 自动测试 | EPL-1.0 |
| Gradle Wrapper | 构建入口 | Apache-2.0 |

PDFBox-Android 的 LICENSE / NOTICE 保留在 `yuejian/core/pdf/src/main/assets/licenses/pdfbox-android/`，KaTeX 的原始许可位于 `yuejian/core/markdown/src/main/assets/markdown/katex/LICENSE.txt`，随 APK 分发。

本地渲染资源清单及安全设置见 `yuejian/core/markdown/src/main/assets/markdown/THIRD_PARTY.md`。没有引入 PDFium 或 MuPDF 二进制。

完整依赖树可在安卓工程目录运行 `./gradlew :app:dependencies` 查看。新增第三方资源时请保留上游许可与版本，不从本文件推断所有传递依赖具有同一种许可。
