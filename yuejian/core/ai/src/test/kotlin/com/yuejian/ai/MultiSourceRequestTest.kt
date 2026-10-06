package com.yuejian.ai

import com.yuejian.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MultiSourceRequestTest {
    private val cfg=ModelConfig(baseUrl="https://api.xiaomimimo.com/v1",thinkingMode="enabled")
    private fun body(request: AiRequest)=Json.parseToJsonElement(buildRequestBody(request) {
        java.util.Base64.getEncoder().encodeToString(it)
    }).jsonObject
    @Test fun addingAPictureKeepsEarlierMessagesUnchanged() {
        val history=listOf(ModelTurn("user","n 是什么？"))
        val initial=AiRequest(cfg,"m","原文",history)
        val source=ModelTurn("user","第 2 页 · 定义图片",imageAssetId="img",isSource=true)
        val updated=initial.copy(history=history+ModelTurn("assistant","请补充定义。")+source+ModelTurn("user","继续回答"),images=mapOf("img" to byteArrayOf(2)))
        val before=body(initial)["messages"]!!.jsonArray
        val after=body(updated)["messages"]!!.jsonArray
        assertEquals(before.toList(),after.take(before.size))
        assertEquals("data:image/png;base64,Ag==",after[4].jsonObject["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("disabled",body(updated)["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }
    @Test fun rootAndSupplementPicturesOccupyTheirOwnSourceMessages() {
        val req=AiRequest(cfg,"m","原图",listOf(ModelTurn("user","补充图片",imageAssetId="img",isSource=true),ModelTurn("user","请解释")),
            image=byteArrayOf(1),images=mapOf("img" to byteArrayOf(2)))
        val messages=body(req)["messages"]!!.jsonArray
        fun url(index: Int)=messages[index].jsonObject["content"]!!.jsonArray[1].jsonObject["image_url"]!!.jsonObject
        assertEquals("data:image/png;base64,AQ==",url(1)["url"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,Ag==",url(2)["url"]!!.jsonPrimitive.content)
        assertFalse(url(2).containsKey("detail"))
        assertEquals("请解释",messages.last().jsonObject["content"]!!.jsonPrimitive.content)
    }
    @Test fun providerUsesTheSamePromptsAsBudgetEstimator() {
        val turn=ModelTurn("user","这个符号呢？",quote="n")
        val messages=body(AiRequest(cfg,"m","教材",listOf(turn)))["messages"]!!.jsonArray
        assertEquals(ReadingPrompts.SYSTEM,messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(ReadingPrompts.source("教材"),messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals(ReadingPrompts.turn(turn),messages[2].jsonObject["content"]!!.jsonPrimitive.content)
    }
    @Test(expected=IllegalArgumentException::class) fun missingSupplementImageCannotBeSilentlySentAsText() {
        body(AiRequest(cfg,"m","教材",listOf(ModelTurn("user","图片",imageAssetId="missing",isSource=true),ModelTurn("user","问题"))))
    }
}
