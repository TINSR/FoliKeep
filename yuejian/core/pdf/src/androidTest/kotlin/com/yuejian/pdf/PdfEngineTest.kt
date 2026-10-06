package com.yuejian.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.platform.app.InstrumentationRegistry
import com.yuejian.model.NormalizedRect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PdfEngineTest {
    @Test fun cropsFromPageCoordinatesAndBoundsLargeRenders() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("render", ".pdf", context.cacheDir)
        try {
            val pdf = PdfDocument()
            try {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawRect(300f, 0f, 600f, 800f, Paint().apply { color = Color.BLACK })
                pdf.finishPage(page)
                file.outputStream().use(pdf::writeTo)
            } finally {
                pdf.close()
            }
            AndroidPdfEngine().use { engine ->
                assertEquals(1, engine.open(file).pageCount)
                val crop = engine.render(0, 1200, NormalizedRect(.6f, .1f, .3f, .8f))
                assertEquals(Color.BLACK, crop.getPixel(crop.width/2, crop.height/2))
                assertTrue(crop.allocationByteCount <= 16_000_000)
                val full = engine.render(0, 20_000)
                assertTrue(full.width <= 3072)
                assertTrue(full.allocationByteCount <= 16_000_000)
            }
        } finally { file.delete() }
    }
}
