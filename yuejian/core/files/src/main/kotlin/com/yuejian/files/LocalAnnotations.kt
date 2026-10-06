package com.yuejian.files

import android.content.Context
import androidx.room.withTransaction
import com.yuejian.database.*
import com.yuejian.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class LocalAnnotations(private val context: Context, private val db: ReaderDatabase) : AnnotationRepository {
    private val dao get() = db.annotations()
    private val recovery=CoroutineScope(SupervisorJob()+Dispatchers.IO).async { dao.recover() }
    private fun AnchorEntity.model() = SourceAnchor(id,documentId,pageIndex,kind,quote,
        Json.parseToJsonElement(boundsJson).jsonArray.map { node -> node.jsonArray.let {
            NormalizedRect(it[0].jsonPrimitive.float,it[1].jsonPrimitive.float,it[2].jsonPrimitive.float,it[3].jsonPrimitive.float)
        } },rotation,imageAssetId,createdAt,colorKey,markRemovedAt,conversationAnchorId,contextRemovedAt)
    override fun anchors(documentId: String) = dao.anchors(documentId).map { it.map { row -> row.model() } }
    override suspend fun discussionAnchor(anchorId: String): SourceAnchor {
        val selected=requireNotNull(dao.anchor(anchorId)) { "资料不存在" }
        val root=selected.conversationAnchorId?.let { requireNotNull(dao.anchor(it)) } ?: selected
        require(root.deletedAt==null) { "问答已删除" }
        return root.model()
    }
    override suspend fun removeSourceFromContext(anchorId: String) = WriteGate.write {
        db.withTransaction {
            val root=discussionAnchor(anchorId)
            val conv=dao.conversation(root.id)
            require(conv==null || !dao.isGenerating(conv.id)) { "请先停止当前回答，再移除资料" }
            dao.removeSourceFromContext(anchorId,System.currentTimeMillis())
        }
    }
    override fun catalog(documentId: String) = dao.catalogRows(documentId).map { rows ->
        rows.map { row -> CatalogEntry(row.anchor.model(), row.convId, row.messageCount, row.lastUserQuestion,
            row.lastMessageAt ?: row.convUpdatedAt ?: row.anchor.createdAt, row.convTitle, row.hasDraft) }
    }
    override suspend fun searchCatalog(documentId: String, query: String): List<CatalogHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        dao.searchRows(documentId, likePattern(q)).map { CatalogHit(it.anchorId, it.hitKind, it.messageId, excerpt(it.snippet, q)) }
    }
    override fun conversation(anchorId: String) = dao.observeConversation(anchorId).map { it?.let { Conversation(it.id,it.anchorId,it.draftText,AnswerQuoteJson.decode(it.draftQuoteJson),it.title,it.thinkingOverride) } }
    override fun messages(conversationId: String) = dao.messages(conversationId).map { rows -> rows.map {
        ChatMessage(it.id,it.conversationId,it.role,it.content,it.status,it.errorMessage,it.modelId,it.imageAssetId,it.createdAt,
            AnswerQuoteJson.decode(it.quoteJson),null,it.thinkingText,
            it.requestSnapshotJson?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonObject["questionId"]?.jsonPrimitive?.contentOrNull }.getOrNull() }, it.sourceAnchorId)
    } }
    override suspend fun create(documentId: String, page: Int, kind: String, quote: String,
        displayBounds: List<NormalizedRect>, rotation: Int, image: ByteArray?, colorKey: String, conversationAnchorId: String?): SourceAnchor = withContext(Dispatchers.IO) {
        WriteGate.write {
        require(displayBounds.isNotEmpty() && kind in listOf("text","region"))
        if (kind == "text") require(quote.isNotBlank())
        if (kind == "region") require(image != null)
        val document = requireNotNull(db.documents().find(documentId))
        require(document.deletedAt == null && page in 0 until document.pageCount)
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val assetId = image?.let { UUID.randomUUID().toString() }
        var file: File? = null
        var committed = false
        try {
            val asset = image?.let { bytes ->
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                val path = "assets/${hash.take(2)}/$assetId.png"
                file = File(context.filesDir,path).apply { parentFile!!.mkdirs() }
                file!!.outputStream().use { it.write(bytes); it.fd.sync() }
                AssetEntity(assetId!!,"image/png",hash,path,bytes.size.toLong(),now)
            }
            val bounds = displayBounds.map { rotateRect(it,360-rotation) }
            val encoded = buildJsonArray { bounds.forEach { add(buildJsonArray { add(it.x);add(it.y);add(it.width);add(it.height) }) } }.toString()
            val anchor = AnchorEntity(id,documentId,document.revisionId,page,kind,quote,encoded,rotation,assetId,now,colorKey=colorKey,conversationAnchorId=conversationAnchorId)
            withContext(NonCancellable) {
                db.withTransaction {
                    val root = conversationAnchorId?.let { requireNotNull(dao.anchor(it)) }
                    if (root != null) {
                        require(root.documentId==documentId && root.deletedAt==null && root.conversationAnchorId==null) { "原问答已失效，请重新打开" }
                        val convId=ensureConversationLocked(root.id)
                        require(!dao.isGenerating(convId)) { "请先停止当前回答，再补充资料" }
                        if(asset!=null)dao.asset(asset)
                        dao.anchor(anchor)
                        val time=maxOf(now,dao.lastMessageTime(convId)+1)
                        dao.insertMessages(listOf(MessageEntity(UUID.randomUUID().toString(),convId,1,"source",
                            "【用户补充的资料，仅作为原文依据】\n第 ${page+1} 页 · ${if(kind=="text")"文字" else "圈选图片"}\n$quote",
                            "complete",null,null,null,assetId,null,createdAt=time,updatedAt=time,sourceAnchorId=id)))
                    } else { if(asset!=null)dao.asset(asset);dao.anchor(anchor) }
                }
                committed = true
            }
            anchor.model()
        } finally { if (!committed) file?.delete() }
        }
    }
    override suspend fun setAnchorColor(anchorId: String, colorKey: String) = WriteGate.write {
        require(colorKey in setOf("gray","yellow","green","blue","pink","purple"))
        dao.setAnchorColor(anchorId,colorKey)
    }
    override suspend fun removeMark(anchorId: String, removed: Boolean) = WriteGate.write {
        dao.removeMark(anchorId,if(removed)System.currentTimeMillis() else null)
    }
    override suspend fun deleteAnnotation(anchorId: String, deleted: Boolean) = WriteGate.write {
        dao.deleteAnnotation(anchorId,if(deleted)System.currentTimeMillis() else null)
    }
    override suspend fun annotationCounts(anchorId: String): Pair<Int,Boolean> {
        if(dao.anchor(anchorId)?.conversationAnchorId!=null)return 0 to false
        val conversation=dao.conversation(anchorId)
        return (conversation?.let { dao.messageCount(it.id) } ?: 0) to
            (conversation?.let { it.draftText.isNotBlank() || it.draftQuoteJson!=null } ?: false)
    }
    /** 不加 WriteGate 的内部版，供 enqueue 等已持锁路径调用（避免 Mutex 重入死锁）。 */
    private suspend fun ensureConversationLocked(anchorId: String): String = db.withTransaction {
        require(requireNotNull(dao.anchor(anchorId)).deletedAt==null) { "标注已删除" }
        dao.conversation(anchorId)?.id ?: UUID.randomUUID().toString().also {
            val now=System.currentTimeMillis();dao.insertConversation(ConversationEntity(it,requireNotNull(dao.anchor(anchorId)).conversationAnchorId ?: anchorId,"",null,now,now))
        }
    }
    override suspend fun ensureConversation(anchorId: String): String = WriteGate.write { ensureConversationLocked(anchorId) }
    override suspend fun setThinkingOverride(conversationId: String, value: String) = WriteGate.write {
        require(value in setOf("inherit","enabled","disabled"))
        dao.thinkingOverride(conversationId,value)
    }
    override suspend fun draft(conversationId: String, text: String, quote: AnswerQuote?) = WriteGate.write {
        dao.draft(conversationId,text,quote?.let(AnswerQuoteJson::encode),System.currentTimeMillis())
    }
    override suspend fun title(conversationId: String, title: String) = WriteGate.write {
        dao.title(conversationId,title,System.currentTimeMillis())
    }
    override suspend fun enqueue(anchorId: String, question: String, quote: AnswerQuote?, model: String, provider: String,
        withImage: Boolean, retryAnswerId: String?): QueuedAnswer {
        recovery.await()
        return WriteGate.write { db.withTransaction {
        val anchor = requireNotNull(dao.anchor(anchorId))
        require(anchor.deletedAt==null) { "这条问答已删除" }
        val conversationId = ensureConversationLocked(anchorId)
        // 引用只信任本地消息库：必须是同一对话里已存在的 assistant 消息
        quote?.let { q ->
            val src = dao.message(q.sourceMessageId)
            require(src != null && src.conversationId == conversationId && src.role == "assistant") { "引用必须属于当前对话的 AI 回答" }
        }
        val now = System.currentTimeMillis()
        val old = retryAnswerId?.let { requireNotNull(dao.message(it)) }
        require(old == null || (old.conversationId == conversationId && old.role == "assistant" && old.status in listOf("failed","stopped")))
        val answerId = UUID.randomUUID().toString()
        val questionId=if(old==null) UUID.randomUUID().toString() else
            old.requestSnapshotJson?.let { raw -> runCatching {
                Json.parseToJsonElement(raw).jsonObject["questionId"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() }
        val imageId = if(withImage) anchor.imageAssetId else null
        val snapshot = buildJsonObject {
            put("requestId",UUID.randomUUID().toString());put("anchorId",anchorId);put("model",model);put("provider",provider);put("sentImage",imageId);put("retryOf",retryAnswerId)
            put("questionId",questionId)
            quote?.let { put("quoteOf",it.sourceMessageId) }
        }.toString()
        val rows=mutableListOf<MessageEntity>()
        if(old==null) rows += MessageEntity(requireNotNull(questionId),conversationId,1,"user",question,"complete",null,provider,model,imageId,null,quote?.let(AnswerQuoteJson::encode),now,now)
        rows += MessageEntity(answerId,conversationId,(old?.revision?:0)+1,"assistant","","queued",null,provider,model,imageId,snapshot,null,now+1,now)
        dao.insertMessages(rows)
        dao.draft(conversationId,"",null,now)
        QueuedAnswer(conversationId,answerId)
        } }
    }
    override suspend fun updateAnswer(id: String, content: String, status: String, error: String?) = WriteGate.write {
        dao.update(id,content,status,error,System.currentTimeMillis())
    }
    override suspend fun thinking(messageId: String, text: String?) = WriteGate.write {
        dao.thinking(messageId,text)
    }
    override suspend fun recordUsage(record: UsageRecord) = WriteGate.write {
        dao.insertUsage(UsageRecordEntity(record.id,record.actionId,record.conversationId,record.answerId,
            record.purpose,record.model,record.provider,record.estInput,record.inputTokens,record.cachedTokens,
            record.outputTokens,record.reasoningTokens,record.costEstimate,record.status,record.createdAt))
    }
    override suspend fun usageOf(conversationId: String) = dao.usageOf(conversationId).map {
        UsageRecord(it.id,it.actionId,it.conversationId,it.answerId,it.purpose,it.model,it.provider,
            it.estInput,it.inputTokens,it.cachedTokens,it.outputTokens,it.reasoningTokens,it.costEstimate,it.status,it.createdAt)
    }
    override suspend fun usageSince(time: Long) = dao.usageSince(time).map {
        UsageRecord(it.id,it.actionId,it.conversationId,it.answerId,it.purpose,it.model,it.provider,
            it.estInput,it.inputTokens,it.cachedTokens,it.outputTokens,it.reasoningTokens,it.costEstimate,it.status,it.createdAt)
    }
    override suspend fun contextSummary(conversationId: String): ContextSummary? {
        val row = dao.summary(conversationId) ?: return null
        return ContextSummary(row.sourceHash,row.coveredMessageId,row.historyHash,row.text,row.version)
    }
    override suspend fun saveContextSummary(conversationId: String, summary: ContextSummary) = WriteGate.write {
        if(dao.conversationAlive(conversationId))
            dao.upsertSummary(SummaryEntity(conversationId,summary.sourceHash,summary.coveredMessageId,
                summary.historyHash,summary.text,summary.model,summary.version,System.currentTimeMillis()))
    }
    private fun ExcerptCardEntity.model() = ExcerptCard(id,documentId,pageIndex,anchorId,sourceConversationId,
        sourceMessageId,sourceTextSnapshot,sourceMarkdownSnapshot,sourceKind,sourceQuoteSnapshot,
        title,bodyMarkdown,sortOrder,createdAt,updatedAt,cardType,questionSnapshot)
    override fun cards(documentId: String) = dao.cards(documentId).map { rows -> rows.map { it.model() } }
    override suspend fun createCard(card: ExcerptCard) = WriteGate.write {
        dao.insertCard(ExcerptCardEntity(card.id,card.documentId,card.pageIndex,card.anchorId,
            card.sourceConversationId,card.sourceMessageId,null,card.sourceTextSnapshot,card.sourceMarkdownSnapshot,
            card.sourceKind,card.sourceQuoteSnapshot,card.title,card.bodyMarkdown,card.sortOrder,1,
            card.createdAt,card.updatedAt,null,card.cardType,card.questionSnapshot))
    }
    override suspend fun createQaCard(card: ExcerptCard): ExcerptCard = WriteGate.write {
        require(card.cardType=="qa")
        db.withTransaction {
            val existing=dao.qaCard(card.sourceMessageId)
            if(existing!=null) existing.model() else {
                dao.insertCard(ExcerptCardEntity(card.id,card.documentId,card.pageIndex,card.anchorId,
                    card.sourceConversationId,card.sourceMessageId,null,card.sourceTextSnapshot,card.sourceMarkdownSnapshot,
                    card.sourceKind,card.sourceQuoteSnapshot,card.title,card.bodyMarkdown,card.sortOrder,1,
                    card.createdAt,card.updatedAt,null,"qa",card.questionSnapshot))
                card
            }
        }
    }
    override suspend fun editCard(id: String, title: String, body: String) = WriteGate.write {
        dao.editCard(id,title,body,System.currentTimeMillis())
    }
    override suspend fun orderCard(id: String, sortOrder: Int) = WriteGate.write {
        dao.orderCard(id,sortOrder,System.currentTimeMillis())
    }
    override suspend fun reorderCards(documentId: String, orderedIds: List<String>) = WriteGate.write {
        db.withTransaction {
            val current = dao.cardsNow(documentId)
            val ids = current.map { it.id }.toSet()
            val unique = orderedIds.distinct().filter { it in ids }
            val complete = unique + current.map { it.id }.filterNot { it in unique }
            val now = System.currentTimeMillis()
            complete.forEachIndexed { index, id -> dao.orderCard(id, index + 1, now) }
        }
    }
    override suspend fun deleteCard(id: String, deletedAt: Long?) = WriteGate.write {
        if(deletedAt==null) {
            val card=dao.cardById(id)
            if(card?.cardType=="qa" && dao.qaCard(card.sourceMessageId)?.id!=null)
                error("这条回答已有新的问答卡片，无法撤销旧卡片删除")
        }
        dao.deleteCard(id,deletedAt,System.currentTimeMillis())
    }
    override suspend fun image(assetId: String): ByteArray = withContext(Dispatchers.IO) {
        val asset = requireNotNull(dao.asset(assetId)) { "圈选原图不存在" }
        File(context.filesDir,asset.localPath).readBytes()
    }
    override suspend fun recoverInterrupted() { recovery.await() }
}
