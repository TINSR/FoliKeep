package com.yuejian.model

import java.net.URI

enum class ThinkingProvider { DEEPSEEK, MIMO }

/** Only official endpoints receive vendor-specific thinking parameters. */
fun thinkingProvider(baseUrl: String): ThinkingProvider? {
    val uri = runCatching { URI(baseUrl.trim()) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true)) return null
    val host = uri.host?.lowercase() ?: return null
    return when {
        host == "api.deepseek.com" -> ThinkingProvider.DEEPSEEK
        host == "api.xiaomimimo.com" || host.endsWith(".xiaomimimo.com") -> ThinkingProvider.MIMO
        else -> null
    }
}
