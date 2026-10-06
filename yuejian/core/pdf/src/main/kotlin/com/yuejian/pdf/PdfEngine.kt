package com.yuejian.pdf

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.yuejian.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import kotlin.math.*

data class PdfDocumentInfo(val pageCount: Int)
data class TextRun(val text: String, val bounds: NormalizedRect)
data class TextHit(val bounds: NormalizedRect)
interface PdfEngine : Closeable {
    suspend fun open(file: File): PdfDocumentInfo
    suspend fun render(pageIndex: Int, targetWidthPx: Int, crop: NormalizedRect? = null): Bitmap
    suspend fun pageSize(pageIndex: Int): PageSize
    suspend fun extractText(pageIndex: Int): List<TextRun>
    suspend fun search(pageIndex: Int, query: String): List<TextHit>
}
/** One renderer per session; every native call and close share the same lock. */
class AndroidPdfEngine : PdfEngine {
    private val lock = Any()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    override suspend fun open(file: File): PdfDocumentInfo = withContext(Dispatchers.IO) {
        synchronized(lock) {
            close()
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val pdf = PdfRenderer(fd)
                descriptor = fd
                renderer = pdf
                PdfDocumentInfo(pdf.pageCount)
            } catch (error: Throwable) { fd.close(); throw error }
        }
    }
    override suspend fun pageSize(pageIndex: Int): PageSize = withContext(Dispatchers.IO) {
        synchronized(lock) {
            checkNotNull(renderer).openPage(pageIndex).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
        }
    }
    override suspend fun render(pageIndex: Int, targetWidthPx: Int, crop: NormalizedRect?): Bitmap = withContext(Dispatchers.IO) {
        synchronized(lock) {
            checkNotNull(renderer).openPage(pageIndex).use { page ->
                val rect = crop ?: NormalizedRect(0f, 0f, 1f, 1f)
                require(rect.width > 0 && rect.height > 0)
                val ratio = page.height * rect.height / (page.width * rect.width)
                val width = targetWidthPx.coerceIn(64, 3072).coerceAtMost(sqrt(4_000_000f / ratio).toInt().coerceAtLeast(1))
                val height = (width * ratio).toInt().coerceIn(1, 4_000_000 / width)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                val scale = width / (page.width * rect.width)
                val matrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(-rect.x * page.width * scale, -rect.y * page.height * scale)
                }
                try { page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                catch (error: Throwable) { bitmap.recycle(); throw error }
                bitmap
            }
        }
    }
    // Stage 1 exposes no text-selection UI. Never invent a text layer.
    override suspend fun extractText(pageIndex: Int): List<TextRun> = emptyList()
    override suspend fun search(pageIndex: Int, query: String): List<TextHit> = emptyList()
    override fun close() = synchronized(lock) {
        renderer?.close(); renderer = null
        descriptor?.close(); descriptor = null
    }
}
interface PageSession : Closeable {
    val pageCount: Int
    suspend fun size(page: Int): PageSize
    suspend fun render(page: Int, width: Int): Bitmap
    suspend fun text(page: Int): List<TextRun> = emptyList()
    suspend fun rotation(page: Int): Int = 0
    suspend fun crop(page: Int, rect: NormalizedRect): Bitmap
}
interface PageSource {
    suspend fun open(documentId: String): PageSession
    fun trimMemory()
}
class PageBitmapCache(maxBytes: Int = 32 * 1024 * 1024) {
    private val cache = object : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        // Compose may still display an evicted bitmap. Let GC reclaim it, never recycle it here.
    }
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) { cache.put(key, bitmap) }
    fun clear() = cache.evictAll()
}
class PdfPageSession(private val engine: PdfEngine, override val pageCount: Int,
    private val cache: PageBitmapCache, private val cacheId: String,
    private val textLayer: PdfTextLayer) : PageSession {
    override suspend fun text(page: Int) = textLayer.text(page)
    override suspend fun rotation(page: Int) = textLayer.rotation(page)
    override suspend fun crop(page: Int, rect: NormalizedRect): Bitmap {
        val size=size(page)
        val ratio=size.height*rect.height/(size.width*rect.width)
        return engine.render(page, minOf(2000f,2000f/ratio).toInt().coerceAtLeast(64),rect)
    }
    override suspend fun size(page: Int) = engine.pageSize(page)
    override suspend fun render(page: Int, width: Int): Bitmap {
        val key = "$cacheId:$page:$width"
        return cache.get(key) ?: engine.render(page, width).also { cache.put(key, it) }
    }
    override fun close() { try { engine.close() } finally { textLayer.close() } }
}
class ImagePageSession(private val file: File, private val cache: PageBitmapCache, private val cacheId: String) : PageSession {
    override val pageCount = 1
    override suspend fun size(page: Int): PageSize = withContext(Dispatchers.IO) {
        require(page == 0)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "无法读取图片" }
        PageSize(options.outWidth.toFloat(), options.outHeight.toFloat())
    }
    override suspend fun render(page: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        require(page == 0)
        val key = "$cacheId:0:$width"
        cache.get(key) ?: run {
            val size = size(0)
            val target = min(width.coerceIn(64, 3072).toFloat(), sqrt(4_000_000f * size.width / size.height))
            var sample = 1
            while (size.width / sample > target * 1.5f || (size.width / sample) * (size.height / sample) > 4_000_000f) sample *= 2
            val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })) { "图片已损坏" }
            cache.put(key, bitmap)
            bitmap
        }
    }
    override suspend fun crop(page: Int, rect: NormalizedRect): Bitmap = withContext(Dispatchers.IO) {
        require(page==0 && rect.width>0 && rect.height>0)
        @Suppress("DEPRECATION")
        val decoder=BitmapRegionDecoder.newInstance(file.path,false) ?: error("无法裁剪图片")
        try {
            val left=(rect.x*decoder.width).toInt().coerceIn(0,decoder.width-1)
            val top=(rect.y*decoder.height).toInt().coerceIn(0,decoder.height-1)
            val right=((rect.x+rect.width)*decoder.width).roundToInt().coerceIn(left+1,decoder.width)
            val bottom=((rect.y+rect.height)*decoder.height).roundToInt().coerceIn(top+1,decoder.height)
            var sample=1
            while(maxOf(right-left,bottom-top)/sample>2400)sample*=2
            requireNotNull(decoder.decodeRegion(Rect(left,top,right,bottom),BitmapFactory.Options().apply { inSampleSize=sample }))
        } finally { decoder.recycle() }
    }
    override fun close() = Unit
}
