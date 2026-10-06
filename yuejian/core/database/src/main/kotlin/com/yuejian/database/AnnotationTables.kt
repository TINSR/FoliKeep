package com.yuejian.database

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "assets")
data class AssetEntity(@PrimaryKey val id: String, val mediaType: String, val contentHash: String,
    val localPath: String, val sizeBytes: Long, val createdAt: Long)
@Entity(tableName = "anchors", indices = [Index(value=["documentId","pageIndex"])], foreignKeys = [
    ForeignKey(entity=DocumentEntity::class, parentColumns=["id"], childColumns=["documentId"])])
data class AnchorEntity(@PrimaryKey val id: String, val documentId: String, val revisionId: String,
    val pageIndex: Int, val kind: String, val quote: String, val boundsJson: String,
    val rotation: Int, val imageAssetId: String?, val createdAt: Long, val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "'yellow'") val colorKey: String = "gray", val markRemovedAt: Long? = null,
    val conversationAnchorId: String? = null, val contextRemovedAt: Long? = null)
@Entity(tableName = "conversations", indices = [Index(value=["anchorId"], unique=true)], foreignKeys = [
    ForeignKey(entity=AnchorEntity::class, parentColumns=["id"], childColumns=["anchorId"])])
data class ConversationEntity(@PrimaryKey val id: String, val anchorId: String, val draftText: String,
    val draftQuoteJson: String? = null, val createdAt: Long, val updatedAt: Long, val title: String? = null,
    @ColumnInfo(defaultValue = "'inherit'") val thinkingOverride: String = "inherit")
@Entity(tableName = "messages", indices = [Index(value=["conversationId"])], foreignKeys = [
    ForeignKey(entity=ConversationEntity::class, parentColumns=["id"], childColumns=["conversationId"])])
data class MessageEntity(@PrimaryKey val id: String, val conversationId: String, val revision: Int,
    val role: String, val content: String, val status: String, val errorMessage: String?,
    val providerId: String?, val modelId: String?, val imageAssetId: String?,
    val requestSnapshotJson: String?, val quoteJson: String? = null, val createdAt: Long, val updatedAt: Long,
    val thinkingText: String? = null, val sourceAnchorId: String? = null)
/** 目录摘要行：锚点 + 对话统计，一次查询拿全，避免逐行订阅消息列表。 */
data class CatalogRow(@Embedded val anchor: AnchorEntity, val convId: String?,
    val convUpdatedAt: Long?, val messageCount: Int, val lastUserQuestion: String?, val lastMessageAt: Long?,
    val convTitle: String?, val hasDraft: Boolean)
/** 搜索命中行：hitKind 为 quote / user / assistant。 */
data class SearchRow(val anchorId: String, val hitKind: String, val messageId: String?, val snippet: String)
@Dao interface AnnotationDao {
    @Query("SELECT a.* FROM anchors a WHERE a.documentId=:id AND a.deletedAt IS NULL AND (a.conversationAnchorId IS NULL OR EXISTS (SELECT 1 FROM anchors r WHERE r.id=a.conversationAnchorId AND r.deletedAt IS NULL)) ORDER BY a.createdAt")
    fun anchors(id: String): Flow<List<AnchorEntity>>
    @Query("SELECT * FROM anchors WHERE id=:id") suspend fun anchor(id: String): AnchorEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE conversationId=:id AND status IN ('queued','generating'))")
    suspend fun isGenerating(id: String): Boolean
    @Query("SELECT COALESCE(MAX(createdAt),0) FROM messages WHERE conversationId=:id")
    suspend fun lastMessageTime(id: String): Long
    @Insert suspend fun anchor(value: AnchorEntity)
    @Query("UPDATE anchors SET colorKey=:color WHERE (id=:id OR conversationAnchorId=:id) AND deletedAt IS NULL") suspend fun setAnchorColor(id: String, color: String)
    @Query("UPDATE anchors SET markRemovedAt=:at WHERE id=:id AND deletedAt IS NULL") suspend fun removeMark(id: String, at: Long?)
    @Query("UPDATE anchors SET deletedAt=:at WHERE id=:id") suspend fun deleteAnnotation(id: String, at: Long?)
    @Query("UPDATE anchors SET contextRemovedAt=:at,markRemovedAt=:at WHERE id=:id AND deletedAt IS NULL")
    suspend fun removeSourceFromContext(id: String, at: Long)
    @Query("SELECT COUNT(*) FROM messages WHERE conversationId=:id AND role != 'source'") suspend fun messageCount(id: String): Int
    @Insert suspend fun asset(value: AssetEntity)
    @Query("SELECT * FROM assets WHERE id=:id") suspend fun asset(id: String): AssetEntity?
    @Query("SELECT * FROM conversations WHERE anchorId=COALESCE((SELECT conversationAnchorId FROM anchors WHERE id=:id),:id)") fun observeConversation(id: String): Flow<ConversationEntity?>
    @Query("SELECT * FROM conversations WHERE anchorId=COALESCE((SELECT conversationAnchorId FROM anchors WHERE id=:id),:id)") suspend fun conversation(id: String): ConversationEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM conversations c JOIN anchors a ON a.id=c.anchorId WHERE c.id=:id AND a.deletedAt IS NULL)")
    suspend fun conversationAlive(id: String): Boolean
    @Insert suspend fun insertConversation(value: ConversationEntity)
    @Query("UPDATE conversations SET draftText=:text, draftQuoteJson=:quoteJson, updatedAt=:now WHERE id=:id AND EXISTS (SELECT 1 FROM anchors a WHERE a.id=conversations.anchorId AND a.deletedAt IS NULL)")
    suspend fun draft(id: String, text: String, quoteJson: String?, now: Long)
    @Query("UPDATE conversations SET title=:title, updatedAt=:now WHERE id=:id AND EXISTS (SELECT 1 FROM anchors a WHERE a.id=conversations.anchorId AND a.deletedAt IS NULL)")
    suspend fun title(id: String, title: String, now: Long)
    @Query("UPDATE conversations SET thinkingOverride=:value WHERE id=:id AND EXISTS (SELECT 1 FROM anchors a WHERE a.id=conversations.anchorId AND a.deletedAt IS NULL)") suspend fun thinkingOverride(id: String, value: String)
    @Query("SELECT * FROM messages WHERE conversationId=:id ORDER BY createdAt, rowid") fun messages(id: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE id=:id") suspend fun message(id: String): MessageEntity?
    @Insert suspend fun insertMessages(values: List<MessageEntity>)
    @Query("UPDATE messages SET content=:text,status=:status,errorMessage=:error,updatedAt=:now WHERE id=:id AND EXISTS (SELECT 1 FROM conversations c JOIN anchors a ON a.id=c.anchorId WHERE c.id=messages.conversationId AND a.deletedAt IS NULL)")
    suspend fun update(id: String, text: String, status: String, error: String?, now: Long)
    @Query("UPDATE messages SET status='stopped',errorMessage='上次生成已中断，可手动重试' WHERE status IN ('queued','generating')")
    suspend fun recover()
    @Query("UPDATE messages SET thinkingText=:text WHERE id=:id AND EXISTS (SELECT 1 FROM conversations c JOIN anchors a ON a.id=c.anchorId WHERE c.id=messages.conversationId AND a.deletedAt IS NULL)")
    suspend fun thinking(id: String, text: String?)
    @Insert suspend fun insertUsage(value: UsageRecordEntity)
    @Query("SELECT * FROM usage_records WHERE conversationId=:conversationId ORDER BY createdAt") suspend fun usageOf(conversationId: String): List<UsageRecordEntity>
    @Query("SELECT * FROM usage_records WHERE createdAt>=:since") suspend fun usageSince(since: Long): List<UsageRecordEntity>
    @Query("SELECT * FROM summaries WHERE conversationId=:conversationId") suspend fun summary(conversationId: String): SummaryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSummary(value: SummaryEntity)
    @Query("SELECT * FROM excerpt_cards WHERE documentId=:documentId AND deletedAt IS NULL ORDER BY sortOrder, createdAt")
    fun cards(documentId: String): Flow<List<ExcerptCardEntity>>
    @Query("SELECT * FROM excerpt_cards WHERE documentId=:documentId AND deletedAt IS NULL ORDER BY sortOrder, createdAt")
    suspend fun cardsNow(documentId: String): List<ExcerptCardEntity>
    @Query("SELECT * FROM excerpt_cards WHERE sourceMessageId=:messageId AND cardType='qa' AND deletedAt IS NULL LIMIT 1")
    suspend fun qaCard(messageId: String): ExcerptCardEntity?
    @Query("SELECT * FROM excerpt_cards WHERE id=:id") suspend fun cardById(id: String): ExcerptCardEntity?
    @Query("SELECT * FROM excerpt_cards WHERE deletedAt IS NULL")
    suspend fun allLiveCards(): List<ExcerptCardEntity>
    @Insert suspend fun insertCard(value: ExcerptCardEntity)
    @Query("UPDATE excerpt_cards SET title=:title, bodyMarkdown=:body, revision=revision+1, updatedAt=:now WHERE id=:id")
    suspend fun editCard(id: String, title: String, body: String, now: Long)
    @Query("UPDATE excerpt_cards SET sortOrder=:order, updatedAt=:now WHERE id=:id")
    suspend fun orderCard(id: String, order: Int, now: Long)
    @Query("UPDATE excerpt_cards SET deletedAt=:deletedAt, updatedAt=:now WHERE id=:id")
    suspend fun deleteCard(id: String, deletedAt: Long?, now: Long)
    @Query("SELECT * FROM anchors") suspend fun allAnchors(): List<AnchorEntity>
    @Query("SELECT * FROM conversations") suspend fun allConversations(): List<ConversationEntity>
    @Query("SELECT * FROM messages") suspend fun allMessages(): List<MessageEntity>
    @Query("SELECT * FROM assets") suspend fun allAssets(): List<AssetEntity>
    @Query("SELECT * FROM restore_map WHERE sourceKind=:kind AND sourceId=:sourceId") suspend fun restoreMap(kind: String, sourceId: String): RestoreMapEntity?
    @Query("SELECT * FROM restore_map") suspend fun allRestoreMaps(): List<RestoreMapEntity>
    @Insert suspend fun insertRestoreMaps(values: List<RestoreMapEntity>)
    @Transaction suspend fun importRows(anchors: List<AnchorEntity>, conversations: List<ConversationEntity>,
        messages: List<MessageEntity>, assets: List<AssetEntity>, cards: List<ExcerptCardEntity>,
        maps: List<RestoreMapEntity>) {
        assets.forEach { asset(it) }
        anchors.forEach { anchor(it) }
        conversations.forEach { insertConversation(it) }
        insertMessages(messages)
        cards.forEach { insertCard(it) }
        insertRestoreMaps(maps)
    }
    @Query("""SELECT anchors.*, conversations.id AS convId, conversations.updatedAt AS convUpdatedAt,
        (SELECT COUNT(*) FROM messages WHERE messages.conversationId = conversations.id) AS messageCount,
        (SELECT m2.content FROM messages m2 WHERE m2.conversationId = conversations.id AND m2.role = 'user' ORDER BY m2.createdAt DESC, m2.rowid DESC LIMIT 1) AS lastUserQuestion,
        (SELECT MAX(m3.updatedAt) FROM messages m3 WHERE m3.conversationId = conversations.id) AS lastMessageAt,
        conversations.title AS convTitle,
        CASE WHEN conversations.draftText != '' OR conversations.draftQuoteJson IS NOT NULL THEN 1 ELSE 0 END AS hasDraft
        FROM anchors LEFT JOIN conversations ON conversations.anchorId = COALESCE(anchors.conversationAnchorId,anchors.id)
        WHERE anchors.documentId = :documentId AND anchors.deletedAt IS NULL AND (anchors.conversationAnchorId IS NULL OR EXISTS (SELECT 1 FROM anchors r WHERE r.id=anchors.conversationAnchorId AND r.deletedAt IS NULL)) AND (anchors.markRemovedAt IS NULL OR conversations.id IS NOT NULL)""")
    fun catalogRows(documentId: String): Flow<List<CatalogRow>>
    @Query("""SELECT anchors.id AS anchorId, 'quote' AS hitKind, NULL AS messageId, anchors.quote AS snippet FROM anchors
        WHERE anchors.documentId = :documentId AND anchors.deletedAt IS NULL AND (anchors.conversationAnchorId IS NULL OR EXISTS (SELECT 1 FROM anchors r WHERE r.id=anchors.conversationAnchorId AND r.deletedAt IS NULL)) AND anchors.quote LIKE :pattern ESCAPE '\'
        UNION ALL
        SELECT anchors.id AS anchorId, CASE messages.role WHEN 'user' THEN 'user' ELSE 'assistant' END AS hitKind, messages.id AS messageId, messages.content AS snippet
        FROM anchors JOIN conversations ON conversations.anchorId = COALESCE(anchors.conversationAnchorId,anchors.id) JOIN messages ON messages.conversationId = conversations.id
        WHERE anchors.documentId = :documentId AND anchors.deletedAt IS NULL AND (anchors.conversationAnchorId IS NULL OR EXISTS (SELECT 1 FROM anchors r WHERE r.id=anchors.conversationAnchorId AND r.deletedAt IS NULL)) AND anchors.conversationAnchorId IS NULL AND messages.role IN ('user','assistant') AND messages.content LIKE :pattern ESCAPE '\'
        UNION ALL
        SELECT anchors.id AS anchorId, 'title' AS hitKind, NULL AS messageId, conversations.title AS snippet
        FROM anchors JOIN conversations ON conversations.anchorId = COALESCE(anchors.conversationAnchorId,anchors.id)
        WHERE anchors.documentId = :documentId AND anchors.deletedAt IS NULL AND (anchors.conversationAnchorId IS NULL OR EXISTS (SELECT 1 FROM anchors r WHERE r.id=anchors.conversationAnchorId AND r.deletedAt IS NULL)) AND anchors.conversationAnchorId IS NULL AND conversations.title LIKE :pattern ESCAPE '\'""")
    suspend fun searchRows(documentId: String, pattern: String): List<SearchRow>
}
val MIGRATION_1_2 = object : Migration(1,2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS assets (id TEXT NOT NULL PRIMARY KEY, mediaType TEXT NOT NULL, contentHash TEXT NOT NULL, localPath TEXT NOT NULL, sizeBytes INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS anchors (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, revisionId TEXT NOT NULL, pageIndex INTEGER NOT NULL, kind TEXT NOT NULL, quote TEXT NOT NULL, boundsJson TEXT NOT NULL, rotation INTEGER NOT NULL, imageAssetId TEXT, createdAt INTEGER NOT NULL, deletedAt INTEGER, FOREIGN KEY(documentId) REFERENCES documents(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_anchors_documentId_pageIndex ON anchors(documentId,pageIndex)")
        db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id TEXT NOT NULL PRIMARY KEY, anchorId TEXT NOT NULL, draftText TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(anchorId) REFERENCES anchors(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_anchorId ON conversations(anchorId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, revision INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL, errorMessage TEXT, providerId TEXT, modelId TEXT, imageAssetId TEXT, requestSnapshotJson TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages(conversationId)")
    }
}
// 引用追问：用户消息存引用快照 JSON，对话草稿存待发送引用；旧数据默认无引用。
val MIGRATION_2_3 = object : Migration(2,3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN quoteJson TEXT")
        db.execSQL("ALTER TABLE conversations ADD COLUMN draftQuoteJson TEXT")
    }
}
// 会话题目：首次回答完成后由模型起题，旧对话默认无题目（显示回退为问题摘要）。
val MIGRATION_3_4 = object : Migration(3,4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN title TEXT")
    }
}
// 恢复来源身份表：记录备份内原对象 ID → 本地对象 ID 的映射，保证同包重复恢复幂等。
@Entity(tableName = "restore_map", primaryKeys = ["sourceKind", "sourceId"], indices = [Index(value = ["sessionId"])])
data class RestoreMapEntity(val sourceKind: String, val sourceId: String,
    val localId: String, val sessionId: String, val createdAt: Long)
val MIGRATION_4_5 = object : Migration(4,5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS restore_map (sourceKind TEXT NOT NULL, sourceId TEXT NOT NULL, localId TEXT NOT NULL, sessionId TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(sourceKind, sourceId))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_restore_map_sessionId ON restore_map(sessionId)")
    }
}
// 思考记录：设置开关开启时随回答保存可见思考（可空列）；不进搜索/备份。
val MIGRATION_5_6 = object : Migration(5,6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN thinkingText TEXT")
    }
}
/** 用量账本：一次模型调用一行；估算与实际分列，缺用量标“费用待确认”而非 0。 */
@Entity(tableName = "usage_records", indices = [Index(value = ["conversationId"]), Index(value = ["createdAt"])])
data class UsageRecordEntity(@PrimaryKey val id: String, val actionId: String,
    val conversationId: String, val answerId: String, val purpose: String, val model: String, val provider: String,
    val estInput: Int, val inputTokens: Long?, val cachedTokens: Long?, val outputTokens: Long?,
    val reasoningTokens: Long?, val costEstimate: Double?, val status: String, val createdAt: Long)
// 用量账本（阶段 B）：记录调用用量与费用估算，供“本次用量”与预算检查。
val MIGRATION_6_7 = object : Migration(6,7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS usage_records (id TEXT NOT NULL PRIMARY KEY, actionId TEXT NOT NULL, conversationId TEXT NOT NULL, answerId TEXT NOT NULL, purpose TEXT NOT NULL, model TEXT NOT NULL, provider TEXT NOT NULL, estInput INTEGER NOT NULL, inputTokens INTEGER, cachedTokens INTEGER, outputTokens INTEGER, reasoningTokens INTEGER, costEstimate REAL, status TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_records_conversationId ON usage_records(conversationId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_usage_records_createdAt ON usage_records(createdAt)")
    }
}
/** 阶段摘要（阶段 C）：每对话一份有效摘要，靠覆盖范围指纹失效；可由完整聊天重建。 */
@Entity(tableName = "summaries")
data class SummaryEntity(@PrimaryKey val conversationId: String, val sourceHash: String,
    val coveredMessageId: String, val historyHash: String, val text: String,
    val model: String, val version: Int, val createdAt: Long)
// 阶段摘要（阶段 C）：发送前超预算时整理较早对话；指纹不符即失效，可重建。
val MIGRATION_7_8 = object : Migration(7,8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS summaries (conversationId TEXT NOT NULL PRIMARY KEY, sourceHash TEXT NOT NULL, coveredMessageId TEXT NOT NULL, historyHash TEXT NOT NULL, text TEXT NOT NULL, model TEXT NOT NULL, version INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
    }
}
/** 精选卡片（阶段 D）：用户从正式回答摘录的学习内容；正文与来源快照分开，编辑不回写原回答。 */
@Entity(tableName = "excerpt_cards", indices = [Index(value = ["documentId"]), Index(value = ["anchorId"]), Index(value = ["sortOrder"])])
data class ExcerptCardEntity(@PrimaryKey val id: String, val documentId: String, val pageIndex: Int,
    val anchorId: String?, val sourceConversationId: String, val sourceMessageId: String,
    val sourceSelection: String?, val sourceTextSnapshot: String, val sourceMarkdownSnapshot: String,
    val sourceKind: String, val sourceQuoteSnapshot: String?,
    val title: String, val bodyMarkdown: String, val sortOrder: Int, val revision: Int,
    val createdAt: Long, val updatedAt: Long, val deletedAt: Long?,
    @ColumnInfo(defaultValue = "'excerpt'") val cardType: String = "excerpt", val questionSnapshot: String? = null)
// 精选卡片（阶段 D）：摘录、编辑、回看与来源关联。
val MIGRATION_8_9 = object : Migration(8,9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS excerpt_cards (id TEXT NOT NULL PRIMARY KEY, documentId TEXT NOT NULL, pageIndex INTEGER NOT NULL, anchorId TEXT, sourceConversationId TEXT NOT NULL, sourceMessageId TEXT NOT NULL, sourceSelection TEXT, sourceTextSnapshot TEXT NOT NULL, sourceMarkdownSnapshot TEXT NOT NULL, sourceKind TEXT NOT NULL, sourceQuoteSnapshot TEXT, title TEXT NOT NULL, bodyMarkdown TEXT NOT NULL, sortOrder INTEGER NOT NULL, revision INTEGER NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_excerpt_cards_documentId ON excerpt_cards(documentId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_excerpt_cards_anchorId ON excerpt_cards(anchorId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_excerpt_cards_sortOrder ON excerpt_cards(sortOrder)")
    }
}
val MIGRATION_9_10 = object : Migration(9,10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE anchors ADD COLUMN colorKey TEXT NOT NULL DEFAULT 'yellow'")
        db.execSQL("ALTER TABLE anchors ADD COLUMN markRemovedAt INTEGER")
        db.execSQL("ALTER TABLE conversations ADD COLUMN thinkingOverride TEXT NOT NULL DEFAULT 'inherit'")
        db.execSQL("ALTER TABLE excerpt_cards ADD COLUMN cardType TEXT NOT NULL DEFAULT 'excerpt'")
        db.execSQL("ALTER TABLE excerpt_cards ADD COLUMN questionSnapshot TEXT")
    }
}
val MIGRATION_10_11 = object : Migration(10,11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE anchors ADD COLUMN conversationAnchorId TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN sourceAnchorId TEXT")
        db.execSQL("ALTER TABLE anchors ADD COLUMN contextRemovedAt INTEGER")
    }
}
