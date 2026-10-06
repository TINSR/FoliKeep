package com.yuejian.database

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DocumentDatabaseTest {
    private inline fun <T> ReaderDatabase.use(block: (ReaderDatabase) -> T): T =
        try { block(this) } finally { close() }
    @Test fun positionAndSoftDeletionPersistAcrossReopen() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, ReaderDatabase::class.java, name).build()
        try {
            open().use { db ->
                db.documents().insert(DocumentEntity("id", "rev", "book", "application/pdf", "hash", "documents/id/source.pdf", 3,
                    createdAt = 1, updatedAt = 1, openedAt = 1), listOf(PageEntity("id", 0, "1", 600f, 800f)))
                db.documents().position("id", 2, 2f, .1f, .2f, 2)
                db.documents().deleted("id", 3, 3)
                assertTrue(db.documents().observeAll().first().isEmpty())
                assertNotNull(db.documents().find("id"))
            }
            open().use { db ->
                db.documents().deleted("id", null, 4)
                val restored = db.documents().observeAll().first().single()
                assertEquals(2, restored.lastPageIndex)
                assertEquals(2f, restored.zoom, .001f)
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun invalidPageInsertRollsBackWholeTransaction() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build().use { db ->
            try {
                db.documents().insert(DocumentEntity("id", "rev", "book", "application/pdf", "hash", "source.pdf", 1,
                    createdAt = 1, updatedAt = 1, openedAt = 1), listOf(PageEntity("missing-parent", 0, "1", 600f, 800f)))
                fail("Expected foreign-key violation")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertNull(db.documents().find("id"))
        }
    }
}
