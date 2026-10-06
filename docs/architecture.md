# 工程结构

FoliKeep 是原生安卓应用。界面用 Jetpack Compose，业务状态在 ViewModel，持久数据通过 repository 接口访问。

| 模块 | 责任 |
|---|---|
| `app` | 单 Activity、导航、Hilt 绑定及数据库迁移注册 |
| `feature:library` | 导入入口、书架与继续阅读 |
| `feature:reader` | PDF / 图片阅读、选区、标注目录、卡片集合 |
| `feature:conversation` | 问答窗口、资料管理、流式状态和上下文调用编排 |
| `feature:settings` | 模型、预算、思考与阅读外观 |
| `core:model` | 领域数据、repository 契约、上下文纯逻辑与预算计算 |
| `core:database` | Room 实体、DAO、显式迁移及历史 schema |
| `core:files` | 私有文件、导入、repository 实现、备份恢复、笔记导出 |
| `core:pdf` | PdfRenderer、PDFBox 文字层及局部截图 |
| `core:ai` | HTTPS 接口、Key 存储、请求构造、SSE 与用量解析 |
| `core:markdown` | 转义、Markdown、离线 KaTeX 与受限 WebView |
| `core:designsystem` | 公共主题与界面基础样式 |
| `build-logic` | Android Library convention plugin |

Room 是文档、标注、问答与卡片的持久事实来源，DataStore 保存设置。页面位图是可丢弃的内存缓存，不是文档数据。

## 阅读与导入

使用系统 Storage Access Framework 选择资料，再复制至私有目录。导入计算内容 hash 以去重，校验页面后提交元数据。系统 PdfRenderer 显示 PDF；PDFBox-Android 提取文字坐标，两者分工不同。

圈选从原文件生成局部图片后落盘，并保存归一化坐标。渲染、复制和解码不放在主线程；位图缓存有上限，高倍率仍是有界整页渲染，没有可见区域 tile 渲染。

## 问答与资料

根来源标记、对话和消息分别存储。补充来源指向根来源，多个页面标记共用同一个对话。数据结构不会通过窗口是否展开来决定归属。

发送先保存问题与待生成回答，流式内容节流写入数据库。停止、失败和截断保留已收到的内容；重开时把残留生成状态转为停止。上下文装配从持久历史生成，不能只靠界面临时缓冲。

具体的最近 4 轮保护、摘要覆盖及服务商缓存见 [harness.md](harness.md)。

## 备份与迁移

数据库 schema 11，所有历史 schema 文件保留，启动时注册显式迁移，不使用 destructive migration。备份格式 4，恢复重映射文档、根 / 补充来源、消息和卡片的引用关系，Key 不进入备份。

技术核对和发布限制见 [release.md](release.md)。早期工程目录和内部包名保留为 `yuejian`，应用名称为 FoliKeep。
