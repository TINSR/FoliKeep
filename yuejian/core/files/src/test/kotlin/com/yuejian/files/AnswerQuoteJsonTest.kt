package com.yuejian.files

import com.yuejian.model.AnswerQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerQuoteJsonTest {
    @Test fun roundTripsQuoteWithLatex() {
        val quote = AnswerQuote("m1", "c1", "选中文字 \\(\\frac{a}{b}\\) 结束", "\\frac{a}{b}")
        assertEquals(quote, AnswerQuoteJson.decode(AnswerQuoteJson.encode(quote)))
    }

    @Test fun roundTripsQuoteWithoutLatex() {
        val quote = AnswerQuote("m2", "c2", "plain text with \"quotes\" and \\n newline")
        assertEquals(quote, AnswerQuoteJson.decode(AnswerQuoteJson.encode(quote)))
    }

    @Test fun damagedOrMissingJsonYieldsNoQuote() {
        assertNull(AnswerQuoteJson.decode(null))
        assertNull(AnswerQuoteJson.decode(""))
        assertNull(AnswerQuoteJson.decode("{not json"))
        assertNull(AnswerQuoteJson.decode("{\"sourceMessageId\":\"m\"}"))
        assertNull(AnswerQuoteJson.decode("[]"))
    }
}
