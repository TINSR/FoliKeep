package com.yuejian.ai

import com.yuejian.model.FinishReason
import com.yuejian.model.TokenUsage
import kotlinx.serialization.json.*

/**
 * Chat 流式数据块解析（纯函数，无网络副作用，供 JVM 单测）。
 * 输入一条 SSE data 文本（已剥掉 "data:" 前缀），解析出正文增量、可见思考增量、
 * 用量与结束原因。`[DONE]` 与空块返回空结果；未知字段忽略。
 */
internal object ChatSse {

    data class Chunk(
        val answer: String = "",
        val thinking: String = "",
        val usage: TokenUsage? = null,
        val finish: FinishReason? = null,
        /** 仅表示本块内出现的错误对象；断流等语义由上层判断。 */
        val error: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(data: String): Chunk {
        val trimmed = data.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]") return Chunk()
        val root = try { json.parseToJsonElement(trimmed).jsonObject } catch (_: Exception) {
            return Chunk(error = "流式响应格式错误")
        }
        if ("error" in root) {
            val msg = root["error"]?.let { it as? JsonObject }?.get("message")
                ?.let { it as? JsonPrimitive }?.contentOrNull
            return Chunk(error = msg?.takeIf { it.isNotBlank() } ?: "服务商返回错误")
        }
        val choice = root["choices"]?.let { it as? JsonArray }?.firstOrNull() as? JsonObject
        // 用量可能出现在无 choices 的尾包
        val delta = choice?.get("delta") as? JsonObject
        val message = choice?.get("message") as? JsonObject
        val answer = textOf(delta?.get("content") ?: message?.get("content"))
        // 服务商字段差异：reasoning_content（DeepSeek/MiMo）或 reasoning
        val thinking = textOf(
            delta?.get("reasoning_content") ?: delta?.get("reasoning")
            ?: message?.get("reasoning_content") ?: message?.get("reasoning")
        )
        val finish = when (choice?.get("finish_reason")?.let { it as? JsonPrimitive }?.contentOrNull) {
            null -> null
            "stop" -> FinishReason.Stop
            "length" -> FinishReason.Length
            "content_filter" -> FinishReason.Filtered
            else -> FinishReason.Other
        }
        return Chunk(answer = answer, thinking = thinking, usage = parseUsage(root), finish = finish)
    }

    private fun textOf(node: JsonElement?): String =
        (node as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun parseUsage(root: JsonObject): TokenUsage? {
        val u = root["usage"] as? JsonObject ?: return null
        fun num(vararg keys: String): Long? = keys.firstNotNullOfOrNull { k ->
            (u[k] as? JsonPrimitive)?.longOrNull
        }
        val detailsCached = (u["prompt_tokens_details"] as? JsonObject)?.get("cached_tokens")
            ?.let { it as? JsonPrimitive }?.longOrNull
        val detailsReasoning = (u["completion_tokens_details"] as? JsonObject)?.get("reasoning_tokens")
            ?.let { it as? JsonPrimitive }?.longOrNull
        return TokenUsage(
            input = num("prompt_tokens", "input_tokens"),
            cached = num("prompt_cache_hit_tokens", "cached_tokens") ?: detailsCached,
            output = num("completion_tokens", "output_tokens"),
            reasoning = num("reasoning_tokens") ?: detailsReasoning
        )
    }
}
