package com.yuejian.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Bitmap
import com.yuejian.database.*
import com.yuejian.model.*
import com.yuejian.pdf.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class LocalDocuments(private val context: Context, private val database: ReaderDatabase,
    private val cache: PageBitmapCache) : DocumentRepository, PageSource {
    private val dao get() = database.documents()
    private val importLock = Mutex()
    private val files get() = context.filesDir
    private fun DocumentEntity.model() = Document(id, revisionId, title, mediaType, contentHash, pageCount,
        lastPageIndex, lastOffset, createdAt, openedAt, thumbnailPath?.let { File(files, it).path }, zoom, panX, panY)
    override fun observeDocuments() = dao.observeAll().map { rows -> rows.map { it.model() } }
    override fun observeDocument(id: String) = dao.observe(id).map { it?.model() }
    override suspend fun pageInfo(id: String) = dao.pages(id).map { PageInfo(it.pageIndex, PageSize(it.widthPt, it.heightPt)) }
    override suspend fun opened(id: String) = dao.opened(id, System.currentTimeMillis())
    override suspend fun savePosition(id: String, page: Int, zoom: Float, panX: Float, panY: Float) = WriteGate.write {
        val document = dao.find(id) ?: return@write
        require(zoom.isFinite() && panX.isFinite() && panY.isFinite())
        dao.position(id, page.coerceIn(0, document.pageCount-1), zoom.coerceIn(1f, 5f), panX, panY, System.currentTimeMillis())
    }
    override suspend fun moveToTrash(id: String) = WriteGate.write { dao.deleted(id, System.currentTimeMillis(), System.currentTimeMillis()) }
    override suspend fun restore(id: String) = WriteGate.write { dao.deleted(id, null, System.currentTimeMillis()) }
    override fun trimMemory() = cache.clear()
    override suspend fun open(documentId: String): PageSession {
        val document = requireNotNull(dao.find(documentId)) { "文档不存在" }
        require(document.deletedAt == null) { "文档已移至回收站" }
        return openFile(File(files, document.localPath), document.mediaType, document.contentHash)
    }
    private suspend fun openFile(file: File, mime: String, cacheId: String): PageSession {
        if (mime != "application/pdf") return ImagePageSession(file, cache, cacheId)
        val engine = AndroidPdfEngine()
        try { return PdfPageSession(engine, engine.open(file).pageCount, cache, cacheId, PdfTextLayer(file, context.cacheDir)) }
        catch (error: Throwable) { engine.close(); throw error }
    }
    override suspend fun importDocument(sourceUri: String): ImportResult = withContext(Dispatchers.IO) {
        WriteGate.write { importLock.withLock {
            val uri = Uri.parse(sourceUri)
            val resolver = context.contentResolver
            var title = "未命名文档"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) title = it.getString(0) ?: title
            }
            require(!title.endsWith(".pptx", true) && !title.endsWith(".ppt", true)) { "请先将 PPT/PPTX 转换为 PDF 再导入" }
            val id = UUID.randomUUID().toString()
            val staging = File(files, "imports/$id.part").apply { parentFile!!.mkdirs() }
            val directory = File(files, "documents/$id")
            var committed = false
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                requireNotNull(resolver.openInputStream(uri)) { "无法打开文件" }.use { input ->
                    staging.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count); digest.update(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                require(staging.length() > 0) { "文件为空" }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                dao.byHash(hash)?.let {
                    if (it.deletedAt != null) dao.deleted(it.id, null, System.currentTimeMillis())
                    dao.opened(it.id, System.currentTimeMillis())
                    return@withLock ImportResult(it.id, true)
                }
                val header = ByteArray(5)
                staging.inputStream().use { it.read(header) }
                val mime = if (String(header, Charsets.US_ASCII) == "%PDF-") "application/pdf" else "image/*"
                val pages = openFile(staging, mime, hash).use { session ->
                    require(session.pageCount > 0) { "文档没有页面" }
                    (0 until session.pageCount).map { index ->
                        currentCoroutineContext().ensureActive()
                        val size = session.size(index)
                        PageEntity(id, index, (index + 1).toString(), size.width, size.height)
                    }
                }
                directory.mkdirs()
                val source = File(directory, if (mime == "application/pdf") "source.pdf" else "source.image")
                check(staging.renameTo(source)) { "保存私有副本失败" }
                val now = System.currentTimeMillis()
                // No suspension/cancellation between a successful commit and recording its outcome.
                withContext(NonCancellable) {
                    dao.insert(DocumentEntity(id, UUID.randomUUID().toString(), title, mime, hash,
                        source.relativeTo(files).invariantSeparatorsPath, pages.size,
                        createdAt = now, updatedAt = now, openedAt = now), pages)
                    committed = true
                }
                try {
                    val thumb = File(files, "thumbnails/$id/0.png").apply { parentFile!!.mkdirs() }
                    openFile(source, mime, hash).use { session ->
                        val bitmap = session.render(0, 280)
                        thumb.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    dao.thumbnail(id, thumb.relativeTo(files).invariantSeparatorsPath)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A thumbnail failure must not roll back a valid document. */ }
                ImportResult(id, false)
            } finally {
                staging.delete()
                if (!committed) directory.deleteRecursively()
            }
        } }
    }
}
