package com.yuejian.files

import com.yuejian.database.AnchorEntity
import com.yuejian.database.ConversationEntity
import com.yuejian.database.DocumentEntity
import com.yuejian.database.MessageEntity
import com.yuejian.database.ExcerptCardEntity

/**
 * 学习笔记渲染：保留 Markdown 与原始 LaTeX 原文；引用、提问、回答分区展示；
 * 失败/停止/无正文的回答明确标记状态，不生成误导性的完整回答。
 * 返回笔记正文与引用到的圈图资产 ID（调用方按相对路径 assets/<id>.png 打包）。
 */
object NotesExporter {
    fun render(
        document: DocumentEntity,
        anchors: List<AnchorEntity>,
        conversations: Map<String, ConversationEntity>,
        messagesByConv: Map<String, List<MessageEntity>>,
        cards: List<ExcerptCardEntity> = emptyList()
    ): Pair<String, List<String>> {
        val images = mutableListOf<String>()
        val out = StringBuilder()
        out.append("# 《${document.title}》学习笔记\n\n")
        out.append("- 书名：${document.title}\n")
        out.append("- 导出时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}\n")
        out.append("- 共 ${anchors.size} 条标注\n\n---\n")
        val exportedConversations=mutableSetOf<String>()
        anchors.forEachIndexed { index, anchor ->
            val page = anchor.pageIndex + 1
            val kind = if (anchor.kind == "text") "高亮" else "圈图"
            out.append("\n## ${index + 1}. 第 $page 页 · $kind\n\n")
            if (anchor.kind == "text") {
                out.append("**原文摘录：**\n\n")
                anchor.quote.trim().lines().forEach { out.append("> $it\n") }
                out.append("\n")
            } else {
                anchor.imageAssetId?.let {
                    images += it
                    out.append("**圈选原图：**\n\n![圈选原图](assets/$it.png)\n\n")
                }
            }
            val conversation = conversations[anchor.conversationAnchorId ?: anchor.id]
            if(conversation!=null && !exportedConversations.add(conversation.id)) {
                out.append("（此资料与前面的标注共享同一问答；对话不重复导出。）\n");return@forEachIndexed
            }
            val messages = conversation?.let { messagesByConv[it.id].orEmpty() }.orEmpty()
                .sortedBy { it.createdAt }
            if (messages.isEmpty()) {
                out.append("（此标注暂无问答记录。）\n")
                return@forEachIndexed
            }
            messages.forEach { message ->
                when (message.role) {
                    "user" -> {
                        out.append("\n**问：**\n\n")
                        quoteJson(message.quoteJson)?.let { quote ->
                            out.append("> 引用 AI 回答：${quote.lines().joinToString("\n> ") { it }}\n\n")
                        }
                        out.append(message.content.trim() + "\n")
                    }
                    "source" -> {
                        out.append("\n**补充资料：**\n\n${message.content.trim()}\n")
                        message.imageAssetId?.let { images+=it;out.append("\n![补充原图](assets/$it.png)\n") }
                    }
                    else -> {
                        out.append("\n**答**")
                        when (message.status) {
                            "stopped" -> out.append("（已停止：以下为部分回答）")
                            "failed" -> out.append("（生成失败${message.errorMessage?.let { "：$it" } ?: ""}）")
                            "queued", "generating" -> out.append("（未完成）")
                        }
                        out.append("：\n\n")
                        val body = message.content.trim()
                        if (body.isEmpty()) out.append("（未生成回答。）\n")
                        else out.append(body + "\n")
                    }
                }
            }
            conversation?.title?.let { out.append("\n*会话题目：$it*\n") }
        }
        if(cards.isNotEmpty()) {
            out.append("\n---\n\n# 精选卡片\n")
            cards.sortedWith(compareBy({it.pageIndex},{it.sortOrder})).forEach { card ->
                out.append("\n## 第 ${card.pageIndex+1} 页 · ${if(card.cardType=="qa")"问答卡片" else "摘录卡片"}\n\n")
                if(card.cardType=="qa") {
                    out.append("**问题：**\n\n${card.title.trim()}\n\n")
                    out.append("**回答：**\n\n${card.bodyMarkdown.trim()}\n")
                } else {
                    out.append("**${card.title.trim()}**\n\n${card.bodyMarkdown.trim()}\n")
                }
            }
        }
        return out.toString() to images
    }

    /** quoteJson 里的 textSnapshot 作为可读引用展示。 */
    private fun quoteJson(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val node = kotlinx.serialization.json.Json.parseToJsonElement(raw)
            val obj = node as? kotlinx.serialization.json.JsonObject
            val text = obj?.get("textSnapshot") as? kotlinx.serialization.json.JsonPrimitive
            text?.content
        }.getOrNull()?.take(400)
    }
}
