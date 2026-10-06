package com.yuejian.pdf

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.yuejian.model.NormalizedRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/** Isolated, lazy text adapter. PdfRenderer remains the only page renderer. */
class PdfTextLayer(private val file: File, private val scratch: File) : Closeable {
    private val lock=Any()
    private var document: PDDocument?=null
    private var closed=false
    private val cache=object : LinkedHashMap<Int,List<TextRun>>(8,.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int,List<TextRun>>?)=size>8
    }
    private fun document(): PDDocument {
        check(!closed)
        return document ?: PDDocument.load(file,MemoryUsageSetting.setupTempFileOnly().setTempDir(scratch)).also { document=it }
    }
    suspend fun rotation(page: Int): Int=withContext(Dispatchers.IO) { synchronized(lock) { (document().getPage(page).rotation%360+360)%360 } }
    suspend fun text(page: Int): List<TextRun> = withContext(Dispatchers.IO) {
        synchronized(lock) {
            cache[page] ?: run {
                val doc=document()
                val pdfPage=doc.getPage(page)
                val rotation=(pdfPage.rotation%360+360)%360
                val box=pdfPage.cropBox
                val width=if(rotation%180==0)box.width else box.height
                val height=if(rotation%180==0)box.height else box.width
                val result=mutableListOf<TextRun>()
                val stripper=object : PDFTextStripper() {
                    override fun writeString(text: String, positions: MutableList<TextPosition>) {
                        positions.forEach { p ->
                            if(result.size>=30000 || p.unicode.isNullOrEmpty())return@forEach
                            val direction=((p.dir.toInt()+rotation)%360+360)%360
                            val advance=p.widthDirAdj.coerceAtLeast(.1f)
                            val glyphHeight=(p.heightDir*1.2f).coerceAtLeast(1f)
                            val coordinates=when(direction) {
                                90 -> floatArrayOf(p.x,p.y,glyphHeight,advance)
                                180 -> floatArrayOf(p.x-advance,p.y,advance,glyphHeight)
                                270 -> floatArrayOf(p.x-glyphHeight,p.y-advance,glyphHeight,advance)
                                else -> floatArrayOf(p.x,p.y-glyphHeight,advance,glyphHeight)
                            }
                            val x=(coordinates[0]/width).coerceIn(0f,1f)
                            val y=(coordinates[1]/height).coerceIn(0f,1f)
                            val right=((coordinates[0]+coordinates[2])/width).coerceIn(x,1f)
                            val bottom=((coordinates[1]+coordinates[3])/height).coerceIn(y,1f)
                            if(right>x && bottom>y)result+=TextRun(p.unicode,NormalizedRect(x,y,right-x,bottom-y))
                        }
                        if(result.isNotEmpty())result[result.lastIndex]=result.last().let { it.copy(text=it.text+" ") }
                    }
                }.apply { startPage=page+1;endPage=page+1;sortByPosition=true }
                stripper.getText(doc)
                result.toList().also { cache[page]=it }
            }
        }
    }
    override fun close() = synchronized(lock) { closed=true;cache.clear();document?.close();document=null }
}
