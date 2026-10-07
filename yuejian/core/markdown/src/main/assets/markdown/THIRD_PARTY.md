# 本地渲染资源与许可说明

本目录（App 内的 `android_asset/markdown/`）承载 AI 回答的离线渲染能力，**不依赖任何 CDN**，
安装即用，飞行模式与断网状态下历史回答同样可以正常显示。

## 打包内容

| 文件 | 来源 | 版本 | 许可 |
| --- | --- | --- | --- |
| `katex/katex.min.js` | KaTeX | 0.16.11 | MIT License © Khan Academy 等贡献者 |
| `katex/katex.min.css` | KaTeX | 0.16.11 | MIT License © Khan Academy 等贡献者 |
| `katex/fonts/*.woff2`（20 个） | KaTeX 发行包 | 0.16.11 | MIT License（字体文件同包发布） |
| `katex/LICENSE.txt` | KaTeX | 0.16.11 | MIT License 原文 |
| `answer.html` / `answer.css` / `render.js` | 本项目自研 | — | FoliKeep项目自有代码 |

## 字体说明

- 只打包 `woff2`（约 296 KB / 20 个文件）。Android 8.0（API 26，本项目 minSdk）以上 WebView 均支持 woff2。
- KaTeX CSS 里同时声明了 `woff2` / `woff` / `ttf` 三个来源，浏览器只会在 woff2 不可用时回退；
  在目标设备上 woff2 一定命中，因此不额外打包 `woff` / `ttf` 以控制安装包体积。
- 公式里的中文（如 `\text{速度}`）不属于 KaTeX 字体覆盖范围，由 WebView 的系统字体兜底渲染，
  这些字符用于公式排版，属于正常显示。

## 安全策略

- 页面只从 `file:///android_asset/markdown/` 读取资源；`WebViewClient.shouldInterceptRequest`
  会拦截并丢弃所有其它请求，回答中的图片 / 脚本 / 远程资源一律不会加载。
- 所有链接由 App 接管（`shouldOverrideUrlLoading` 返回 true），确认后才会交给系统浏览器。
- 没有任何 `@JavascriptInterface` 桥接暴露给页面，高度测量由原生侧 `evaluateJavascript` 主动拉取。
- 模型输出在进入 WebView 之前已完成 HTML 转义，仅供 ${'$'}{'$'}数学占位${'$'}{'$'} 使用的 KaTeX 由本地 JS 渲染，
  `throwOnError: false` + `strict: "ignore"`，单个公式错误不会影响整条回答。
