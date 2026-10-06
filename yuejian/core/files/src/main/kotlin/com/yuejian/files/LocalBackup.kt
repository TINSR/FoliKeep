package com.yuejian.files

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.yuejian.database.*
import com.yuejian.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份恢复与学习笔记导出实现。
 *
 * 导出：冻结写入取得一致快照 -> 私有临时目录流式打包并整包回读校验 -> 写 SAF 目标；
 *      写失败尽力删除不完整目标并报错，绝不提示成功。不导出 API Key / 模型配置 / 绝对路径。
 * 恢复：预检（格式/清单/哈希/路径安全/引用关系）-> 追加导入 + 全量 ID 重映射 ->
 *      文件先落盘并记录待提交清单，Room 事务提交映射与记录，成功后清理；
 *      中断遗留由 recoverPending() 在启动时清理本轮孤立文件（不触碰原有共享文件）。
 */
class LocalBackup(private val context: Context, private val db: ReaderDatabase,
    private val preferences: ReadingPreferences) : BackupRepository {

    companion object {
        const val FORMAT_VERSION = 4
        const val MAX_ENTRIES = 20000
        const val MAX_TOTAL_BYTES = 20L * 1024 * 1024 * 1024
    }

    private val dao get() = db.annotations()
    private val docs get() = db.documents()
    private val files get() = context.filesDir
    private val pendingDir get() = File(files, "restore-pending")

    /** kind x 源ID -> 本地ID；恢复开始时填入历史映射与本轮新映射，供 ID 重写使用。 */
    private val idMap = HashMap<Pair<String, String>, String>()

    private data class Snap(
        val documents: List<DocumentEntity>, val pages: List<PageEntity>,
        val anchors: List<AnchorEntity>, val conversations: List<ConversationEntity>,
        val messages: List<MessageEntity>, val assets: List<AssetEntity>,
        val cards: List<ExcerptCardEntity>
    )

    private data class Parsed(val manifest: JsonObject, val docs: List<JsonObject>, val pages: List<JsonObject>,
        val anchors: List<JsonObject>, val convs: List<JsonObject>, val msgs: List<JsonObject>,
        val assets: List<JsonObject>, val cards: List<JsonObject>, val prefs: JsonObject?)

    // ================= 导出 =================

    override suspend fun exportBackup(targetUri: String, appVersion: String, onStage: (String) -> Unit): Long =
        withContext(Dispatchers.IO) {
            onStage("准备")
            val snap = WriteGate.frozen { db.withTransaction {
                val liveDocs = docs.allDocuments().filter { it.deletedAt == null }
                val liveIds = liveDocs.map { it.id }.toSet()
                val candidates=dao.allAnchors().filter { it.deletedAt==null && it.documentId in liveIds }
                val roots=candidates.filter { it.conversationAnchorId==null }.map { it.id }.toSet()
                val liveAnchors=candidates.filter { it.conversationAnchorId==null || it.conversationAnchorId in roots }
                val anchorIds = liveAnchors.map { it.id }.toSet()
                val liveConvs = dao.allConversations().filter { it.anchorId in anchorIds }
                val convIds = liveConvs.map { it.id }.toSet()
                val liveMsgs = dao.allMessages().filter { it.conversationId in convIds && (it.role!="source" || it.sourceAnchorId in anchorIds) }
                val imageIds = liveAnchors.mapNotNull { it.imageAssetId }.toSet()
                val liveAssets = dao.allAssets().filter { it.id in imageIds }
                Snap(liveDocs, docs.allPages().filter { it.documentId in liveIds }, liveAnchors, liveConvs,
                    liveMsgs, liveAssets, dao.allLiveCards().filter { it.documentId in liveIds })
            } }
            val relPaths = referencedPaths(snap)
            val counts = mapOf(
                "documents" to snap.documents.size, "pages" to snap.pages.size,
                "anchors" to snap.anchors.size, "conversations" to snap.conversations.size,
                "messages" to snap.messages.size, "assets" to snap.assets.size,
                "cards" to snap.cards.size
            )
            onStage("打包")
            val tmp = File(context.cacheDir, "yuejian-backup-${UUID.randomUUID()}.zip")
            val entries = mutableListOf<BackupFileEntry>()
            try {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
                    fun putBytes(path: String, bytes: ByteArray) {
                        zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
                        entries += BackupFileEntry(path, bytes.size.toLong(), sha256(bytes))
                    }
                    fun putText(path: String, text: String) = putBytes(path, text.toByteArray(Charsets.UTF_8))
                    putText("data/documents.json", jsonArray(snap.documents.map { docJson(it) }))
                    putText("data/pages.json", jsonArray(snap.pages.map { pageJson(it) }))
                    putText("data/anchors.json", jsonArray(snap.anchors.map { anchorJson(it) }))
                    putText("data/conversations.json", jsonArray(snap.conversations.map { convJson(it) }))
                    putText("data/messages.json", jsonArray(snap.messages.map { msgJson(it) }))
                    putText("data/assets.json", jsonArray(snap.assets.map { assetJson(it) }))
                    putText("data/cards.json", jsonArray(snap.cards.map { cardJson(it) }))
                    putText("data/preferences.json", prefsJson())
                    for (rel in relPaths) {
                        currentCoroutineContext().ensureActive()
                        val f = File(files, rel)
                        require(f.isFile) { "资料文件缺失，无法完成备份：$rel" }
                        val digest = MessageDigest.getInstance("SHA-256")
                        var size = 0L
                        zip.putNextEntry(ZipEntry("files/$rel"))
                        f.inputStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                zip.write(buffer, 0, n); digest.update(buffer, 0, n); size += n
                            }
                        }
                        zip.closeEntry()
                        entries += BackupFileEntry("files/$rel", size, digest.digest().toHex())
                    }
                    putText("manifest.json", manifestJson(appVersion, counts, entries))
                }
                onStage("校验")
                verifyLocalZip(tmp, entries.size)
                onStage("保存")
                val target = Uri.parse(targetUri)
                val out = context.contentResolver.openOutputStream(target, "wt")
                    ?: error("无法写入目标位置，请重新选择保存目录")
                try {
                    tmp.inputStream().use { input -> out.use { output -> input.copyTo(output) } }
                } catch (e: Exception) {
                    runCatching { context.contentResolver.delete(target, null, null) }
                    throw IllegalStateException("保存失败，未完成的目标文件已尽力清理，请重试", e)
                }
                tmp.length()
            } finally { tmp.delete() }
        }

    private fun referencedPaths(snap: Snap): List<String> {
        val set = LinkedHashSet<String>()
        snap.documents.forEach { d ->
            set += d.localPath
            d.thumbnailPath?.let { set += it }
        }
        snap.pages.forEach { p -> p.thumbnailPath?.let { set += it } }
        snap.assets.forEach { a -> set += a.localPath }
        return set.toList()
    }

    // ================= 预检 =================

    override suspend fun inspectBackup(sourceUri: String): RestorePlan = withContext(Dispatchers.IO) {
        val tmp = copyUriToTemp(sourceUri)
        try {
            ZipFile(tmp).use { zip ->
                val parsed = openAndVerify(zip)
                val notes = mutableListOf<String>()
                notes += "追加恢复：不会覆盖或清空现有数据；同内容书籍复用本地副本。"
                notes += "软删除资料不在备份范围内；恢复的生成中消息将标记为已停止，不会自动重新联网。"
                notes += "精选卡片 ${parsed.cards.size} 张；旧版备份不含卡片时将恢复 0 张。"
                validateReferences(parsed, notes)
                RestorePlan(
                    formatVersion = parsed.manifest.int("formatVersion") ?: FORMAT_VERSION,
                    appVersion = parsed.manifest.str("appVersion").orEmpty(),
                    documents = parsed.docs.size, pages = parsed.pages.size, anchors = parsed.anchors.size,
                    conversations = parsed.convs.size, messages = parsed.msgs.size, assets = parsed.assets.size,
                    totalBytes = parsed.manifest.long("totalBytes") ?: 0L, notes = notes
                )
            }
        } finally { tmp.delete() }
    }

    // ================= 恢复 =================

    override suspend fun restoreBackup(sourceUri: String): RestoreResult = withContext(Dispatchers.IO) {
        val tmp = copyUriToTemp(sourceUri)
        try {
            ZipFile(tmp).use { zip ->
                val parsed = openAndVerify(zip)
                val notes = mutableListOf<String>()
                validateReferences(parsed, notes)
                val sessionId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                idMap.clear()
                dao.allRestoreMaps().forEach { idMap[(it.sourceKind) to it.sourceId] = it.localId }
                val newMaps = mutableListOf<RestoreMapEntity>()
                fun mapped(kind: String, id: String?): String? = id?.let { idMap[kind to it] }
                fun assign(kind: String, srcId: String, localId: String) {
                    idMap[(kind) to srcId] = localId
                    newMaps += RestoreMapEntity(kind, srcId, localId, sessionId, now)
                }
                var skipped = 0

                // ---- 阶段 1：ID 分配与记录物化（不落盘不入库） ----
                val docRows = mutableListOf<DocumentEntity>()
                val pagesOf = HashMap<String, List<PageEntity>>()
                val fileOps = mutableListOf<Pair<String, String>>()
                val docMap = HashMap<String, String>()
                val undelete = mutableListOf<String>()
                for (src in parsed.docs) {
                    val srcId = src.str("id") ?: continue
                    val priorDoc = mapped("document", srcId)
                    if (priorDoc != null) { docMap[srcId] = priorDoc; skipped++; continue }
                    val hash = src.str("contentHash").orEmpty()
                    val existing = docs.byHash(hash)
                    if (existing != null) {
                        docMap[srcId] = existing.id
                        if (existing.deletedAt != null) {
                            undelete += existing.id
                            notes += "本地已有同内容的已删书籍《${existing.title}》，本次直接恢复该书（保留本地记录）。"
                        } else {
                            notes += "书籍《${existing.title}》与本地同内容副本合并（复用本地文件）。"
                        }
                        assign("document", srcId, existing.id)
                        continue
                    }
                    val newId = UUID.randomUUID().toString()
                    docMap[srcId] = newId
                    val srcPath = src.str("localPath") ?: continue
                    val ext = srcPath.substringAfterLast('.', "")
                    val localPath = "documents/$newId/source" + if (ext.isNotEmpty() && ext.length <= 6) ".$ext" else ""
                    val thumb = src.str("thumbnailPath")?.let { "thumbnails/$newId/0.png" }
                    docRows += DocumentEntity(newId, UUID.randomUUID().toString(), src.str("title") ?: "未命名文档",
                        src.str("mediaType") ?: "application/pdf", hash, localPath,
                        src.int("pageCount") ?: 0, src.int("lastPageIndex") ?: 0, src.float("lastOffset") ?: 0f,
                        src.float("zoom") ?: 1f, src.float("panX") ?: 0f, src.float("panY") ?: 0f,
                        src.long("createdAt") ?: now, src.long("updatedAt") ?: now, src.long("openedAt") ?: now,
                        null, thumb)
                    val srcPages = parsed.pages.filter { it.str("documentId") == srcId }
                    pagesOf[newId] = srcPages.map { p ->
                        val pageThumb = p.str("thumbnailPath")?.let { "thumbnails/$newId/p${p.int("pageIndex") ?: 0}.png" }
                        PageEntity(newId, p.int("pageIndex") ?: 0, p.str("displayLabel") ?: "",
                            p.float("widthPt") ?: 0f, p.float("heightPt") ?: 0f, p.int("rotation") ?: 0,
                            p.str("textLayerStatus") ?: "none", pageThumb)
                    }
                    fileOps += "files/$srcPath" to localPath
                    if (thumb != null) src.str("thumbnailPath")?.let { fileOps += "files/$it" to thumb }
                    srcPages.forEach { p ->
                        p.str("thumbnailPath")?.let { from ->
                            fileOps += "files/$from" to "thumbnails/$newId/p${p.int("pageIndex") ?: 0}.png"
                        }
                    }
                    assign("document", srcId, newId)
                }

                val assetRows = mutableListOf<AssetEntity>()
                val assetMap = HashMap<String, String>()
                val allAssetsNow = dao.allAssets()
                for (src in parsed.assets) {
                    val srcId = src.str("id") ?: continue
                    val priorAsset = mapped("asset", srcId)
                    if (priorAsset != null) { assetMap[srcId] = priorAsset; skipped++; continue }
                    val hash = src.str("contentHash").orEmpty()
                    val existing = allAssetsNow.firstOrNull { it.contentHash == hash }
                    if (existing != null) {
                        assetMap[srcId] = existing.id; assign("asset", srcId, existing.id); continue
                    }
                    val newId = UUID.randomUUID().toString()
                    assetMap[srcId] = newId
                    val srcPath = src.str("localPath") ?: continue
                    val localPath = "assets/${hash.take(2)}/$newId.png"
                    assetRows += AssetEntity(newId, src.str("mediaType") ?: "image/png", hash, localPath,
                        src.long("sizeBytes") ?: 0L, src.long("createdAt") ?: now)
                    fileOps += "files/$srcPath" to localPath
                    assign("asset", srcId, newId)
                }

                val anchorRows = mutableListOf<AnchorEntity>()
                val anchorMap = HashMap<String, String>()
                for (src in parsed.anchors) {
                    val srcId = src.str("id") ?: continue
                    val priorAnchor = mapped("anchor", srcId)
                    if (priorAnchor != null) { anchorMap[srcId] = priorAnchor; skipped++; continue }
                    val documentId = docMap[src.str("documentId") ?: ""]
                    if (documentId == null) { notes += "跳过一条无主标注"; continue }
                    val imageId = src.str("imageAssetId")?.let { assetMap[it] }
                    val newId = UUID.randomUUID().toString()
                    anchorMap[srcId] = newId
                    anchorRows += AnchorEntity(newId, documentId, UUID.randomUUID().toString(),
                        src.int("pageIndex") ?: 0, src.str("kind") ?: "text", src.str("quote").orEmpty(),
                        src.str("boundsJson") ?: "[]", src.int("rotation") ?: 0, imageId,
                        src.long("createdAt") ?: now, src.long("deletedAt"),
                        src.str("colorKey")?.takeIf { it in setOf("gray","yellow","green","blue","pink","purple") } ?: "gray",
                        src.long("markRemovedAt"),src.str("conversationAnchorId"),src.long("contextRemovedAt"))
                    assign("anchor", srcId, newId)
                }

                val linkedAnchorRows=anchorRows.map { row -> row.copy(conversationAnchorId=row.conversationAnchorId?.let { anchorMap[it] }) }
                val convRows = mutableListOf<ConversationEntity>()
                val convSrcList = mutableListOf<JsonObject>()
                val convMap = HashMap<String, String>()
                for (src in parsed.convs) {
                    val srcId = src.str("id") ?: continue
                    val priorConv = mapped("conversation", srcId)
                    if (priorConv != null) { convMap[srcId] = priorConv; skipped++; continue }
                    val anchorId = anchorMap[src.str("anchorId") ?: ""]
                    if (anchorId == null) { notes += "跳过一条无主对话"; continue }
                    val newId = UUID.randomUUID().toString()
                    convMap[srcId] = newId
                    convRows += ConversationEntity(newId, anchorId, src.str("draftText").orEmpty(), null,
                        src.long("createdAt") ?: now, src.long("updatedAt") ?: now, src.str("title"),
                        src.str("thinkingOverride")?.takeIf { it in setOf("inherit","enabled","disabled") } ?: "inherit")
                    convSrcList += src
                    assign("conversation", srcId, newId)
                }

                val msgRows = mutableListOf<MessageEntity>()
                for (src in parsed.msgs) {
                    val srcId = src.str("id") ?: continue
                    val priorMsg = mapped("message", srcId)
                    if (priorMsg != null) { skipped++; continue }
                    val convId = convMap[src.str("conversationId") ?: ""]
                    if (convId == null) { notes += "跳过一条无主消息"; continue }
                    val newId = UUID.randomUUID().toString()
                    assign("message", srcId, newId)
                    var status = src.str("status") ?: "complete"
                    var error = src.str("errorMessage")
                    if (status in listOf("queued", "generating")) {
                        status = "stopped"; error = "从备份恢复，未自动重新生成"
                    }
                    msgRows += MessageEntity(newId, convId, src.int("revision") ?: 1, src.str("role") ?: "user",
                        src.str("content").orEmpty(), status, error, src.str("providerId"), src.str("modelId"),
                        src.str("imageAssetId")?.let { assetMap[it] }, src.str("requestSnapshotJson")?.let { remapSnapshot(it) },
                        src.str("quoteJson")?.let { remapQuote(it) }, src.long("createdAt") ?: now, src.long("updatedAt") ?: now,sourceAnchorId=src.str("sourceAnchorId")?.let { anchorMap[it] })
                }
                // 草稿引用快照的 ID 重写（在消息 ID 分配完成后）
                val convRowsRemapped = convRows.mapIndexed { index, row ->
                    row.copy(draftQuoteJson = convSrcList[index].str("draftQuoteJson")?.let { remapQuote(it) })
                }

                val cardRows = mutableListOf<ExcerptCardEntity>()
                val nextOrder = HashMap<String, Int>()
                for (src in parsed.cards.sortedWith(compareBy({ it.str("documentId").orEmpty() },
                        { it.int("sortOrder") ?: 0 }, { it.long("createdAt") ?: 0L }))) {
                    val srcId = src.str("id") ?: continue
                    if (mapped("card", srcId) != null) { skipped++; continue }
                    val documentId = docMap[src.str("documentId") ?: ""]
                    if (documentId == null) { notes += "跳过一张无主卡片"; continue }
                    val order = nextOrder.getOrPut(documentId) {
                        dao.cardsNow(documentId).maxOfOrNull { it.sortOrder } ?: 0
                    } + 1
                    nextOrder[documentId] = order
                    val newId = UUID.randomUUID().toString()
                    cardRows += ExcerptCardEntity(newId, documentId, src.int("pageIndex") ?: 0,
                        mapped("anchor", src.str("anchorId")),
                        mapped("conversation", src.str("sourceConversationId")) ?: "",
                        mapped("message", src.str("sourceMessageId")) ?: "",
                        src.str("sourceSelection"), src.str("sourceTextSnapshot").orEmpty(),
                        src.str("sourceMarkdownSnapshot").orEmpty(), src.str("sourceKind") ?: "text",
                        src.str("sourceQuoteSnapshot"), src.str("title").orEmpty(),
                        src.str("bodyMarkdown").orEmpty(), order, src.int("revision") ?: 1,
                        src.long("createdAt") ?: now, src.long("updatedAt") ?: now, src.long("deletedAt"),
                        src.str("cardType")?.takeIf { it in setOf("excerpt","qa") } ?: "excerpt",
                        src.str("questionSnapshot"))
                    assign("card", srcId, newId)
                }

                // ---- 阶段 2：文件落盘（先记录待提交清单，供中断清理） ----
                val pendingFile = File(pendingDir, "$sessionId.json")
                pendingDir.mkdirs()
                val placed = mutableListOf<String>()
                try {
                    pendingFile.writeText("[]")
                    for ((from, to) in fileOps) {
                        currentCoroutineContext().ensureActive()
                        val entry = zip.getEntry(from) ?: error("备份缺少文件：$from")
                        val target = File(files, to)
                        target.parentFile!!.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                }
                                output.fd.sync()
                            }
                        }
                        placed += to
                        pendingFile.writeText(jsonArray(placed.map { JsonPrimitive(it) }).toString())
                    }

                    // ---- 阶段 3：Room 事务提交全部映射与记录 ----
                    WriteGate.frozen { db.withTransaction {
                        docRows.forEach { docs.insert(it, pagesOf[it.id].orEmpty()) }
                        undelete.forEach { docs.deleted(it, null, now) }
                        dao.importRows(linkedAnchorRows, convRowsRemapped, msgRows, assetRows, cardRows, newMaps)
                    } }
                } catch (e: Exception) {
                    placed.forEach { runCatching { File(files, it).delete() } }
                    pendingFile.delete()
                    throw e
                }
                // ---- 阶段 4：成功后清理待提交清单，并应用外观偏好 ----
                pendingFile.delete()
                parsed.prefs?.let { p ->
                    runCatching {
                        p.int("answerTextScale")?.let { preferences.setAnswerTextScale(it) }
                        p.float("answerPanelWidthDp")?.let { preferences.setAnswerPanelWidthDp(it) }
                        p.boolean("darkTheme")?.let { preferences.setDarkTheme(it) }
                        p.str("recentHighlightColor")?.let { preferences.setRecentHighlightColor(it) }
                    }
                    notes += "已应用备份中的外观偏好（不含模型配置与 API Key）。"
                }
                RestoreResult(documents = docRows.size + undelete.size, anchors = anchorRows.size,
                    conversations = convRowsRemapped.size, messages = msgRows.size, skipped = skipped,
                    notes = notes + "恢复精选卡片 ${cardRows.size} 张")
            }
        } finally { tmp.delete() }
    }

    /** 启动时清理上一轮中断遗留的孤立文件（清单里只含本轮新文件，不触碰共享文件）。 */
    suspend fun recoverPending() = withContext(Dispatchers.IO) {
        val list = pendingDir.listFiles() ?: return@withContext
        for (f in list) {
            val sessionId = f.name.removeSuffix(".json")
            // 关键判据：restore_map 与数据行同一事务提交——查到该会话的映射即视为已提交，
            // 文件已归库所有，只清标记，绝不删除（防“提交后、清标记前”中断窗口误删已提交文件）
            val committed = runCatching { dao.allRestoreMaps().any { it.sessionId == sessionId } }.getOrDefault(false)
            if (committed) {
                f.delete()
                continue
            }
            runCatching {
                Json.parseToJsonElement(f.readText()).jsonArray.forEach { node ->
                    runCatching {
                        val target = File(files, node.jsonPrimitive.content)
                        if (target.canonicalPath.startsWith(files.canonicalPath)) target.delete()
                    }
                }
            }
            f.delete()
        }
    }

    // ---- ID 重映射 ----

    private fun remapQuote(raw: String): String = runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        buildJsonObject {
            obj.forEach { (k, v) -> put(k, v) }
            listOf("sourceMessageId", "sourceConversationId").forEach { key ->
                obj.str(key)?.let { src -> put(key, JsonPrimitive(remapId(key, src))) }
            }
        }.toString()
    }.getOrDefault(raw)

    private fun remapSnapshot(raw: String): String = runCatching {
        val obj = Json.parseToJsonElement(raw).jsonObject
        buildJsonObject {
            obj.forEach { (k, v) -> put(k, v) }
            mapOf("anchorId" to "anchor", "sentImage" to "asset", "retryOf" to "message", "quoteOf" to "message", "questionId" to "message")
                .forEach { (key, kind) -> obj.str(key)?.let { src -> put(key, JsonPrimitive(remapId(kind, src))) } }
            put("requestId", JsonPrimitive(UUID.randomUUID().toString()))
        }.toString()
    }.getOrDefault(raw)

    private fun remapId(kindOrKey: String, src: String): String {
        val kind = when (kindOrKey) {
            "sourceMessageId" -> "message"
            "sourceConversationId" -> "conversation"
            else -> kindOrKey
        }
        return idMap[kind to src] ?: ""
    }

    // ---- 校验与解析 ----

    private fun copyUriToTemp(sourceUri: String): File {
        val tmp = File(context.cacheDir, "yuejian-restore-${UUID.randomUUID()}.zip")
        val input = context.contentResolver.openInputStream(Uri.parse(sourceUri))
            ?: error("无法打开所选文件")
        input.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out) } }
        return tmp
    }

    private fun readManifest(zip: ZipFile): JsonObject {
        val entry = zip.getEntry("manifest.json") ?: error("缺少 manifest.json，不是有效的FoliKeep备份")
        val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        val obj = Json.parseToJsonElement(text).jsonObject
        val version = obj.int("formatVersion") ?: error("备份缺少格式版本信息")
        require(version in 1..FORMAT_VERSION) { "备份格式版本 $version 不支持，请更新应用后再试" }
        require((obj.long("totalBytes") ?: 0L) <= MAX_TOTAL_BYTES) { "备份包超出可恢复大小上限" }
        return obj
    }

    private fun openAndVerify(zip: ZipFile): Parsed {
        val manifest = readManifest(zip)
        if ((manifest.int("formatVersion") ?: 1) >= 2) {
            require(zip.getEntry("data/cards.json") != null) { "备份缺少卡片数据" }
        }
        val filesArr = manifest["files"]?.jsonArray ?: error("备份清单缺少文件列表")
        require(filesArr.size <= MAX_ENTRIES) { "备份包含过多文件条目" }
        val listed = HashMap<String, JsonObject>()
        filesArr.forEach { node ->
            val o = node.jsonObject
            val path = o.str("path") ?: error("清单条目缺少路径")
            requireSafePath(path)
            require(listed.put(path, o) == null) { "备份清单包含重复路径：$path" }
        }
        val seen = HashSet<String>()
        zip.entries().toList().forEach { e ->
            if (e.isDirectory) return@forEach
            requireSafePath(e.name)
            require(seen.add(e.name)) { "备份包含重复路径：${e.name}" }
            // 明确规则：清单不自含哈希（避免循环哈希）；manifest.json 是唯一豁免校验项，
            // 其余任何清单外文件一律拒绝
            require(listed.containsKey(e.name) || e.name == "manifest.json") { "备份包含清单外文件：${e.name}" }
        }
        require(seen.contains("manifest.json")) { "备份缺少 manifest.json" }
        for ((path, o) in listed) {
            val entry = zip.getEntry(path) ?: error("备份缺少文件：$path")
            val declared = o.long("bytes") ?: -1L
            require(entry.size == declared) { "文件大小不符：$path" }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n); size += n
                    require(size <= declared) { "文件超出声明大小：$path" }
                }
            }
            require(digest.digest().toHex() == o.str("sha256")) { "文件校验失败（哈希不符）：$path" }
        }
        fun data(name: String): List<JsonObject> {
            val path = "data/$name"
            if (!listed.containsKey(path)) return emptyList()
            val bytes = zip.getInputStream(zip.getEntry(path)).use { it.readBytes() }
            return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonArray.map { it.jsonObject }
        }
        val prefsNode = if (listed.containsKey("data/preferences.json")) {
            val bytes = zip.getInputStream(zip.getEntry("data/preferences.json")).use { it.readBytes() }
            Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } else null
        return Parsed(manifest, data("documents.json"), data("pages.json"), data("anchors.json"),
            data("conversations.json"), data("messages.json"), data("assets.json"), data("cards.json"), prefsNode)
    }

    private fun validateReferences(parsed: Parsed, notes: MutableList<String>) {
        val docIds = parsed.docs.mapNotNull { it.str("id") }.toSet()
        val anchorIds = parsed.anchors.mapNotNull { it.str("id") }.toSet()
        val convIds = parsed.convs.mapNotNull { it.str("id") }.toSet()
        val assetIds = parsed.assets.mapNotNull { it.str("id") }.toSet()
        val msgIds = parsed.msgs.mapNotNull { it.str("id") }.toSet()
        val anchorById=parsed.anchors.associateBy { it.str("id") }
        val convById=parsed.convs.associateBy { it.str("id") }
        parsed.anchors.forEach { a -> a.str("conversationAnchorId")?.let { rootId ->
            val root=anchorById[rootId]
            require(root!=null && rootId!=a.str("id") && root.str("conversationAnchorId")==null && root.str("documentId")==a.str("documentId")) { "补充资料的问答关联无效" }
        } }
        parsed.msgs.filter { it.str("role")=="source" }.forEach { m ->
            val a=anchorById[m.str("sourceAnchorId")]
            require(a!=null && a.str("conversationAnchorId")==convById[m.str("conversationId")]?.str("anchorId")) { "补充资料记录缺少有效来源" }
        }
        if (parsed.anchors.any { (it.str("documentId") ?: "") !in docIds }) notes += "存在无主标注，恢复时将跳过"
        if (parsed.convs.any { (it.str("anchorId") ?: "") !in anchorIds }) notes += "存在无主对话，恢复时将跳过"
        if (parsed.msgs.any { (it.str("conversationId") ?: "") !in convIds }) notes += "存在无主消息，恢复时将跳过"
        if (parsed.anchors.any { it.str("imageAssetId")?.let { id -> id !in assetIds } == true }) {
            notes += "存在缺失图片资产的圈图，恢复时图片位留空"
        }
        val danglingQuote = parsed.msgs.any { m ->
            m.str("quoteJson")?.let { q ->
                runCatching {
                    val o = Json.parseToJsonElement(q).jsonObject
                    (o.str("sourceMessageId") ?: "") !in msgIds
                }.getOrDefault(false)
            } == true
        }
        if (danglingQuote) notes += "存在引用来源不在包内的消息，引用快照保留文字但无法回跳"
        if (parsed.cards.any { (it.str("documentId") ?: "") !in docIds })
            notes += "存在无主卡片，恢复时将跳过"
        if (parsed.cards.any { c -> c.str("anchorId")?.let { it !in anchorIds } == true ||
                c.str("sourceMessageId")?.let { it !in msgIds } == true })
            notes += "部分卡片的来源不在备份内；正文与快照会保留，来源操作可能不可用"
    }

    private fun verifyLocalZip(tmp: File, expectedEntries: Int) {
        var count = 0
        ZipInputStream(BufferedInputStream(FileInputStream(tmp))).use { zin ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                zin.nextEntry ?: break
                while (zin.read(buffer) >= 0) { /* 读取即校验 CRC */ }
                count++
            }
        }
        require(count == expectedEntries) { "内部校验失败：条目数不符" }
    }

    private fun requireSafePath(path: String) {
        val bad = path.isBlank() || path.startsWith("/") || path.contains("\\") || path.contains(":") ||
            path.split('/').any { it == ".." || it.isEmpty() }
        require(!bad) { "备份包含非法路径：$path" }
    }

    // ---- JSON 读写辅助 ----

    private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull
    private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull
    private fun JsonObject.float(name: String): Float? = this[name]?.jsonPrimitive?.floatOrNull
    private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull
    private fun jsonArray(items: List<JsonElement>): String = JsonArray(items).toString()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun docJson(d: DocumentEntity) = buildJsonObject {
        put("id", d.id); put("revisionId", d.revisionId); put("title", d.title); put("mediaType", d.mediaType)
        put("contentHash", d.contentHash); put("localPath", d.localPath); put("pageCount", d.pageCount)
        put("lastPageIndex", d.lastPageIndex); put("lastOffset", d.lastOffset.toDouble()); put("zoom", d.zoom.toDouble())
        put("panX", d.panX.toDouble()); put("panY", d.panY.toDouble()); put("createdAt", d.createdAt)
        put("updatedAt", d.updatedAt); put("openedAt", d.openedAt); put("thumbnailPath", d.thumbnailPath)
    }
    private fun pageJson(p: PageEntity) = buildJsonObject {
        put("documentId", p.documentId); put("pageIndex", p.pageIndex); put("displayLabel", p.displayLabel)
        put("widthPt", p.widthPt.toDouble()); put("heightPt", p.heightPt.toDouble()); put("rotation", p.rotation)
        put("textLayerStatus", p.textLayerStatus); put("thumbnailPath", p.thumbnailPath)
    }
    private fun anchorJson(a: AnchorEntity) = buildJsonObject {
        put("id", a.id); put("documentId", a.documentId); put("revisionId", a.revisionId); put("pageIndex", a.pageIndex)
        put("kind", a.kind); put("quote", a.quote); put("boundsJson", a.boundsJson); put("rotation", a.rotation)
        put("imageAssetId", a.imageAssetId); put("createdAt", a.createdAt)
        put("deletedAt", a.deletedAt);put("colorKey",a.colorKey);put("markRemovedAt",a.markRemovedAt);put("conversationAnchorId",a.conversationAnchorId);put("contextRemovedAt",a.contextRemovedAt)
    }

    private fun convJson(c: ConversationEntity) = buildJsonObject {
        put("id", c.id); put("anchorId", c.anchorId); put("draftText", c.draftText); put("draftQuoteJson", c.draftQuoteJson)
        put("title", c.title); put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
        put("thinkingOverride",c.thinkingOverride)
    }
    private fun msgJson(m: MessageEntity) = buildJsonObject {
        put("id", m.id); put("conversationId", m.conversationId); put("revision", m.revision); put("role", m.role)
        put("content", m.content); put("status", m.status); put("errorMessage", m.errorMessage)
        put("providerId", m.providerId); put("modelId", m.modelId); put("imageAssetId", m.imageAssetId)
        put("requestSnapshotJson", m.requestSnapshotJson); put("quoteJson", m.quoteJson);put("sourceAnchorId",m.sourceAnchorId)
        put("createdAt", m.createdAt); put("updatedAt", m.updatedAt)
    }
    private fun assetJson(a: AssetEntity) = buildJsonObject {
        put("id", a.id); put("mediaType", a.mediaType); put("contentHash", a.contentHash); put("localPath", a.localPath)
        put("sizeBytes", a.sizeBytes); put("createdAt", a.createdAt)
    }
    private fun cardJson(c: ExcerptCardEntity) = buildJsonObject {
        put("id", c.id); put("documentId", c.documentId); put("pageIndex", c.pageIndex)
        put("anchorId", c.anchorId); put("sourceConversationId", c.sourceConversationId)
        put("sourceMessageId", c.sourceMessageId); put("sourceSelection", c.sourceSelection)
        put("sourceTextSnapshot", c.sourceTextSnapshot)
        put("sourceMarkdownSnapshot", c.sourceMarkdownSnapshot)
        put("sourceKind", c.sourceKind); put("sourceQuoteSnapshot", c.sourceQuoteSnapshot)
        put("title", c.title); put("bodyMarkdown", c.bodyMarkdown)
        put("sortOrder", c.sortOrder); put("revision", c.revision)
        put("createdAt", c.createdAt); put("updatedAt", c.updatedAt)
        put("deletedAt",c.deletedAt);put("cardType",c.cardType);put("questionSnapshot",c.questionSnapshot)
    }

    private suspend fun prefsJson(): String = buildJsonObject {
        put("darkTheme", preferences.darkTheme.first())
        put("answerTextScale", preferences.answerTextScale.first())
        put("answerPanelWidthDp", preferences.answerPanelWidthDp.first().toDouble())
        put("recentHighlightColor",preferences.recentHighlightColor.first())
    }.toString()
    private fun manifestJson(appVersion: String, counts: Map<String, Int>, entries: List<BackupFileEntry>): String =
        buildJsonObject {
            put("formatVersion", FORMAT_VERSION); put("appVersion", appVersion)
            put("createdAt", System.currentTimeMillis())
            put("counts", buildJsonObject { counts.forEach { (k, v) -> put(k, v) } })
            put("totalBytes", entries.sumOf { it.bytes })
            put("files", buildJsonArray {
                entries.forEach {
                    add(buildJsonObject {
                        put("path", it.path); put("bytes", it.bytes); put("sha256", it.sha256)
                    })
                }
            })
        }.toString()

    // ================= 学习笔记导出 =================

    override suspend fun exportNotes(documentId: String, anchorIds: List<String>, targetUri: String, onStage: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            onStage("准备")
            val doc = docs.find(documentId) ?: error("文档不存在")
            val wanted = anchorIds.toSet()
            val anchors = dao.allAnchors().filter { it.id in wanted && it.documentId == documentId && it.deletedAt == null }
                .sortedWith(compareBy<AnchorEntity>({ it.pageIndex }, { it.createdAt }))
            require(anchors.isNotEmpty()) { "没有可导出的条目" }
            val convs = dao.allConversations().associateBy { it.anchorId }
            val activeSources=dao.allAnchors().filter { it.deletedAt==null && it.contextRemovedAt==null }.map { it.id }.toSet()
            val msgs = dao.allMessages().filter { it.role!="source" || it.sourceAnchorId in activeSources }.groupBy { it.conversationId }
            val cards = dao.cardsNow(documentId).filter { it.anchorId in wanted }
            val (markdown, images) = NotesExporter.render(doc, anchors, convs, msgs, cards)
            onStage("保存")
            val target = Uri.parse(targetUri)
            val out = context.contentResolver.openOutputStream(target, "wt") ?: error("无法写入目标位置")
            if (images.isEmpty()) {
                out.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
                "markdown"
            } else {
                val assetFiles = images.mapNotNull { id ->
                    val asset = dao.asset(id) ?: return@mapNotNull null
                    id to File(files, asset.localPath)
                }
                out.use { output ->
                    ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                        zip.putNextEntry(ZipEntry("notes.md"))
                        zip.write(markdown.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        assetFiles.forEach { (id, f) ->
                            require(f.isFile) { "圈图文件缺失：$id" }
                            zip.putNextEntry(ZipEntry("assets/$id.png"))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
                "zip"
            }
        }
}
