package com.yuejian.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "documents", indices = [Index(value = ["contentHash"], unique = true)])
data class DocumentEntity(
    @PrimaryKey val id: String,
    val revisionId: String,
    val title: String,
    val mediaType: String,
    val contentHash: String,
    val localPath: String,
    val pageCount: Int,
    val lastPageIndex: Int = 0,
    val lastOffset: Float = 0f,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val createdAt: Long,
    val updatedAt: Long,
    val openedAt: Long,
    val deletedAt: Long? = null,
    val thumbnailPath: String? = null
)
@Entity(tableName = "pages", primaryKeys = ["documentId", "pageIndex"],
    foreignKeys = [ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE)])
data class PageEntity(
    val documentId: String,
    val pageIndex: Int,
    val displayLabel: String,
    val widthPt: Float,
    val heightPt: Float,
    val rotation: Int = 0,
    val textLayerStatus: String = "none",
    val thumbnailPath: String? = null
)
@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents WHERE deletedAt IS NULL ORDER BY openedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE id = :id AND deletedAt IS NULL")
    fun observe(id: String): Flow<DocumentEntity?>
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun find(id: String): DocumentEntity?
    @Query("SELECT * FROM documents WHERE contentHash = :hash LIMIT 1")
    suspend fun byHash(hash: String): DocumentEntity?
    @Query("SELECT * FROM pages WHERE documentId = :id ORDER BY pageIndex")
    suspend fun pages(id: String): List<PageEntity>
    @Insert suspend fun insertDocument(document: DocumentEntity)
    @Insert suspend fun insertPages(pages: List<PageEntity>)
    @Transaction suspend fun insert(document: DocumentEntity, pages: List<PageEntity>) {
        insertDocument(document)
        insertPages(pages)
    }
    @Query("UPDATE documents SET lastPageIndex = :page, zoom = :zoom, panX = :panX, panY = :panY, lastOffset = :panY, updatedAt = :now WHERE id = :id")
    suspend fun position(id: String, page: Int, zoom: Float, panX: Float, panY: Float, now: Long)
    @Query("UPDATE documents SET openedAt = :now WHERE id = :id")
    suspend fun opened(id: String, now: Long)
    @Query("UPDATE documents SET deletedAt = :deletedAt, updatedAt = :now WHERE id = :id")
    suspend fun deleted(id: String, deletedAt: Long?, now: Long)
    @Query("UPDATE documents SET thumbnailPath = :path WHERE id = :id")
    suspend fun thumbnail(id: String, path: String)
    @Query("SELECT localPath FROM documents") suspend fun documentPaths(): List<String>
    @Query("SELECT * FROM documents") suspend fun allDocuments(): List<DocumentEntity>
    @Query("SELECT * FROM pages") suspend fun allPages(): List<PageEntity>
}
@Database(entities = [DocumentEntity::class, PageEntity::class, AssetEntity::class, AnchorEntity::class,
    ConversationEntity::class, MessageEntity::class, RestoreMapEntity::class, UsageRecordEntity::class,
    SummaryEntity::class, ExcerptCardEntity::class], version = 11, exportSchema = true)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun annotations(): AnnotationDao
}
