package com.yuejian.ai

import com.yuejian.model.FinishReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSseTest {

    @Test
    fun parsesThinkingAndAnswerDeltas() {
        val thinking = ChatSse.parse("""{"choices":[{"delta":{"reasoning_content":"先想一步，"}}]}""")
        assertEquals("先想一步，", thinking.thinking)
        assertEquals("", thinking.answer)

        val answer = ChatSse.parse("""{"choices":[{"delta":{"content":"正文增量"}}]}""")
        assertEquals("正文增量", answer.answer)
        assertEquals("", answer.thinking)
    }

    @Test
    fun supportsReasoningFieldAlias() {
        val chunk = ChatSse.parse("""{"choices":[{"delta":{"reasoning":"别名字段"}}]}""")
        assertEquals("别名字段", chunk.thinking)
    }

    @Test
    fun parsesUsageInTailChunkWithAndWithoutChoices() {
        val tail = ChatSse.parse("""{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"prompt_cache_hit_tokens":4}}""")
        assertNotNull(tail.usage)
        assertEquals(10L, tail.usage!!.input)
        assertEquals(5L, tail.usage!!.output)
        assertEquals(4L, tail.usage!!.cached)

        val openAiStyle = ChatSse.parse("""{"usage":{"prompt_tokens":8,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":3},"completion_tokens_details":{"reasoning_tokens":7}}}""")
        assertEquals(3L, openAiStyle.usage!!.cached)
        assertEquals(7L, openAiStyle.usage!!.reasoning)

        val none = ChatSse.parse("""{"choices":[{"delta":{"content":"x"}}]}""")
        assertNull(none.usage)
    }

    @Test
    fun parsesMimoReasoningAndUsageTail() {
        val thinking = ChatSse.parse("""{"choices":[{"delta":{"reasoning_content":"先分析"},"finish_reason":null}],"usage":null}""")
        assertEquals("先分析", thinking.thinking)
        val tail = ChatSse.parse("""{"choices":[],"usage":{"prompt_tokens":61,"completion_tokens":467,"completion_tokens_details":{"reasoning_tokens":29},"prompt_tokens_details":{"cached_tokens":12}}}""")
        assertEquals(61L, tail.usage?.input)
        assertEquals(467L, tail.usage?.output)
        assertEquals(29L, tail.usage?.reasoning)
        assertEquals(12L, tail.usage?.cached)
    }

    @Test
    fun mapsFinishReasons() {
        assertEquals(FinishReason.Stop, ChatSse.parse("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""").finish)
        assertEquals(FinishReason.Length, ChatSse.parse("""{"choices":[{"delta":{},"finish_reason":"length"}]}""").finish)
        assertEquals(FinishReason.Filtered, ChatSse.parse("""{"choices":[{"delta":{},"finish_reason":"content_filter"}]}""").finish)
        assertNull(ChatSse.parse("""{"choices":[{"delta":{"content":"未结束"}}]}""").finish)
    }

    @Test
    fun handlesDoneEmptyAndBrokenChunks() {
        assertTrue(ChatSse.parse("[DONE]").answer.isEmpty() && ChatSse.parse("[DONE]").finish == null)
        assertTrue(ChatSse.parse("").answer.isEmpty())
        assertNotNull(ChatSse.parse("{not json").error)
        assertNotNull(ChatSse.parse("""{"error":{"message":"boom"}}""").error)
    }

    @Test
    fun handlesNonStreamMessageShape() {
        val chunk = ChatSse.parse("""{"choices":[{"message":{"content":"完整回答","reasoning_content":"思考过"},"finish_reason":"stop"}]}""")
        assertEquals("完整回答", chunk.answer)
        assertEquals("思考过", chunk.thinking)
        assertEquals(FinishReason.Stop, chunk.finish)
    }
}
