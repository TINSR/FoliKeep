package com.yuejian.reader

import androidx.compose.ui.graphics.Color

internal val highlightKeys = listOf("gray","yellow","green","blue","pink","purple")
internal fun highlightColor(key: String): Color = when(key) {
    "gray" -> Color(0xFFBDBDBD)
    "green" -> Color(0xFF7CCF9A)
    "blue" -> Color(0xFF79B7EF)
    "pink" -> Color(0xFFEE9DB8)
    "purple" -> Color(0xFFB99AE8)
    else -> Color(0xFFF6D65A)
}
