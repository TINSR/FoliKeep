package com.yuejian.model

/**
 * 备份包内清单条目：路径为包内相对路径，哈希用于完整性校验（不宣称防篡改身份认证）。
 */
data class BackupFileEntry(val path: String, val bytes: Long, val sha256: String)

/** 恢复预检报告：数量、预计空间与注意事项，确认后才执行追加恢复。 */
data class RestorePlan(
    val formatVersion: Int,
    val appVersion: String,
    val documents: Int,
    val pages: Int,
    val anchors: Int,
    val conversations: Int,
    val messages: Int,
    val assets: Int,
    val totalBytes: Long,
    val notes: List<String>
)

/** 恢复结果：追加导入统计与冲突/跳过说明；失败时原有资料保持可用。 */
data class RestoreResult(
    val documents: Int,
    val anchors: Int,
    val conversations: Int,
    val messages: Int,
    val skipped: Int,
    val notes: List<String>
)

/**
 * 备份恢复与学习笔记导出。所有路径参数是 SAF 返回的 content URI 字符串。
 * 不导出 API Key、Keystore 材料、模型连接配置与绝对路径。
 */
interface BackupRepository {
    /** 全量备份到目标 URI；onStage 回报“准备/打包/保存”阶段，可随协程取消。 */
    suspend fun exportBackup(targetUri: String, appVersion: String, onStage: (String) -> Unit = {}): Long
    /** 预检备份包：格式版本、清单、哈希、引用关系与路径安全；不写入任何数据。 */
    suspend fun inspectBackup(sourceUri: String): RestorePlan
    /** 追加式恢复：不覆盖本地数据，ID 全量重映射，同一包重复恢复幂等。 */
    suspend fun restoreBackup(sourceUri: String): RestoreResult
    /** 导出学习笔记：纯文字为 .md；含圈图时为 Markdown+assets 的 ZIP（相对路径引用）。 */
    suspend fun exportNotes(documentId: String, anchorIds: List<String>, targetUri: String, onStage: (String) -> Unit = {}): String
}
