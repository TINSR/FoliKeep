package com.yuejian.files

/** 把用户输入转成 LIKE 参数模式：通配符按字面匹配，配合 ESCAPE '\' 使用。 */
internal fun likePattern(query: String): String = "%" + query
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_") + "%"

/** 取命中处前后各约 28 字的可读摘要，两端加省略号。 */
internal fun excerpt(text: String, query: String, window: Int = 28): String {
    val flat = text.replace('\n', ' ').trim()
    if (flat.isEmpty()) return ""
    val at = flat.indexOf(query, ignoreCase = true)
    if (at < 0) return flat.take(window * 2) + (if (flat.length > window * 2) "…" else "")
    val start = (at - window).coerceAtLeast(0)
    val end = (at + query.length + window).coerceAtMost(flat.length)
    return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
}
