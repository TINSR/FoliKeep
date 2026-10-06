package com.yuejian.model

import kotlinx.coroutines.flow.Flow

data class SourceAnchor(val id: String, val documentId: String, val pageIndex: Int,
    val kind: String, val quote: String, val bounds: List<NormalizedRect>, val rotation: Int,
    val imageAssetId: String?, val createdAt: Long,
    val colorKey: String = "gray", val markRemovedAt: Long? = null,
    val conversationAnchorId: String? = null, val contextRemovedAt: Long? = null) {
    val discussionAnchorId: String get() = conversationAnchorId ?: id
}

/** 针对 AI 回答局部的引用：只保存快照与来源标识，来源失效后快照仍可读。 */
data class AnswerQuote(val sourceMessageId: String, val sourceConversationId: String,
    val textSnapshot: String, val latexSnapshot: String? = null)
data class Conversation(val id: String, val anchorId: String, val draft: String,
    val draftQuote: AnswerQuote? = null, val title: String? = null,
    val thinkingOverride: String = "inherit")
data class ChatMessage(val id: String, val conversationId: String, val role: String,
    val content: String, val status: String, val error: String?, val modelId: String?,
    val imageAssetId: String?, val createdAt: Long, val quote: AnswerQuote? = null,
    val generationInfo: String? = null, val thinking: String? = null,
    val sourceQuestionId: String? = null, val sourceAnchorId: String? = null)
data class QueuedAnswer(val conversationId: String, val answerId: String)

/** 目录条目：一个标注锚点 + 它的问答摘要（无对话/空对话时 messageCount 为 0）。 */
data class CatalogEntry(val anchor: SourceAnchor, val conversationId: String?,
    val messageCount: Int, val lastUserQuestion: String?, val lastActivityAt: Long,
    val title: String? = null, val hasDraft: Boolean = false) {
    /** 仅创建空对话但无消息的锚点不算“有问答”。 */
    val hasDiscussion: Boolean get() = messageCount > 0
}

/** 搜索命中：quote=高亮/圈选原文，user=我的提问，assistant=AI 回答；后两者带 messageId。 */
data class CatalogHit(val anchorId: String, val hitKind: String, val messageId: String?, val snippet: String)

/**
 * 用量账本记录：一次模型调用一行。估算与实际分列；实际用量缺失时为 null，
 * 状态标“费用待确认”，不得当作 0 或免费。
 */
data class UsageRecord(val id: String, val actionId: String, val conversationId: String,
    val answerId: String, val purpose: String, val model: String, val provider: String,
    val estInput: Int, val inputTokens: Long?, val cachedTokens: Long?, val outputTokens: Long?,
    val reasoningTokens: Long?, val costEstimate: Double?, val status: String, val createdAt: Long)

/**
 * 精选卡片：用户从正式回答摘录的学习内容，关联原文锚点与来源问答。
 * 正文可编辑；来源快照不可变，编辑卡片不回写 AI 原回答。
 */
data class ExcerptCard(val id: String, val documentId: String, val pageIndex: Int,
    val anchorId: String?, val sourceConversationId: String, val sourceMessageId: String,
    val sourceTextSnapshot: String, val sourceMarkdownSnapshot: String,
    val sourceKind: String, val sourceQuoteSnapshot: String?,
    val title: String, val bodyMarkdown: String, val sortOrder: Int,
    val createdAt: Long, val updatedAt: Long,
    val cardType: String = "excerpt", val questionSnapshot: String? = null)

interface AnnotationRepository {
    fun anchors(documentId: String): Flow<List<SourceAnchor>>
    fun conversation(anchorId: String): Flow<Conversation?>
    suspend fun discussionAnchor(anchorId: String): SourceAnchor
    suspend fun removeSourceFromContext(anchorId: String)
    fun messages(conversationId: String): Flow<List<ChatMessage>>
    fun catalog(documentId: String): Flow<List<CatalogEntry>>
    suspend fun searchCatalog(documentId: String, query: String): List<CatalogHit>
    suspend fun create(documentId: String, page: Int, kind: String, quote: String,
        displayBounds: List<NormalizedRect>, rotation: Int, image: ByteArray?, colorKey: String = "gray",
        conversationAnchorId: String? = null): SourceAnchor
    suspend fun setAnchorColor(anchorId: String, colorKey: String)
    suspend fun removeMark(anchorId: String, removed: Boolean)
    suspend fun deleteAnnotation(anchorId: String, deleted: Boolean)
    suspend fun annotationCounts(anchorId: String): Pair<Int, Boolean>
    suspend fun ensureConversation(anchorId: String): String
    suspend fun setThinkingOverride(conversationId: String, value: String)
    suspend fun draft(conversationId: String, text: String, quote: AnswerQuote? = null)
    suspend fun title(conversationId: String, title: String)
    suspend fun enqueue(anchorId: String, question: String, quote: AnswerQuote? = null,
        model: String, provider: String,
        withImage: Boolean, retryAnswerId: String? = null): QueuedAnswer
    suspend fun updateAnswer(id: String, content: String, status: String, error: String? = null)
    /** 保存可见思考记录（开关开启时调用）；不进搜索/备份，仅作回看。 */
    suspend fun thinking(messageId: String, text: String?)
    suspend fun recordUsage(record: UsageRecord)
    suspend fun usageOf(conversationId: String): List<UsageRecord>
    suspend fun usageSince(time: Long): List<UsageRecord>
    /** 阶段摘要（每对话一份；指纹不符由调用方失效重建）。 */
    suspend fun contextSummary(conversationId: String): ContextSummary?
    suspend fun saveContextSummary(conversationId: String, summary: ContextSummary)
    fun cards(documentId: String): Flow<List<ExcerptCard>>
    suspend fun createCard(card: ExcerptCard)
    suspend fun createQaCard(card: ExcerptCard): ExcerptCard
    suspend fun editCard(id: String, title: String, body: String)
    suspend fun orderCard(id: String, sortOrder: Int)
    suspend fun reorderCards(documentId: String, orderedIds: List<String>)
    suspend fun deleteCard(id: String, deletedAt: Long?)
    suspend fun image(assetId: String): ByteArray
    suspend fun recoverInterrupted()
    // 阶段 B/C 引入：用量账本（reserveCall/finishCall/callRecords）与阶段摘要（contextSummary/saveContextSummary）
    // 随对应阶段的表与迁移一起加入，避免半集成接口挂空。
}
fun rotateRect(rect: NormalizedRect, rotation: Int): NormalizedRect = when ((rotation%360+360)%360) {
    90 -> NormalizedRect((1-rect.y-rect.height).coerceIn(0f, 1f), rect.x, rect.height, rect.width)
    180 -> NormalizedRect((1-rect.x-rect.width).coerceIn(0f, 1f), (1-rect.y-rect.height).coerceIn(0f, 1f), rect.width, rect.height)
    270 -> NormalizedRect(rect.y, (1-rect.x-rect.width).coerceIn(0f, 1f), rect.height, rect.width)
    else -> rect
}
fun NormalizedRect.contains(x: Float, y: Float) = x in this.x..(this.x+width) && y in this.y..(this.y+height)
fun hitAnchor(anchors: List<SourceAnchor>, x: Float, y: Float): SourceAnchor? = anchors
    .filter { a -> a.bounds.any { rotateRect(it, a.rotation).contains(x,y) } }
    .sortedWith(compareBy<SourceAnchor> { if (it.kind == "text") 0 else 1 }
        .thenBy { it.bounds.sumOf { rect -> (rect.width*rect.height).toDouble() } }
        .thenByDescending { it.createdAt }).firstOrNull()

data class ModelConfig(val name: String = "自定义", val baseUrl: String = "",
    val textModel: String = "", val imageModel: String = "", val imageEnabled: Boolean = false,
    val streaming: Boolean = true, val timeoutSeconds: Int = 90, val keyPresent: Boolean = false,
    val contextWindow: Int = 65536, val inputBudget: Int = 32768, val outputBudget: Int = 8192,
    val imageTokenBudget: Int = 8192, val thinkingMode: String = "disabled",
    val inputPrice: Double = 0.0, val cachedPrice: Double = 0.0, val outputPrice: Double = 0.0,
    val turnLimitYuan: Double = 0.0, val dailyLimitYuan: Double = 0.0)
interface ModelSettings {
    val config: Flow<ModelConfig>
    suspend fun save(config: ModelConfig, newKey: String? = null)
    suspend fun deleteKey()
}
data class ModelTurn(val role: String, val content: String, val quote: String? = null, val id: String? = null,
    val imageAssetId: String? = null, val isSource: Boolean = false)
data class AiRequest(val config: ModelConfig, val model: String, val source: String,
    val history: List<ModelTurn>, val image: ByteArray? = null,
    val summary: String? = null, val outputLimit: Int = config.outputBudget,
    val purpose: String = "answer", val imageTokens: Int = 0,
    val images: Map<String, ByteArray> = emptyMap())
interface ModelProvider {
    fun stream(request: AiRequest): Flow<AiEvent>
    suspend fun testConnection(config: ModelConfig): String
    /** 首条回答完成后给会话起主题标题（轻量非流式调用）；失败抛异常，由调用方回退。 */
    suspend fun title(config: ModelConfig, model: String, question: String, answer: String): String
}
