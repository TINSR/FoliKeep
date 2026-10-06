package com.yuejian.ai

import android.util.Base64
import com.yuejian.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 系统提示词。除了原有的回答风格约束，这里明确要求模型输出 Markdown + 标准 LaTeX。
 *
 * 注意：提示词本身不再做任何转义处理，JSON 转义统一由 kotlinx.serialization 在序列化时完成，
 * 避免出现"模型拿到被重复转义的反斜杠"（\\frac）或"本地再反转义一次"这类问题。
 * 渲染端仍然兼容 `$...$` 与 `$$...$$`，用于处理历史回答以及模型没有严格遵循提示词的情况。
 */
private const val SYSTEM_PROMPT = ReadingPrompts.SYSTEM

internal fun JsonObjectBuilder.addGenerationOptions(request: AiRequest) {
    val vendor = thinkingProvider(request.config.baseUrl)
    put(if (vendor == ThinkingProvider.MIMO) "max_completion_tokens" else "max_tokens", request.outputLimit)
    if (vendor != null) {
        val enabled = request.purpose == "answer" && request.image == null && request.history.none { it.imageAssetId != null } && request.config.thinkingMode == "enabled"
        put("thinking", buildJsonObject { put("type", if (enabled) "enabled" else "disabled") })
    }
}

internal fun imageUrlPayload(baseUrl: String, encodedImage: String): JsonObject = buildJsonObject {
    put("url", "data:image/png;base64," + encodedImage)
    // MiMo's documented image_url shape contains only url; other adapters retain existing detail behavior.
    if (thinkingProvider(baseUrl) != ThinkingProvider.MIMO) put("detail", "original")
}

internal fun buildRequestBody(request: AiRequest, encodeImage: (ByteArray) -> String): String = buildJsonObject {
            put("model",request.model);put("stream",request.config.streaming)
            addGenerationOptions(request)
            put("messages",buildJsonArray {
                // 摘要调用（purpose=summary）使用整理提示；普通回答使用阅读助手提示
                add(buildJsonObject { put("role","system");put("content", if(request.purpose=="summary") com.yuejian.model.ReadingPrompts.COMPACT else SYSTEM_PROMPT) })
                // 图片固定在来源块（前缀稳定）：同一会话每轮前缀逐字节一致，利于服务商提示缓存；
                // 补充图片保留在各自的资料消息中，不移动至最新问题。
                add(buildJsonObject {
                    put("role","user")
                    val sourceText = ReadingPrompts.source(request.source)
                    val sourceImage = request.image
                    if(sourceImage!=null) {
                        put("content",buildJsonArray {
                            add(buildJsonObject { put("type","text");put("text",sourceText) })
                            add(buildJsonObject { put("type","image_url");put("image_url",imageUrlPayload(
                                request.config.baseUrl,encodeImage(sourceImage))) })
                        })
                    } else put("content",sourceText)
                })
                request.history.forEach { turn -> add(buildJsonObject {
                    put("role",turn.role)
                    // 引用作为“待讨论数据”随对应问题一起发送，历史轮次同样保留；不是系统指令，也不是教材原文。
                    val text = ReadingPrompts.turn(turn)
                    val image = turn.imageAssetId?.let { requireNotNull(request.images[it]) { "补充图片缺失，请移除或重新添加该资料" } }
                    if(image==null)put("content",text) else put("content",buildJsonArray {
                        add(buildJsonObject { put("type","text");put("text",text) })
                        add(buildJsonObject { put("type","image_url");put("image_url",imageUrlPayload(
                            request.config.baseUrl,encodeImage(image))) })
                    })
                }) }
            })
        }.toString()

class OpenAiCompatibleProvider(private val settings: SecureModelSettings) : ModelProvider {
    private val json=Json { ignoreUnknownKeys=true }
    private val client=OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()

    override fun stream(request: AiRequest): Flow<AiEvent> = flow {
        require(request.model.isNotBlank()) { "请先配置模型 ID" }
        require(request.history.lastOrNull()?.role=="user") { "缺少待发送的问题" }
        val key=settings.apiKey(request.config.baseUrl)
        val body=buildRequestBody(request) { Base64.encodeToString(it,Base64.NO_WRAP) }
        val httpRequest=Request.Builder().url(request.config.baseUrl.trimEnd('/')+"/chat/completions")
            .header("Authorization","Bearer $key").header("Accept",if(request.config.streaming) "text/event-stream" else "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call=client.newBuilder().connectTimeout(20,TimeUnit.SECONDS)
            .readTimeout(request.config.timeoutSeconds.toLong(),TimeUnit.SECONDS).callTimeout(10,TimeUnit.MINUTES).build().newCall(httpRequest)
        val cancelWatcher=CoroutineScope(currentCoroutineContext()).launch(start=CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if(!response.isSuccessful) {
                    emit(AiEvent.Failed(httpError(response.code), FailKind.Http)); return@use
                }
                val responseBody=response.body
                if(responseBody==null) {
                    emit(AiEvent.Failed("服务商没有返回内容", FailKind.Empty)); return@use
                }
                var hasAnswer=false
                var hasThinking=false
                if(!request.config.streaming || response.header("Content-Type").orEmpty().contains("application/json")) {
                    // 非流式：真实等待，不伪装流式过程
                    val root=json.parseToJsonElement(responseBody.string()).jsonObject
                    if("error" in root) {
                        emit(AiEvent.Failed("服务商返回错误，请检查模型配置与账户状态", FailKind.Http)); return@use
                    }
                    val choice=root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    val message=choice?.get("message")?.jsonObject
                    val reasoning=(message?.get("reasoning_content") ?: message?.get("reasoning"))
                        ?.jsonPrimitive?.contentOrNull.orEmpty()
                    val content=message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                    val reason=choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull
                    if(reasoning.isNotEmpty()) { hasThinking=true; emit(AiEvent.ThinkingDelta(reasoning)) }
                    if(content.isNotBlank()) { hasAnswer=true; emit(AiEvent.AnswerDelta(content)) }
                    ChatSse.parse(root.toString()).usage?.let { emit(AiEvent.Usage(it)) }
                    when {
                        reason=="content_filter" -> emit(AiEvent.Failed("服务商拒绝回答此问题", FailKind.Http))
                        reason=="length" -> emit(AiEvent.Finished(FinishReason.Length))
                        hasAnswer -> emit(AiEvent.Finished(FinishReason.Stop))
                        else -> emit(AiEvent.Failed(emptyAnswer(reason,request.image!=null), FailKind.Empty))
                    }
                } else {
                    var chunkError: String?=null
                    var finished=false
                    val event=StringBuilder()
                    suspend fun dispatch() {
                        val data=event.toString().trim();event.clear()
                        if(data.isEmpty() || data=="[DONE]") return
                        val chunk=ChatSse.parse(data)
                        if(chunk.error!=null) { chunkError=chunk.error;return }
                        if(chunk.thinking.isNotEmpty()) { hasThinking=true;emit(AiEvent.ThinkingDelta(chunk.thinking)) }
                        if(chunk.answer.isNotEmpty()) { hasAnswer=true;emit(AiEvent.AnswerDelta(chunk.answer)) }
                        chunk.usage?.let { emit(AiEvent.Usage(it)) }
                        when (chunk.finish) {
                            FinishReason.Filtered -> chunkError="服务商拒绝回答此问题"
                            null -> {}
                            else -> { finished=true;emit(AiEvent.Finished(chunk.finish)) }
                        }
                    }
                    val source=responseBody.source()
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val line=source.readUtf8Line() ?: break
                        if(line.isEmpty()) dispatch()
                        else if(line.startsWith("data:")) {
                            if(event.isNotEmpty())event.append('\n')
                            event.append(line.substring(5).trimStart())
                        }
                        if(chunkError!=null) break
                    }
                    if(chunkError==null && event.isNotEmpty())dispatch()
                    when {
                        chunkError!=null -> emit(AiEvent.Failed(chunkError!!, FailKind.Http))
                        // 断流不能标完成：保留已收内容，明确失败
                        !finished -> emit(AiEvent.Failed("连接提前断开，已保留收到的内容；可以手动重试", FailKind.Disconnected))
                        !hasAnswer && !hasThinking -> emit(AiEvent.Failed(emptyAnswer("stop",request.image!=null), FailKind.Empty))
                        // 仅思考没有正文：不把思考链当正式答案
                        !hasAnswer -> emit(AiEvent.Failed("模型只返回了思考过程，没有生成正式回答", FailKind.Empty))
                    }
                }
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            emit(AiEvent.Failed("网络连接失败或超时，已保留问题与已收到的回答", FailKind.Network))
        } finally { cancelWatcher.cancel();call.cancel() }
    }.flowOn(Dispatchers.IO)

    override suspend fun testConnection(config: ModelConfig): String {
        var answered=false
        stream(AiRequest(config,config.textModel,"连接测试，无文档内容",listOf(ModelTurn("user","请只回复 OK")))).collect { event ->
            when (event) {
                is AiEvent.AnswerDelta -> if(event.text.isNotBlank()) answered=true
                is AiEvent.Failed -> error(event.message)
                else -> {}
            }
        }
        if(!answered) error("模型没有返回正文，请检查模型或增加超时时间")
        return "文字模型连接成功；图片能力请用圈选问答确认"
    }

    override suspend fun title(config: ModelConfig, model: String, question: String, answer: String): String = withContext(Dispatchers.IO) {
        require(model.isNotBlank()) { "缺少模型 ID" }
        val key=settings.apiKey(config.baseUrl)
        val body=buildJsonObject {
            put("model",model);put("stream",false);put("max_tokens",24)
            put("messages",buildJsonArray {
                add(buildJsonObject { put("role","system");put("content","给下面的问答起一个不超过15个字的中文主题标题。只输出标题本身，不要标点、引号、“标题”字样或换行。") })
                add(buildJsonObject { put("role","user");put("content","问题：\n"+question.take(400)+"\n\n回答：\n"+answer.take(1500)) })
            })
        }.toString()
        val httpRequest=Request.Builder().url(config.baseUrl.trimEnd('/')+"/chat/completions")
            .header("Authorization","Bearer $key").header("Accept","application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call=client.newBuilder().connectTimeout(20,TimeUnit.SECONDS)
            .readTimeout(30,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).build().newCall(httpRequest)
        try {
            call.execute().use { response ->
                if(!response.isSuccessful) error(httpError(response.code))
                val responseBody=response.body ?: error("服务商没有返回内容")
                val root=json.parseToJsonElement(responseBody.string()).jsonObject
                val content=root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                cleanTitle(content)
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            error("网络连接失败，标题未生成")
        } finally { call.cancel() }
    }
    private fun cleanTitle(raw: String): String {
        val line=raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        return line.removePrefix("标题：").removePrefix("标题:").removePrefix("题目：").removePrefix("题目:")
            .trim { it in "“”\"'「」《》〈〉【】*#·、 。.,，:：;；" }
            .replace(Regex("\\s+")," ")
            .take(30)
    }
    private fun emptyAnswer(reason: String?, image: Boolean): String = when {
        reason=="length" && image -> "图片请求已达到输出上限，但没有生成正文；请缩小圈选后重试"
        reason=="length" -> "模型达到输出上限，尚未生成正文；请重试"
        image -> "模型没有返回图片解读；请确认该模型支持看图，或缩小圈选后重试"
        else -> "模型没有返回正文，请检查模型或增加超时时间"
    }
    private fun httpError(code: Int)=when(code) {
        401,403 -> "API Key 无效或没有模型访问权限"
        402 -> "模型账户余额不足"
        404 -> "接口或模型不存在，请检查 Base URL 和模型 ID"
        413 -> "内容过大，请缩小圈选或选字范围"
        429 -> "服务商限流或额度不足，请稍后手动重试"
        in 300..399 -> "接口发生重定向，请在设置中填写最终 HTTPS 地址"
        else -> "服务商返回 HTTP $code，请检查模型配置后重试"
    }
}
