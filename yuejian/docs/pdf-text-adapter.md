# PDF 文字层适配器决策（测试版本）

采用 com.tom-roush:pdfbox-android:2.0.27.0，仅在 core/pdf 内提取字符和坐标；显示仍使用平台 PdfRenderer，图片继续原生解码。未引入旧业务代码。

官方项目：https://github.com/TomRoush/PdfBox-Android
许可证：Apache-2.0。上游 LICENSE.txt 与 NOTICE.txt 已原样加入 core/pdf/src/main/assets/licenses/pdfbox-android，随 APK 分发。

该适配器不依赖新增 native ABI；AAR 约 3.1 MiB，APK 实际增长还包括问答模块及网络依赖。上游版本较旧，未将其视为完成生产级维护性和安全审查。文档使用临时文件缓存；同一解析实例的提取与关闭通过锁串行化，文字页缓存最多 8 页、每页最多 30000 字形。

仅对有文字层的 PDF 提供长按选字；扫描页使用局部图片问答，不假装已完成 OCR。密码 PDF 仍不支持。中文字体映射、竖排、多栏阅读顺序、页面旋转和坐标精度尚待设备样本验证；ARM64 真机及性能同样未验收。
