package com.yuejian.ai

import com.yuejian.model.AiRequest
import com.yuejian.model.ModelConfig
import com.yuejian.model.ModelTurn
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GenerationOptionsTest {
    private fun options(baseUrl: String, thinking: String = "enabled", image: ByteArray? = null, purpose: String = "answer") =
        buildJsonObject {
            addGenerationOptions(AiRequest(ModelConfig(baseUrl = baseUrl, thinkingMode = thinking),
                "model", "source", listOf(ModelTurn("user", "question")), image = image,
                outputLimit = 8192, purpose = purpose))
        }

    @Test fun deepSeekAndMimoUseTheirDocumentedOutputFields() {
        val deepSeek = options("https://api.deepseek.com")
        assertEquals("8192", deepSeek["max_tokens"]?.jsonPrimitive?.content)
        assertEquals("enabled", deepSeek["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(deepSeek.containsKey("max_completion_tokens"))

        val mimo = options("https://token-plan-cn.xiaomimimo.com/v1", thinking = "disabled")
        assertEquals("8192", mimo["max_completion_tokens"]?.jsonPrimitive?.content)
        assertEquals("disabled", mimo["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(mimo.containsKey("max_tokens"))
    }

    @Test fun imagesAndSummariesDoNotSpendTheirOutputBudgetOnThinking() {
        val image = options("https://api.deepseek.com", image = byteArrayOf(1))
        val summary = options("https://api.xiaomimimo.com/v1", purpose = "summary")
        assertEquals("disabled", image["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("disabled", summary["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test fun unknownProviderKeepsGenericCompatibility() {
        val generic = options("https://example.com/v1")
        assertEquals("8192", generic["max_tokens"]?.jsonPrimitive?.content)
        assertFalse(generic.containsKey("thinking"))
    }

    @Test fun mimoImagePayloadUsesDocumentedUrlShape() {
        val mimo = imageUrlPayload("https://api.xiaomimimo.com/v1", "AQ==")
        assertEquals("data:image/png;base64,AQ==", mimo["url"]?.jsonPrimitive?.content)
        assertFalse(mimo.containsKey("detail"))
        val deepSeek = imageUrlPayload("https://api.deepseek.com", "AQ==")
        assertEquals("original", deepSeek["detail"]?.jsonPrimitive?.content)
    }
}
