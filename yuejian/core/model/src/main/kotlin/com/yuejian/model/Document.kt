package com.yuejian.model

import kotlinx.coroutines.flow.Flow

data class Document(
    val id: String,
    val revisionId: String,
    val title: String,
    val mediaType: String,
    val contentHash: String,
    val pageCount: Int,
    val lastPageIndex: Int,
    val lastOffset: Float,
    val createdAt: Long,
    val openedAt: Long,
    val thumbnail: String?,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f
)
data class PageSize(val width: Float, val height: Float)
data class PageInfo(val index: Int, val size: PageSize)
data class ImportResult(val documentId: String, val duplicate: Boolean)
interface DocumentRepository {
    fun observeDocuments(): Flow<List<Document>>
    fun observeDocument(id: String): Flow<Document?>
    suspend fun importDocument(sourceUri: String): ImportResult
    suspend fun pageInfo(id: String): List<PageInfo>
    suspend fun opened(id: String)
    suspend fun savePosition(id: String, page: Int, zoom: Float, panX: Float, panY: Float)
    suspend fun moveToTrash(id: String)
    suspend fun restore(id: String)
}
interface ReadingPreferences {
    val recentHighlightColor: Flow<String>
    suspend fun setRecentHighlightColor(value: String)
    val darkTheme: Flow<Boolean>
    suspend fun setDarkTheme(dark: Boolean)
    /** 回答字号档位：0 小 / 1 标准 / 2 大。系统字体缩放仍然生效。 */
    val answerTextScale: Flow<Int>
    suspend fun setAnswerTextScale(value: Int)
    /** 问答栏宽度偏好（dp）；窗口尺寸变化时仍按当前可用宽度重新限制。 */
    val answerPanelWidthDp: Flow<Float>
    suspend fun setAnswerPanelWidthDp(value: Float)
    /** 悬浮工具球位置（相对阅读区的 0~1 比例，跨分辨率/重启记忆）。 */
    val floatingToolsX: Flow<Float>
    val floatingToolsY: Flow<Float>
    suspend fun setFloatingToolsPos(x: Float, y: Float)
    /** 文档独立的悬浮位置；一次读取 X/Y，未保存时沿用旧全局位置或默认位置。 */
    fun floatingToolsPosition(documentId: String): Flow<Pair<Float, Float>>
    suspend fun setFloatingToolsPosition(documentId: String, x: Float, y: Float)
    /** 保留思考记录：开启后可见思考随回答持久保存供回看；默认关闭（文档默认临时保存）。 */
    /** 每张卡片独立的正文缩放，80%–200%；仅为本机显示偏好。 */
    val cardTextScales: Flow<Map<String, Float>>
    suspend fun setCardTextScale(cardId: String, scale: Float)
    val keepThinking: Flow<Boolean>
    suspend fun setKeepThinking(value: Boolean)
}
