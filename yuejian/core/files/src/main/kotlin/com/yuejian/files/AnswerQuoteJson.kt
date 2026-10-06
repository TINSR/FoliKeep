package com.yuejian.files

import com.yuejian.model.AnswerQuote
import kotlinx.serialization.json.*

/**
 * AnswerQuote 的 JSON 编解码。
 * 解析失败一律返回 null（损坏或旧数据按“无引用”处理），不抛异常打断对话读取。
 */
object AnswerQuoteJson {
    fun encode(quote: AnswerQuote): String = buildJsonObject {
        put("sourceMessageId", quote.sourceMessageId)
        put("sourceConversationId", quote.sourceConversationId)
        put("textSnapshot", quote.textSnapshot)
        quote.latexSnapshot?.let { put("latexSnapshot", it) }
    }.toString()

    fun decode(raw: String?): AnswerQuote? {
        if (raw.isNullOrBlank()) return null
        return try {
            val obj = Json.parseToJsonElement(raw).jsonObject
            val sourceMessageId = obj["sourceMessageId"]?.jsonPrimitive?.contentOrNull ?: return null
            val sourceConversationId = obj["sourceConversationId"]?.jsonPrimitive?.contentOrNull ?: return null
            val textSnapshot = obj["textSnapshot"]?.jsonPrimitive?.contentOrNull ?: return null
            val latexSnapshot = obj["latexSnapshot"]?.jsonPrimitive?.contentOrNull
            AnswerQuote(sourceMessageId, sourceConversationId, textSnapshot, latexSnapshot)
        } catch (_: Exception) { null }
    }
}
