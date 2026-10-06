package com.yuejian.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 Markdown/公式解析的关键用例：代码块与转义优先级、流式半成品、不可信内容。
 * 纯 JVM 单元测试，不涉及 WebView。
 */
class MarkdownParserTest {

    private fun render(src: String) = YuejianMarkdown.render(src)

    @Test fun inlineFormulaIsHandedToRenderer() {
        val html = render("质能方程 \\( E=mc^2 \\) 说明了质量与能量的关系。")
        assertTrue(html.contains("class=\"yj-math\" data-display=\"0\""))
        assertTrue(html.contains("E=mc^2"))
    }

    @Test fun displayFormulaUsesBlockWrapper() {
        val html = render("\\[ \\frac{a}{b} + \\sqrt{c} \\]")
        assertTrue(html.contains("yj-math-block"))
        assertTrue(html.contains("data-display=\"1\""))
    }

    @Test fun dollarDelimitersAreStillSupported() {
        assertTrue(render("\$x^2 + y^2 = z^2$").contains("class=\"yj-math\" data-display=\"0\""))
        assertTrue(render("$$\\int_0^1 x\\,dx = \\frac{1}{2}$$").contains("data-display=\"1\""))
    }

    @Test fun moneyIsNotTreatedAsFormula() {
        val html = render("这本书 $5，那本书 $10，一共 $15。")
        assertFalse("金额不应被识别为公式", html.contains("yj-math"))
        assertTrue(html.contains("$5"))
    }

    @Test fun codeBlockKeepsDollarsAndBackslashes() {
        val html = render("```\nval price = \"\\$5\"\nval tex = \"\\\\frac{a}{b}\"\n```")
        assertTrue(html.contains("<pre class=\"yj-pre\""))
        assertFalse("代码块内不得出现公式占位", html.contains("yj-math"))
        assertTrue(html.contains("\$5"))
    }

    @Test fun inlineCodeKeepsFormulaText() {
        val html = render("行内代码 `\\( a + b \\)` 保持原样。")
        assertTrue(html.contains("<code class=\"yj-code\">"))
        assertFalse(html.contains("yj-math"))
    }

    @Test fun backslashEscapesAreHandled() {
        val html = render("转义的美元 \\$5 不应开启公式。")
        assertFalse(html.contains("yj-math"))
        assertTrue(html.contains("$5"))
    }

    @Test fun rawHtmlIsEscaped() {
        val html = render("<script>alert('x')</script> 与 <img src=\"http://evil/x.png\">")
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test fun imagesAreDroppedButAltTextKept() {
        val html = render("示意图：![摘录原图](http://example.com/a.png) 结束。")
        assertFalse(html.contains("<img"))
        assertTrue(html.contains("摘录原图"))
    }

    @Test fun unsafeLinkSchemesAreRemoved() {
        assertFalse(render("[点我](javascript:alert(1))").contains("<a class=\"yj-a\""))
        assertFalse(render("[点我](data:text/html;base64,AAA)").contains("<a class=\"yj-a\""))
        assertFalse(render("[点我](file:///sdcard/secret.txt)").contains("<a class=\"yj-a\""))
        assertTrue(render("[官网](https://example.com)").contains("href=\"https://example.com\""))
    }

    @Test fun tableBlockIsRendered() {
        val html = render("| 项目 | 数值 |\n| --- | ---: |\n| 速度 | 3.0 |")
        assertTrue(html.contains("<table class=\"yj-table\""))
        assertTrue(html.contains("<th class=\"yj-th\""))
        assertTrue(html.contains("数值"))
        assertTrue("右对齐列必须保留", html.contains("text-align:right"))
    }

    @Test fun multiLineEnvironmentsArePreserved() {
        val aligned = render("\\[\n\\begin{aligned}\na &= b + c \\\\\nc &= \\frac{d}{e}\n\\end{aligned}\n\\]")
        assertTrue(aligned.contains("begin{aligned}"))
        assertTrue(aligned.contains("end{aligned}"))
        assertTrue(aligned.contains("data-display=\"1\""))

        val matrix = render("\\[ A = \\begin{pmatrix} 1 & 2 \\\\ 3 & 4 \\end{pmatrix} \\]")
        assertTrue(matrix.contains("begin{pmatrix}"))
        assertTrue(matrix.contains("data-display=\"1\""))
    }

    @Test fun displayMathSpanningSoftBreaks() {
        val html = render("推导如下：$$\n\\frac{a}{b}\n$$")
        assertTrue("跨行输入的独立公式应当渲染成公式块", html.contains("yj-math-block"))
    }

    @Test fun listHeadingQuoteAndRule() {
        val html = render("## 小结\n\n- 第一点\n- 第二点\n  - 子项\n\n> 引用一句原文\n\n---\n")
        assertTrue(html.contains("<h2 class=\"yj-h2\">"))
        assertTrue(html.contains("<ul class=\"yj-ul\">"))
        assertTrue(html.contains("<li class=\"yj-li\">"))
        assertTrue(html.contains("<blockquote class=\"yj-quote\">"))
        assertTrue(html.contains("<hr class=\"yj-hr\""))
    }

    @Test fun nestedListInsideListItem() {
        val html = render("- 外层\n  - 内层")
        assertTrue(html.count { it == '<' } > 0)
        assertTrue(html.indexOf("<ul class=\"yj-ul\">") < html.indexOf("内层"))
        assertTrue(html.substringAfter("外层").contains("<ul class=\"yj-ul\">"))
    }

    @Test fun partialFormulaFallsBackToPlainText() {
        val partial = "推导如下：$$\n\\frac{"
        assertFalse("未闭合公式不得渲染", YuejianMarkdown.render(partial).contains("yj-math"))

        val complete = "推导如下：$$\n\\frac{a}{b}\n$$"
        assertTrue(YuejianMarkdown.render(complete).contains("yj-math-block"))
    }

    @Test fun partialFenceStillRendersAsCode() {
        val html = render("```kotlin\nval x = 1\n")
        assertTrue(html.contains("<pre class=\"yj-pre\""))
    }

    @Test fun partialInlineCodeIsPlainText() {
        val html = render("这里有一个未闭合的 `代码片段")
        assertFalse(html.contains("<code"))
    }

    @Test fun chineseInsideFormulaSurvives() {
        val html = render("\\( \\text{速度} = \\frac{s}{t} \\)")
        assertTrue(html.contains("速度"))
        assertTrue(html.contains("data-display=\"0\""))
    }

    @Test fun chineseSoftBreakDoesNotInsertSpace() {
        val html = render("第一行\n第二行")
        assertTrue(html.startsWith("<p class=\"yj-p\">"))
        assertTrue(html.contains("第一行第二行"))
    }

    @Test fun hardBreakUsesBr() {
        assertTrue(render("第一行  \n第二行").contains("<br />"))
    }

    @Test fun boldAndItalic() {
        val html = render("**重点** 与 *强调*")
        assertTrue(html.contains("<strong>重点</strong>"))
        assertTrue(html.contains("<em>强调</em>"))
    }

    @Test fun neverReturnsBlankForNonBlankInput() {
        val inputs = listOf("#", "|", "$$", "-", ">", "```", "\\\\", "[]((", "\\\\(", "**", "1.", "~~")
        inputs.forEach {
            val html = render(it)
            assertTrue("输入 [$it] 不应产出空内容", html.isNotEmpty())
        }
        assertEquals("全空白输入允许为空", "", render("\n\n\n"))
    }

    @Test fun chineseHelperBehaviour() {
        val rendered = render("你好，世界\nHello world")
        assertTrue(rendered.contains("Hello world"))
    }

    @Test fun styleCssIsSelfContained() {
        val css = AnswerStyle(baseSizePx = 17f).toCss()
        assertTrue(css.startsWith("html:root{"))
        assertTrue(css.contains("--yj-fs:17.0px;"))
        assertEquals("html:root{", css.substring(0, 10))
    }
}
