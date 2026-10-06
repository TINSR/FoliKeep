package com.yuejian.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

object PdfPlatform {
    fun initialize(context: Context) { PDFBoxResourceLoader.init(context.applicationContext) }
}
