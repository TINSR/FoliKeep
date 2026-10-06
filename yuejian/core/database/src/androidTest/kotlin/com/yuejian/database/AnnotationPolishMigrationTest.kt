package com.yuejian.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** 旧版标注、对话和精选卡片升级后仍可读，新字段使用安全默认值。 */
class AnnotationPolishMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), ReaderDatabase::class.java
    )

    @Test fun migration9To10PreservesLearningData() {
        val name = "annotation-polish-migration.db"
        helper.createDatabase(name, 9).use { db ->
            db.execSQL("INSERT INTO documents (id,revisionId,title,mediaType,contentHash,localPath,pageCount,lastPageIndex,lastOffset,zoom,panX,panY,createdAt,updatedAt,openedAt,deletedAt,thumbnailPath) VALUES ('doc','rev','书','application/pdf','hash','documents/x.pdf',1,0,0,1,0,0,1,1,1,NULL,NULL)")
            db.execSQL("INSERT INTO anchors (id,documentId,revisionId,pageIndex,kind,quote,boundsJson,rotation,imageAssetId,createdAt,deletedAt) VALUES ('anchor','doc','rev',0,'text','原文','[[0,0,0.1,0.1]]',0,NULL,1,NULL)")
            db.execSQL("INSERT INTO conversations (id,anchorId,draftText,draftQuoteJson,createdAt,updatedAt,title) VALUES ('conv','anchor','旧草稿',NULL,1,1,'主题')")
            db.execSQL("INSERT INTO excerpt_cards (id,documentId,pageIndex,anchorId,sourceConversationId,sourceMessageId,sourceSelection,sourceTextSnapshot,sourceMarkdownSnapshot,sourceKind,sourceQuoteSnapshot,title,bodyMarkdown,sortOrder,revision,createdAt,updatedAt,deletedAt) VALUES ('card','doc',0,'anchor','conv','answer',NULL,'回答','回答','text','原文','标题','正文',1,1,1,1,NULL)")
        }
        helper.runMigrationsAndValidate(name, 10, true, MIGRATION_9_10).use { db ->
            db.query("SELECT quote,colorKey,markRemovedAt FROM anchors WHERE id='anchor'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("原文", row.getString(0))
                assertEquals("yellow", row.getString(1))
                assertTrue(row.isNull(2))
            }
            db.query("SELECT draftText,thinkingOverride FROM conversations WHERE id='conv'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("旧草稿", row.getString(0))
                assertEquals("inherit", row.getString(1))
            }
            db.query("SELECT bodyMarkdown,cardType,questionSnapshot FROM excerpt_cards WHERE id='card'").use { row ->
                assertTrue(row.moveToFirst())
                assertEquals("正文", row.getString(0))
                assertEquals("excerpt", row.getString(1))
                assertTrue(row.isNull(2))
            }
        }
    }
}
