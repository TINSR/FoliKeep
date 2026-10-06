package com.yuejian.markdown

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归：主题变量必须到达 body，而不是只到达 body 的子容器。 */
class AnswerThemeTest {
    @Test fun themeReachesInheritedBodyStyles() {
        val assets=File("src/main/assets/markdown").absoluteFile
        val output=File("build/verification/answer-theme").apply { mkdirs() }
        val markdown="# 标题\n\n普通正文与 **粗体**。\n\n- 列表文字\n\n> 引用文字\n\n| 名称 | 数值 |\n| --- | --- |\n| 测试 | 1 |\n\n```text\ncode\n```\n\n\\( x^2 \\)"
        listOf(false,true).forEach { dark ->
            val style=AnswerStyle(foreground=if(dark)0xFFFFFFFF.toInt() else 0xFF000000.toInt(),
                muted=if(dark)0xFFBBBBBB.toInt() else 0xFF666666.toInt(),
                codeBackground=if(dark)0xFF191919.toInt() else 0xFFF2F2F2.toInt(),baseSizePx=18f)
            val css=style.toCss()
            assertTrue("The inherited body needs document-scoped theme variables",css.startsWith("html:root{"))
            // 与真实 answer.html 相同顺序：主题先加载，基础样式后加载，验证优先级。
            val page="""<!doctype html><html><head><meta charset="UTF-8">
                <style id="yj-theme">$css</style>
                <link rel="stylesheet" href="${File(assets,"katex/katex.min.css").toURI()}">
                <link rel="stylesheet" href="${File(assets,"answer.css").toURI()}">
                <script src="${File(assets,"katex/katex.min.js").toURI()}"></script>
                <script src="${File(assets,"render.js").toURI()}"></script>
                </head><body><div id="yj-root">${YuejianMarkdown.render(markdown)}</div>
                <script>
                    YuejianRender(btoa(unescape(encodeURIComponent(document.getElementById('yj-root').innerHTML))),
                        btoa(unescape(encodeURIComponent(document.getElementById('yj-theme').textContent))));
                    var expected=${if(dark)"'rgb(255, 255, 255)'" else "'rgb(0, 0, 0)'"};
                    var results=[];
                    ['body','#yj-root','.yj-p','.yj-h1','.yj-li','.yj-th','.yj-td','.yj-code-block','.katex'].forEach(function(selector){
                        var node=document.querySelector(selector);
                        results.push({selector:selector,color:node?getComputedStyle(node).color:'missing'});
                    });
                    var report=document.createElement('pre');report.id='theme-check';
                    report.textContent=JSON.stringify({pass:results.every(function(r){return r.color===expected;}) && getComputedStyle(document.body).fontSize==='18px',
                        fontSize:getComputedStyle(document.body).fontSize,results:results});document.body.appendChild(report);
                </script></body></html>""".trimIndent()
            File(output,if(dark)"dark.html" else "light.html").writeText(page)
        }
    }
}
