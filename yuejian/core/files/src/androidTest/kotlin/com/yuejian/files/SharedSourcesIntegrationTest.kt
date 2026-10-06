package com.yuejian.files

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.yuejian.database.ReaderDatabase
import com.yuejian.model.NormalizedRect
import com.yuejian.pdf.PageBitmapCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

internal inline fun <T> PdfDocument.use(block: (PdfDocument) -> T): T =
    try { block(this) } finally { close() }

class SharedSourcesIntegrationTest {
    @Test fun crossPageSourcesShareOneConversationAndSurviveBackup() = runTest {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,ReaderDatabase::class.java).build()
        val restored=Room.inMemoryDatabaseBuilder(context,ReaderDatabase::class.java).build()
        val fixture=File(context.cacheDir,"sources-${UUID.randomUUID()}.pdf")
        val backupFile=File(context.cacheDir,"sources-${UUID.randomUUID()}.zip")
        val ownedPaths=mutableSetOf<String>()
        try {
            PdfDocument().use { pdf ->
                repeat(3) { i ->
                    val page=pdf.startPage(PdfDocument.PageInfo.Builder(595,842,i+1).create())
                    page.canvas.drawText("Definition n",30f,50f,Paint().apply { textSize=20f })
                    pdf.finishPage(page)
                }
                fixture.outputStream().use(pdf::writeTo)
            }
            val doc=LocalDocuments(context,db,PageBitmapCache()).importDocument(Uri.fromFile(fixture).toString()).documentId
            val repo=LocalAnnotations(context,db)
            val bounds=listOf(NormalizedRect(.1f,.1f,.4f,.1f))
            val root=repo.create(doc,0,"text","当 n=2 时",bounds,0,null)
            val conv=repo.ensureConversation(root.id)
            val answer=repo.enqueue(root.id,"n 是什么",model="m",provider="p",withImage=false)
            repo.updateAnswer(answer.answerId,"请补充定义。","complete")
            val child=repo.create(doc,1,"text","n 是中心矩的阶数",bounds,0,null,conversationAnchorId=root.id)
            assertEquals(root.id,repo.discussionAnchor(child.id).id)
            assertEquals(conv,repo.ensureConversation(child.id))
            assertEquals(conv,repo.conversation(child.id).first()!!.id)
            assertEquals(child.id,repo.messages(conv).first().last().sourceAnchorId)
            assertEquals(1,db.annotations().allConversations().size)
            val backup=LocalBackup(context,db,LocalReadingPreferences(context))
            backup.exportBackup(Uri.fromFile(backupFile).toString(),"1.1.0") {}
            val restore=LocalBackup(context,restored,LocalReadingPreferences(context))
            assertEquals(4,restore.inspectBackup(Uri.fromFile(backupFile).toString()).formatVersion)
            restore.restoreBackup(Uri.fromFile(backupFile).toString())
            val rows=restored.annotations().allAnchors()
            val restoredRoot=rows.single { it.conversationAnchorId==null }
            val restoredChild=rows.single { it.conversationAnchorId!=null }
            assertEquals(restoredRoot.id,restoredChild.conversationAnchorId)
            assertNotEquals(root.id,restoredRoot.id)
            assertEquals(restoredChild.id,restored.annotations().allMessages().single { it.role=="source" }.sourceAnchorId)
            restore.restoreBackup(Uri.fromFile(backupFile).toString())
            assertEquals(2,restored.annotations().allAnchors().size)
            repo.deleteAnnotation(child.id,true)
            assertEquals(1,repo.anchors(doc).first().size)
            assertEquals(conv,repo.ensureConversation(root.id))
        } finally {
            listOf(db,restored).forEach { database ->
                ownedPaths+=database.documents().allDocuments().flatMap { listOfNotNull(it.localPath,it.thumbnailPath) }
                ownedPaths+=database.annotations().allAssets().map { it.localPath }
                database.close()
            }
            ownedPaths.forEach { File(context.filesDir,it).delete() }
            fixture.delete();backupFile.delete()
        }
    }
}
