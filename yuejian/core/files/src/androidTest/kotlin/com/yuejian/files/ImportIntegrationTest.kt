package com.yuejian.files

import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.yuejian.database.ReaderDatabase
import com.yuejian.pdf.PageBitmapCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ImportIntegrationTest {
    @Test fun pdfImageDuplicateAndReopen() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sources = File(context.cacheDir, "fixtures-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val name = "imports-${java.util.UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, ReaderDatabase::class.java, name).build()
        val importedIds = mutableListOf<String>()
        try {
            var repository = LocalDocuments(context, db, PageBitmapCache())
            val text = File(sources, "text.pdf")
            PdfDocument().use { pdf ->
                repeat(3) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index+1).create())
                    page.canvas.drawText("Offline document ${index+1}", 40f, 80f, Paint().apply { textSize = 24f })
                    pdf.finishPage(page)
                }
                text.outputStream().use(pdf::writeTo)
            }
            val result = repository.importDocument(Uri.fromFile(text).toString())
            importedIds += result.documentId
            assertFalse(result.duplicate)
            assertEquals(3, repository.observeDocument(result.documentId).first()!!.pageCount)
            assertTrue(repository.importDocument(Uri.fromFile(text).toString()).duplicate)
            repository.savePosition(result.documentId, 2, 2f, .1f, .2f)
            repository.open(result.documentId).use { session ->
                assertEquals(3, session.pageCount)
                val rendered = session.render(2, 1000)
                assertTrue(rendered.width > 0)
                assertTrue(rendered.allocationByteCount <= 16_000_000)
            }
            repository.moveToTrash(result.documentId)
            assertTrue(repository.observeDocuments().first().isEmpty())
            assertTrue(repository.importDocument(Uri.fromFile(text).toString()).duplicate)
            text.delete() // Private copy must remain readable after the source disappears.
            db.close()
            db = Room.databaseBuilder(context, ReaderDatabase::class.java, name).build()
            repository = LocalDocuments(context, db, PageBitmapCache())
            val document = repository.observeDocument(result.documentId).first()!!
            assertEquals(2, document.lastPageIndex)
            assertEquals(2f, document.zoom, .001f)
            repository.open(result.documentId).use { assertTrue(it.render(2, 480).height > 0) }
            val image = File(sources, "scan.png")
            val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            Canvas(bitmap).drawRect(40f, 40f, 200f, 200f, Paint().apply { color = Color.BLACK })
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val imageResult = repository.importDocument(Uri.fromFile(image).toString())
            importedIds += imageResult.documentId
            repository.open(imageResult.documentId).use { assertEquals(1, it.pageCount); assertTrue(it.render(0, 280).width > 0) }
            val broken = File(sources, "broken.pdf").apply { writeText("%PDF-invalid") }
            try { repository.importDocument(Uri.fromFile(broken).toString()); fail("Corrupt PDF accepted") }
            catch (_: java.io.IOException) { }
            catch (_: IllegalArgumentException) { }
            assertEquals(2, repository.observeDocuments().first().size)
            assertTrue(File(context.filesDir, "imports").listFiles().orEmpty().isEmpty())
        } finally {
            db.close(); context.deleteDatabase(name); sources.deleteRecursively()
            importedIds.forEach {
                File(context.filesDir, "documents/$it").deleteRecursively()
                File(context.filesDir, "thumbnails/$it").deleteRecursively()
            }
        }
    }
}
