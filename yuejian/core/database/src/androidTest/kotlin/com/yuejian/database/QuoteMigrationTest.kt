package com.yuejian.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * schema 2→3（引用追问）迁移的数据完整性验证：
 * 旧消息/草稿必须原样保留，新增引用列可空且默认无引用，迁移后可读写引用。
 * 需在真机/模拟器上运行（connectedAndroidTest）。
 */
class QuoteMigrationTest {
    private val name = "quote-migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), ReaderDatabase::class.java
    )

    @Test fun migration2To3PreservesRowsAndAddsNullableQuoteColumns() {
        helper.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO documents (id,revisionId,title,mediaType,contentHash,localPath,pageCount,lastPageIndex,lastOffset,zoom,panX,panY,createdAt,updatedAt,openedAt) " +
                "VALUES ('doc','rev','book','application/pdf','hash','documents/x.pdf',2,0,0.0,1.0,0.0,0.0,1,1,1)")
            db.execSQL("INSERT INTO anchors (id,documentId,revisionId,pageIndex,kind,quote,boundsJson,rotation,imageAssetId,createdAt,deletedAt) " +
                "VALUES ('anc','doc','rev',0,'text','选中原文','[[0.0,0.0,1.0,1.0]]',0,NULL,1,NULL)")
            db.execSQL("INSERT INTO conversations (id,anchorId,draftText,createdAt,updatedAt) VALUES ('conv','anc','旧草稿',1,1)")
            db.execSQL("INSERT INTO messages (id,conversationId,revision,role,content,status,errorMessage,providerId,modelId,imageAssetId,requestSnapshotJson,createdAt,updatedAt) " +
                "VALUES ('user1','conv',1,'user','问题','complete',NULL,'p','m',NULL,NULL,1,1)")
            db.execSQL("INSERT INTO messages (id,conversationId,revision,role,content,status,errorMessage,providerId,modelId,imageAssetId,requestSnapshotJson,createdAt,updatedAt) " +
                "VALUES ('asst1','conv',1,'assistant','回答','complete',NULL,'p','m',NULL,NULL,2,2)")
            db.execSQL("INSERT INTO messages (id,conversationId,revision,role,content,status,errorMessage,providerId,modelId,imageAssetId,requestSnapshotJson,createdAt,updatedAt) " +
                "VALUES ('user2','conv',1,'user','追问','complete',NULL,'p','m',NULL,NULL,3,3)")
        }
        helper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT draftText, draftQuoteJson FROM conversations").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("旧草稿", c.getString(0))
                assertTrue("旧草稿应默认无引用", c.isNull(1))
            }
            db.query("SELECT COUNT(*), COALESCE(SUM(quoteJson IS NOT NULL),0) FROM messages").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("旧消息必须全部保留", 3, c.getInt(0))
                assertEquals("旧消息应默认无引用", 0, c.getInt(1))
            }
            db.query("SELECT content FROM messages WHERE id='asst1'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("回答", c.getString(0))
            }
            // 迁移后的库可写入并读回引用快照
            db.execSQL("UPDATE messages SET quoteJson='{\"sourceMessageId\":\"asst1\",\"sourceConversationId\":\"conv\",\"textSnapshot\":\"引用选文\"}' WHERE id='user2'")
            db.execSQL("UPDATE conversations SET draftQuoteJson='{\"sourceMessageId\":\"asst1\",\"sourceConversationId\":\"conv\",\"textSnapshot\":\"草稿引用\"}' WHERE id='conv'")
            db.query("SELECT quoteJson FROM messages WHERE id='user2'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.getString(0).contains("引用选文"))
            }
            db.query("SELECT draftQuoteJson FROM conversations").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.getString(0).contains("草稿引用"))
            }
        }
    }
}
