package com.yuejian.markdown

/**
 * 面向 AI 回答的 Markdown + LaTeX 渲染器。
 *
 * 设计约束：
 * 1. 这是一个「逐行块级扫描 + 逐字符行内扫描」的解析器，不是几条正则做全量替换。
 *    代码块、转义字符、数学分隔符有明确的优先级，互不干扰。
 * 2. 模型输出被当作不可信内容：所有文本都经过 HTML 转义，解析器自身只产出白名单标签，
 *    不生成 `<img>`，链接只允许 http/https/mailto。
 * 3. 流式安全：未闭合的公式、未闭合的代码围栏会被当作普通文本处理，
 *    下一段内容补齐后自然重新渲染为公式/代码块；任何解析异常都不会让整条回答变空。
 * 4. 不全局替换反斜杠：`\(x\)` 之外的 `\\(` 不会被二次反转义，
 *    `$` 在没有成对闭合时就是普通美元符号（金额场景）。
 */
object YuejianMarkdown {

    /** 把模型返回的原始 Markdown/LaTeX 渲染为安全的 HTML 片段。 */
    fun render(source: String): String {
        if (source.isEmpty()) return ""
        return try {
            BlockRenderer(source).renderDocument()
        } catch (t: Throwable) {
            // 兜底：解析整体失败时也保留原文，绝不返回空作答
            "<p class=\"yj-p\">" + escapeHtml(source).replace("\n", "<br />") + "</p>"
        }
    }
}

// region 基础工具

internal fun escapeHtml(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\'' -> sb.append("&#39;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

/** Markdown 可转义字符（ASCII 标点），其余字符前的反斜杠保持原样。 */
private fun isEscapable(c: Char): Boolean =
    (c in '!'..'/') || (c in ':'..'@') || (c in '['..'`') || (c in '{'..'~')

/**
 * 查找 token，跳过被反斜杠转义的位置，避免 `\*`、`\$` 等被误判为语法。
 * 只适用于不带反斜杠的 token（`*`、`_`、`~~`、反引号）。
 */
private fun indexOfToken(s: String, token: String, from: Int, limit: Int = s.length): Int {
    var i = from
    val end = minOf(s.length, limit)
    while (i < end) {
        if (s[i] == '\\') {
            i += 2
            continue
        }
        if (s.startsWith(token, i)) return i
        i++
    }
    return -1
}

/**
 * 原样查找 token，不做转义跳过。
 * 用于本身就以反斜杠开头的数学分隔符（`\)`、`\]`）以及 `$$`：
 * 公式正文里天然大量出现反斜杠，若按转义规则跳过就会找不到闭合位置。
 */
private fun indexOfMathClose(s: String, token: String, from: Int, limit: Int = s.length): Int {
    val end = minOf(s.length, limit)
    if (from >= end) return -1
    val found = s.indexOf(token, from)
    return if (found < 0 || found >= end) -1 else found
}

private fun isCjk(c: Char): Boolean = when (c) {
    in '\u3000'..'\u303f' -> true      // 中文标点
    in '\u3400'..'\u4dbf' -> true      // 扩展 A
    in '\u4e00'..'\u9fff' -> true      // 常用汉字
    in '\uf900'..'\ufaff' -> true      // 兼容汉字
    in '\uff00'..'\uffef' -> true      // 全角字符
    in '\u3040'..'\u30ff' -> true      // 日文假名
    else -> false
}

private val EMPHASIS_LIMIT = 1200
private val INLINE_MATH_LIMIT = 240
private val DISPLAY_MATH_LIMIT = 4000

private const val P = "<p class=\"yj-p\">"

/** 硬换行哨兵：包含 NUL，模型输出不可能产生；解析后统一替换为 <br />。 */
private val BREAK_TOKEN = "\u0000yjbr\u0000"

private fun mathSpan(tex: String, display: Boolean): String =
    "<span class=\"yj-math\" data-display=\"" + (if (display) 1 else 0) + "\">" +
        escapeHtml(tex) + "</span>"

/** 用 span 承载块级公式并强制 display:block，避免把 <div> 塞进 <p> 导致浏览器补标签。 */
private fun mathBlock(tex: String): String =
    "<span class=\"yj-scroller yj-math-block\">" + mathSpan(tex, true) + "</span>"

// endregion

// region 行内渲染

internal fun renderInline(src: String, depth: Int = 0): String {
    if (src.isEmpty()) return ""
    if (depth > 10) return escapeHtml(src)
    val out = StringBuilder(src.length + 32)
    val plain = StringBuilder()
    val n = src.length
    var i = 0

    fun flush() {
        if (plain.isNotEmpty()) {
            out.append(escapeHtml(plain.toString()))
            plain.setLength(0)
        }
    }
    fun literal(text: String) = plain.append(text)

    while (i < n) {
        val c = src[i]
        when {
            c == '\\' && i + 1 < n -> when (val nx = src[i + 1]) {
                '(' -> {
                    val end = indexOfMathClose(src, "\\)", i + 2, minOf(n, i + 2 + DISPLAY_MATH_LIMIT))
                    if (end > i + 2) {
                        flush()
                        out.append(mathSpan(src.substring(i + 2, end), false))
                        i = end + 2
                    } else {
                        // 流式：还没收到 \)，先当普通文本
                        literal("\\(")
                        i += 2
                    }
                }
                '[' -> {
                    val end = indexOfMathClose(src, "\\]", i + 2, minOf(n, i + 2 + DISPLAY_MATH_LIMIT))
                    if (end > i + 2) {
                        flush()
                        out.append(mathBlock(src.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        literal("\\[")
                        i += 2
                    }
                }
                else -> {
                    if (isEscapable(nx)) {
                        literal(nx.toString())
                        i += 2
                    } else {
                        literal("\\")
                        i += 1
                    }
                }
            }

            c == '`' -> {
                var fence = 0
                while (i + fence < n && src[i + fence] == '`') fence++
                if (fence > 24) {
                    literal("`".repeat(fence))
                    i += fence
                    continue
                }
                val close = indexOfToken(src, "`".repeat(fence), i + fence)
                if (close < 0) {
                    // 流式：反引号未闭合，当作普通文本
                    literal("`".repeat(fence))
                    i += fence
                    continue
                }
                val raw = src.substring(i + fence, close)
                flush()
                out.append("<code class=\"yj-code\">").append(escapeHtml(trimCodeSpan(raw))).append("</code>")
                i = close + fence
            }

            c == '$' -> {
                if (i + 1 < n && src[i + 1] == '$') {
                    val end = indexOfMathClose(src, "$$", i + 2, minOf(n, i + 2 + DISPLAY_MATH_LIMIT))
                    val okRun = end > i + 2
                    if (okRun && src.substring(i + 2, end).isNotBlank()) {
                        flush()
                        out.append(mathBlock(src.substring(i + 2, end)))
                        i = end + 2
                    } else {
                        literal("$$")
                        i += 2
                    }
                } else {
                    val end = findInlineDollar(src, i + 1)
                    if (end > i + 1) {
                        flush()
                        out.append(mathSpan(src.substring(i + 1, end), false))
                        i = end + 1
                    } else {
                        literal("$")
                        i += 1
                    }
                }
            }

            c == '<' -> {
                // 不解析原生 HTML：`<` 一律转义，模型内容无法注入标签
                literal("<")
                i += 1
            }

            c == '[' || c == '!' -> {
                val open = if (c == '!') i + 1 else i
                val isImage = c == '!'
                val close = if (open < n && src[open] == '[') findMatchingBracket(src, open) else -1
                val target = if (close > 0) parseDestination(src, close + 1) else null
                if (close > 0 && target != null) {
                    val label = src.substring(open + 1, close)
                    if (isImage) {
                        // 不加载任何图片，只保留替代文字
                        flush()
                        out.append(renderInline(label, depth + 1))
                    } else {
                        val url = safeUrl(target.first)
                        if (url != null) {
                            flush()
                            out.append("<a class=\"yj-a\" href=\"").append(escapeHtml(url)).append("\">")
                            out.append(renderInline(label, depth + 1))
                            out.append("</a>")
                        } else {
                            literal(src.substring(i, kotlin.math.min(n, target.second)))
                        }
                    }
                    i = target.second
                } else {
                    literal(c.toString())
                    i += 1
                }
            }

            c == '*' || c == '_' || c == '~' -> {
                var run = 0
                while (i + run < n && src[i + run] == c) run++
                run = minOf(run, 3)
                val handled = when {
                    c == '~' && run >= 2 -> {
                        val end = indexOfToken(src, "~~", i + 2, minOf(n, i + 2 + EMPHASIS_LIMIT))
                        if (end > i + 2) {
                            flush()
                            out.append("<del>").append(renderInline(src.substring(i + 2, end), depth + 1)).append("</del>")
                            end + 2
                        } else {
                            literal(src.substring(i, i + run))
                            i + run
                        }
                    }
                    run >= 2 -> {
                        val end = indexOfToken(src, c.toString() + c, i + 2, minOf(n, i + 2 + EMPHASIS_LIMIT))
                        val firstOk = i + 2 < n && src[i + 2] != ' ' && src[i + 2] != '\n'
                        if (end > i + 2 && firstOk) {
                            flush()
                            out.append("<strong>").append(renderInline(src.substring(i + 2, end), depth + 1)).append("</strong>")
                            end + 2
                        } else {
                            literal(src.substring(i, i + run))
                            i + run
                        }
                    }
                    c == '*' -> {
                        val end = indexOfToken(src, "*", i + 1, minOf(n, i + 1 + EMPHASIS_LIMIT))
                        val firstOk = i + 1 < n && src[i + 1] != ' ' && src[i + 1] != '\n'
                        if (end > i + 1 && firstOk) {
                            flush()
                            out.append("<em>").append(renderInline(src.substring(i + 1, end), depth + 1)).append("</em>")
                            end + 1
                        } else {
                            literal("*")
                            i + 1
                        }
                    }
                    c == '_' -> {
                        val prev = if (i > 0) src[i - 1] else ' '
                        val end = indexOfToken(src, "_", i + 1, minOf(n, i + 1 + EMPHASIS_LIMIT))
                        val openerOk = !prev.isLetterOrDigit() && prev != '_'
                        val firstOk = i + 1 < n && src[i + 1] != ' ' && src[i + 1] != '\n'
                        val closerOk = end > i + 1 && (end + 1 >= n || !src[end + 1].isLetterOrDigit())
                        if (openerOk && firstOk && closerOk) {
                            flush()
                            out.append("<em>").append(renderInline(src.substring(i + 1, end), depth + 1)).append("</em>")
                            end + 1
                        } else {
                            literal("_")
                            i + 1
                        }
                    }
                    else -> {
                        literal("~")
                        i + 1
                    }
                }
                i = handled
            }

            else -> {
                plain.append(c)
                i += 1
            }
        }
    }
    flush()
    return out.toString()
}

/** 围栏代码内容：` `foo` ` 形式去掉首尾各一个空格。 */
private fun trimCodeSpan(raw: String): String {
    if (raw.length >= 2 && raw.startsWith(" ") && raw.endsWith(" ") && raw.trim().isNotEmpty() && raw.first() != '\n') {
        return raw.substring(1, raw.length - 1)
    }
    return raw
}

/**
 * 行内 `$...$` 的闭合查找。遵循 Pandoc 风格的约束，避免金额被误判为公式：
 * 起始字符不能是空白；闭合 `$` 前一个字符不能是空白，其后不能紧跟数字；不能跨行；长度有限。
 */
private fun findInlineDollar(s: String, start: Int): Int {
    if (start >= s.length) return -1
    val first = s[start]
    if (first == ' ' || first == '\t' || first == '\n') return -1
    val limit = minOf(s.length, start + INLINE_MATH_LIMIT)
    var i = start
    while (i < limit) {
        when (s[i]) {
            '\\' -> i += 2
            '\n' -> return -1
            '$' -> {
                val prev = s.getOrNull(i - 1)
                val next = s.getOrNull(i + 1)
                val prevOk = prev != null && prev != ' ' && prev != '\t' && prev != '$' && prev != '\n'
                val nextOk = next == null || next !in '0'..'9'
                return if (prevOk && nextOk) i else -1
            }
            else -> i += 1
        }
    }
    return -1
}

/** 找到与 `[` 配对的 `]`，支持一层嵌套。 */
private fun findMatchingBracket(s: String, open: Int): Int {
    var depth = 0
    var i = open
    while (i < s.length) {
        when (s[i]) {
            '\\' -> i += 2
            '[' -> {
                depth++
                i++
            }
            ']' -> {
                depth--
                if (depth == 0) {
                    if (i > open + 1 && i - open <= 800) return i
                    return -1
                }
                i++
            }
            '\n' -> return -1
            else -> i++
        }
    }
    return -1
}

/** 解析 `(url "title")` 部分，返回 (url, 结束下标)。 */
private fun parseDestination(s: String, from: Int): Pair<String, Int>? {
    if (from >= s.length || s[from] != '(') return null
    val end = indexOfToken(s, ")", from + 1, minOf(s.length, from + 1 + 2048))
    if (end < 0) return null
    var inner = s.substring(from + 1, end).trim()
    val space = inner.indexOf(' ')
    if (space > 0 && inner.substring(space).trim().let { it.startsWith("\"") || it.startsWith("'") }) {
        inner = inner.substring(0, space).trim()
    }
    return inner to end + 1
}

/** 只允许 http/https/mailto，其余（javascript:、data:、file: 等）一律返回 null。 */
private fun safeUrl(raw: String): String? {
    val url = buildString {
        for (ch in raw.trim()) {
            if (ch.code < 0x20 || ch == '\u007f') continue
            if (ch.isWhitespace()) continue
            append(ch)
        }
    }
    if (url.isEmpty() || url.length > 2048) return null
    return when {
        url.startsWith("https://", ignoreCase = true) -> url
        url.startsWith("http://", ignoreCase = true) -> url
        url.startsWith("mailto:", ignoreCase = true) -> url
        else -> null
    }
}

// endregion

// region 块级渲染

private class Marker(val indent: Int, val contentCol: Int, val ordered: Boolean, val number: Int)

private class BlockRenderer(source: String) {
    private val lines: List<String> =
        source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    private var pos = 0

    private fun has() = pos < lines.size
    private fun peek(ahead: Int = 0): String = if (pos + ahead < lines.size) lines[pos + ahead] else ""
    private fun take(): String = lines[pos].also { pos++ }

    fun renderDocument(): String = renderLevel()

    private fun renderLevel(): String {
        val out = StringBuilder()
        while (has()) {
            if (peek().isBlank()) {
                pos++
                continue
            }
            renderBlock(out)
        }
        return out.toString()
    }

    private fun renderBlock(out: StringBuilder) {
        val line = peek()
        when {
            isFence(line) -> out.append(parseFence())
            isMathStart(line) -> {
                val block = parseMathBlock()
                if (block != null) {
                    out.append(block)
                } else {
                    // 未闭合：保持原文由段落流程输出
                    out.append(parseParagraph())
                }
            }
            isHeading(line) -> out.append(parseHeading())
            isHr(line) -> {
                pos++
                out.append("<hr class=\"yj-hr\" />")
            }
            isTableStart() -> out.append(parseTable())
            listMarker(line) != null -> out.append(parseList())
            line.trimStart().startsWith(">") -> out.append(parseQuote())
            else -> out.append(parseParagraph())
        }
    }

    private fun subRender(text: String): String = BlockRenderer(text).renderLevel()

    private fun indentOf(s: String): Int {
        var i = 0
        var columns = 0
        while (i < s.length) {
            when (s[i]) {
                ' ' -> {
                    columns++
                    i++
                }
                '\t' -> {
                    columns += 4 - (columns % 4)
                    i++
                }
                else -> return columns
            }
        }
        return columns
    }

    private fun startsBlock(line: String): Boolean =
        isFence(line) || isHeading(line) || isHr(line) ||
            line.trimStart().startsWith(">") || listMarker(line) != null || isMathStart(line)

    // 代码块

    private fun isFence(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("```") || t.startsWith("~~~")
    }

    private fun parseFence(): String {
        val head = take()
        val marker = head.trimStart()
        val fence = marker.substring(0, 3)
        val info = marker.removePrefix(fence).trim()
        val body = StringBuilder()
        while (has()) {
            val line = peek()
            if (line.trimStart().startsWith(fence)) {
                pos++
                break
            }
            body.append(line).append('\n')
            pos++
        }
        var code = body.toString()
        if (code.endsWith("\n")) code = code.dropLast(1)
        val langAttr = if (info.isNotBlank() && info.length <= 24 &&
            info.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '+' || it == '#' || it == '.' }
        ) {
            " data-lang=\"" + escapeHtml(info) + "\""
        } else {
            ""
        }
        return "<div class=\"yj-scroller\"><pre class=\"yj-pre\"$langAttr>" +
            "<code class=\"yj-code-block\">" + escapeHtml(code) + "</code></pre></div>"
    }

    // 数学块

    private fun isMathStart(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("\\[") || t.startsWith("$$")
    }

    /** 返回 null 表示尚未闭合（流式），调用方应按普通文本处理。 */
    private fun parseMathBlock(): String? {
        val start = pos
        val firstLine = peek()
        val kind = when {
            firstLine.trimStart().startsWith("$$") -> 1
            firstLine.trimStart().startsWith("\\[") -> 2
            else -> return null
        }
        val openToken = if (kind == 1) "$$" else "\\["
        val closeToken = if (kind == 1) "$$" else "\\]"
        val joined = StringBuilder()
        var i = start
        var closingLine = -1
        while (i < lines.size && i - start < 200) {
            val line = lines[i]
            joined.append(line).append('\n')
            val searchFrom = if (i == start) line.indexOf(openToken) + openToken.length else 0
            val closeAt = line.indexOf(closeToken, searchFrom)
            if (closeAt >= 0) {
                closingLine = i
                break
            }
            if (line.isBlank() && i > start) {
                // 公式里出现空行，通常意味着公式没有闭合
                return null
            }
            i++
        }
        if (closingLine < 0) return null
        val text = joined.toString()
        val afterOpen = text.indexOf(openToken)
        if (afterOpen < 0) return null
        val contentStart = afterOpen + openToken.length
        val closeAt = text.indexOf(closeToken, contentStart)
        if (closeAt < 0) return null
        val tex = text.substring(contentStart, closeAt)
        pos = closingLine + 1
        return if (tex.isBlank()) null else mathBlock(tex.trim())
    }

    // 标题 / 分隔线

    private fun isHeading(line: String): Boolean {
        val t = line.trimStart()
        if (!t.startsWith("#")) return false
        var level = 0
        while (level < t.length && t[level] == '#') level++
        return level in 1..6 && (level == t.length || t[level] == ' ' || t[level] == '\t')
    }

    private fun parseHeading(): String {
        val raw = take()
        val t = raw.trimStart()
        var level = 0
        while (level < t.length && t[level] == '#') level++
        var text = t.substring(level).trim()
        if (Regex(".*\\s#+$").matches(text)) {
            text = text.replace(Regex("\\s#+$"), "").trim()
        }
        val clamped = level.coerceIn(1, 6)
        return "<h$clamped class=\"yj-h$clamped\">" + renderInline(text) + "</h$clamped>"
    }

    private fun isHr(line: String): Boolean {
        val t = line.trim()
        if (t.length < 3) return false
        val c = t[0]
        if (c != '-' && c != '*' && c != '_') return false
        return t.all { it == c || it == ' ' || it == '\t' }
    }

    // 引用

    private fun parseQuote(): String {
        val inner = StringBuilder()
        while (has()) {
            val line = peek()
            val trimmed = line.trimStart()
            if (trimmed.startsWith(">")) {
                var body = trimmed.substring(1)
                if (body.startsWith(" ")) body = body.substring(1)
                inner.append(body).append('\n')
                pos++
            } else if (line.isBlank()) {
                if (peek(1).trimStart().startsWith(">")) {
                    inner.append('\n')
                    pos++
                } else {
                    break
                }
            } else {
                break
            }
        }
        return "<blockquote class=\"yj-quote\">" + subRender(inner.toString()) + "</blockquote>"
    }

    // 列表

    private fun listMarker(line: String): Marker? {
        val indent = indentOf(line)
        if (indent > 48 || indent >= line.length) return null
        val rest = line.substring(indent)
        if (rest.isEmpty()) return null
        val bullet = rest[0]
        if ((bullet == '-' || bullet == '*' || bullet == '+') &&
            (rest.length == 1 || rest[1] == ' ' || rest[1] == '\t')
        ) {
            return Marker(indent, firstContentColumn(line, indent + 2), false, 1)
        }
        var digits = 0
        while (digits < rest.length && rest[digits].isDigit()) digits++
        if (digits in 1..9 && digits + 1 < rest.length &&
            (rest[digits] == '.' || rest[digits] == ')') && (rest[digits + 1] == ' ' || rest[digits + 1] == '\t')
        ) {
            val number = rest.substring(0, digits).toIntOrNull() ?: 1
            return Marker(indent, firstContentColumn(line, indent + digits + 2), true, number)
        }
        return null
    }

    private fun firstContentColumn(line: String, from: Int): Int {
        var col = from
        while (col < line.length && line[col] == ' ') col++
        return col
    }

    private fun parseList(): String {
        val first = listMarker(peek()) ?: return ""
        val ordered = first.ordered
        val startNumber = first.number
        val baseIndent = first.indent
        val contentCol = first.contentCol
        val items = ArrayList<StringBuilder>()
        var loose = false

        while (has()) {
            val line = peek()
            if (line.isBlank()) {
                val next = peek(1)
                val nextMarker = if (next.isBlank()) null else listMarker(next)
                if (nextMarker != null && nextMarker.ordered == ordered && nextMarker.indent <= baseIndent + 1) {
                    loose = true
                    pos++
                    continue
                }
                if (!next.isBlank() && (indentOf(next) >= contentCol || listMarker(next) != null)) {
                    items.lastOrNull()?.append('\n')
                    pos++
                    continue
                }
                break
            }
            val marker = listMarker(line)
            if (marker != null && marker.ordered == ordered && marker.indent <= baseIndent + 1) {
                pos++
                val sb = StringBuilder()
                val rest = line.substring(minOf(line.length, marker.contentCol))
                if (rest.isNotBlank()) sb.append(rest).append('\n')
                items.add(sb)
                continue
            }
            if (indentOf(line) >= contentCol) {
                items.lastOrNull()?.append(stripColumns(line, contentCol))?.append('\n')
                pos++
                continue
            }
            if (items.isNotEmpty() && !startsBlock(line)) {
                items.lastOrNull()?.append(line.trimStart())?.append('\n')
                pos++
                continue
            }
            break
        }

        val tag = if (ordered) "ol" else "ul"
        val startAttr = if (ordered && startNumber != 1) " start=\"$startNumber\"" else ""
        val cls = "yj-$tag" + if (loose) " yj-list-loose" else ""
        val body = items.joinToString("") { sb ->
            val html = subRender(sb.toString())
            "<li class=\"yj-li\">" + unwrapSoleParagraph(html) + "</li>"
        }
        return "<$tag class=\"$cls\"$startAttr>$body</$tag>"
    }

    private fun stripColumns(line: String, columns: Int): String {
        var remaining = columns
        var i = 0
        while (i < line.length && remaining > 0) {
            when (line[i]) {
                ' ' -> {
                    remaining--
                    i++
                }
                '\t' -> {
                    val width = 4 - ((columns - remaining) % 4)
                    if (width > remaining) break
                    remaining -= width
                    i++
                }
                else -> break
            }
        }
        return line.substring(i)
    }

    private fun unwrapSoleParagraph(html: String): String {
        if (!html.startsWith(P)) return html
        val close = html.indexOf("</p>", P.length)
        val inner = html.substring(P.length, close)
        if (close < 0 || close + 4 != html.length || inner.contains(P)) return html
        return inner
    }

    // 表格

    private fun isDelimiterCell(cell: String): Boolean {
        var s = cell
        if (s.startsWith(":")) s = s.drop(1)
        if (s.endsWith(":")) s = s.dropLast(1)
        return s.isNotEmpty() && s.all { it == '-' }
    }

    private fun isTableStart(): Boolean {
        val next = peek(1)
        if (next.isBlank()) return false
        val cells = splitCells(next.trim())
        return cells.isNotEmpty() && cells.all { isDelimiterCell(it) }
    }

    private fun parseTable(): String {
        val header = take().trim()
        val delimiter = take().trim()
        val aligns = splitCells(delimiter).map { cell ->
            when {
                cell.startsWith(":") && cell.endsWith(":") -> "center"
                cell.endsWith(":") -> "right"
                else -> "left"
            }
        }
        val headCells = splitCells(header)
        val out = StringBuilder("<div class=\"yj-scroller\"><table class=\"yj-table\"><thead><tr>")
        headCells.forEachIndexed { index, cell ->
            out.append("<th class=\"yj-th\" style=\"text-align:").append(aligns.getOrElse(index) { "left" })
                .append("\">").append(renderInline(cell)).append("</th>")
        }
        out.append("</tr></thead><tbody>")
        while (has()) {
            val line = peek()
            if (line.isBlank()) break
            val trimmed = line.trim()
            if (!trimmed.contains('|')) break
            pos++
            val cells = splitCells(trimmed)
            out.append("<tr>")
            cells.forEachIndexed { index, cell ->
                out.append("<td class=\"yj-td\" style=\"text-align:").append(aligns.getOrElse(index) { "left" })
                    .append("\">").append(renderInline(cell)).append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</tbody></table></div>")
        return out.toString()
    }

    /** 按顶层 `|` 切分行，尊重转义 `\|` 与代码围栏。 */
    private fun splitCells(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val cells = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '\\' -> {
                    if (i + 1 < s.length && s[i + 1] == '|') {
                        current.append('|')
                        i += 2
                    } else {
                        current.append(s[i])
                        i += 1
                    }
                }
                '`' -> {
                    val end = indexOfToken(s, "`", i + 1)
                    if (end < 0) {
                        current.append(s[i])
                        i += 1
                    } else {
                        current.append(s, i, end + 1)
                        i = end + 1
                    }
                }
                '|' -> {
                    cells.add(current.toString().trim())
                    current.setLength(0)
                    i += 1
                }
                else -> {
                    current.append(s[i])
                    i += 1
                }
            }
        }
        cells.add(current.toString().trim())
        return cells
    }

    // 段落

    private fun parseParagraph(): String {
        val rawLines = ArrayList<String>()
        val hardBreaks = ArrayList<Boolean>()
        while (has()) {
            val raw = peek()
            if (raw.isBlank()) break
            // 流式场景：行首在段落中途出现的 `$$` / `\[` 若还没闭合，先当作同一段落的续行，
            // 避免把未完成的公式拦腰截断成两段
            if (rawLines.isNotEmpty() && startsBlock(raw) &&
                !hasUnclosedMath(rawLines.joinToString(""))
            ) break
            pos++
            val trimmedEnd = raw.trimEnd()
            hardBreaks.add(trimmedEnd.endsWith("\\") || (trimmedEnd.length + 2 <= raw.length))
            rawLines.add(raw.trim())
        }
        if (rawLines.isEmpty()) {
            // 理论上不会发生，防止外层循环卡死
            rawLines.add(take())
            hardBreaks.add(false)
        }
        // 先把整段拼成一个字符串再做行内解析，否则跨行的行内代码、
        // 行内强调以及 "行尾 $$ ... 另起一行 $$" 的公式都会配不上对。
        // 硬换行用不可能出现在输入里的哨兵占位，解析完成后替换成 <br />。
        val joined = StringBuilder()
        rawLines.forEachIndexed { index, line ->
            if (index > 0) {
                joined.append(if (hardBreaks[index - 1]) BREAK_TOKEN else softBreak(rawLines[index - 1], line))
            }
            joined.append(line)
        }
        val html = renderInline(joined.toString()).replace(BREAK_TOKEN, "<br />")
        return P + html + "</p>"
    }

    /** 段落已开头但公式还没闭合时，允许后续行继续并入同一段落（流式半成品场景）。 */
    private fun hasUnclosedMath(text: String): Boolean {
        var dollars = 0
        var idx = text.indexOf("$$")
        while (idx >= 0) {
            dollars++
            idx = text.indexOf("$$", idx + 2)
        }
        val opened = text.split("\\[").size - 1
        val closed = text.split("\\]").size - 1
        return dollars % 2 == 1 || opened > closed
    }

    /** 中日韩字符之间的软换行不插入空格，其余按 Markdown 惯例补一个空格。 */
    private fun softBreak(prev: String, next: String): String {
        val before = prev.lastOrNull()
        val after = next.firstOrNull()
        if (before == null || after == null) return " "
        return if (isCjk(before) || isCjk(after)) "" else " "
    }
}

// endregion
