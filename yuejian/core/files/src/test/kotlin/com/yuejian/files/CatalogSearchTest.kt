package com.yuejian.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSearchTest {
    @Test fun escapesLikeWildcardsAndBackslash() {
        // "50\%" → 反斜杠翻倍、% 转义 → %50\\\%（3 个反斜杠）%
        assertEquals("%50\\\\\\%%", likePattern("50\\%"))
        assertEquals("%a\\_b%", likePattern("a_b"))
        assertEquals("%a\\%b%", likePattern("a%b"))
        assertEquals("%ab%", likePattern("ab"))
    }

    @Test fun excerptCentersOnMatch() {
        val text = "前面一段普通的文字，这里出现目标词，后面还有很多内容要截掉。"
        val out = excerpt(text, "目标词", window = 6)
        assertTrue(out.contains("目标词"))
        assertTrue(out.startsWith("…"))
        assertTrue(out.endsWith("…"))
    }

    @Test fun excerptHandlesMissingMatchAndShortText() {
        assertEquals("短文本", excerpt("短文本", "不存在"))
        assertTrue(excerpt("x".repeat(200), "zzz").endsWith("…"))
    }
}
