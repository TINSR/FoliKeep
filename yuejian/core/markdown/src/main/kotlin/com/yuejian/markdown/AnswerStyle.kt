package com.yuejian.markdown

/**
 * 回答渲染所用的外观参数。宿主（Compose）从 MaterialTheme 取值后传入，
 * 渲染模块本身不依赖 material3，只把这些值翻译成局部 CSS 变量。
 *
 * 所有颜色必须是宿主生成的 ARGB 整数，模块内部只做 #rrggbb 输出，
 * 不会把任何来自模型的字符串拼进 CSS。
 */
data class AnswerStyle(
    val foreground: Int = 0xFF1B1B1B.toInt(),
    val muted: Int = 0xFF5F6368.toInt(),
    val accent: Int = 0xFF0B6BCB.toInt(),
    val line: Int = 0xFFD6D6D6.toInt(),
    val codeBackground: Int = 0xFFF3F4F6.toInt(),
    val quoteBar: Int = 0xFFCFCFCF.toInt(),
    /** 正文字号，单位 px（已含系统字体缩放）。 */
    val baseSizePx: Float = 15f
) {
    /** 用于判断是否需要重新注入样式，避免重复渲染。 */
    val styleKey: String get() = "$foreground|$muted|$accent|$line|$codeBackground|$quoteBar|$baseSizePx"

    /** 生成注入到页面里的 CSS 片段（纯宿主数据，无外部输入）。 */
    fun toCss(): String = buildString {
        // body 的正文颜色/字号也必须收到主题变量；更高优先级覆盖后加载的 answer.css 默认值。
        append("html:root{")
        append("--yj-fg:").append(css(foreground)).append(';')
        append("--yj-bg:rgba(0,0,0,0);")
        append("--yj-muted:").append(css(muted)).append(';')
        append("--yj-accent:").append(css(accent)).append(';')
        append("--yj-line:").append(css(line)).append(';')
        append("--yj-code-bg:").append(css(codeBackground)).append(';')
        append("--yj-quote-bar:").append(css(quoteBar)).append(';')
        append("--yj-fs:").append(baseSizePx).append("px;")
        append('}')
    }

    private fun css(argb: Int): String {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return "#" + hex(r) + hex(g) + hex(b)
    }

    private fun hex(v: Int): String {
        val s = v.toString(16)
        return if (s.length == 1) "0$s" else s
    }
}
