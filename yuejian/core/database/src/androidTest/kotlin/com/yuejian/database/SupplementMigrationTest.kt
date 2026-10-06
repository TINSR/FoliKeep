package com.yuejian.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SupplementMigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),ReaderDatabase::class.java)
    @Test fun upgradesOldAnnotationsWithoutChangingTheirOwnership() {
        val name="supplement-migration.db"
        helper.createDatabase(name,10).use { db ->
            db.execSQL("INSERT INTO documents (id,revisionId,title,mediaType,contentHash,localPath,pageCount,lastPageIndex,lastOffset,zoom,panX,panY,createdAt,updatedAt,openedAt,deletedAt,thumbnailPath) VALUES ('d','r','资料','application/pdf','h','d.pdf',2,0,0,1,0,0,1,1,1,NULL,NULL)")
            db.execSQL("INSERT INTO anchors (id,documentId,revisionId,pageIndex,kind,quote,boundsJson,rotation,imageAssetId,createdAt,deletedAt,colorKey,markRemovedAt) VALUES ('a','d','r',0,'text','旧原文','[]',0,NULL,1,NULL,'gray',NULL)")
        }
        helper.runMigrationsAndValidate(name,11,true,MIGRATION_10_11).use { db ->
            db.query("SELECT quote,conversationAnchorId FROM anchors WHERE id='a'").use { row ->
                assertTrue(row.moveToFirst());assertEquals("旧原文",row.getString(0));assertTrue(row.isNull(1))
            }
        }
    }
}
