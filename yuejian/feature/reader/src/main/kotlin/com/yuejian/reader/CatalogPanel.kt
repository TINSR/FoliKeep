package com.yuejian.reader

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.yuejian.model.CatalogEntry
import com.yuejian.model.CatalogHit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 标注与问答目录：本书全部高亮/圈图/问答的清单。
 * 宽屏作为左侧抽屉、窄屏作为底部面板嵌入；本体不修改阅读进度。
 */
@Composable fun CatalogPanel(
    vm: CatalogViewModel,
    onSelectEntry: (CatalogEntry) -> Unit,
    onSelectHit: (CatalogHit, CatalogEntry?) -> Unit,
    onDeleteEntry: (CatalogEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rows by vm.rows.collectAsStateWithLifecycle()
    val entries by vm.catalog.collectAsStateWithLifecycle()
    val hits by vm.hits.collectAsStateWithLifecycle()
    val typeFilter by vm.typeFilter.collectAsStateWithLifecycle()
    val onlyPage by vm.onlyCurrentPage.collectAsStateWithLifecycle()
    val sortRecent by vm.sortRecent.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val searchFailed by vm.searchFailed.collectAsStateWithLifecycle()
    val selectionMode by vm.selectionMode.collectAsStateWithLifecycle()
    val selectedIds by vm.selectedIds.collectAsStateWithLifecycle()
    val exportStage by vm.exportStage.collectAsStateWithLifecycle()
    val exportMessage by vm.exportMessage.collectAsStateWithLifecycle()
    val dateTag = remember { SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date()) }
    val mdLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        uri?.let { vm.exportNotes(it.toString()) }
    }
    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.exportNotes(it.toString()) }
    }
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val itemCount = rows.count { it is CatalogRowUi.Item }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    LaunchedEffect(exportMessage) {
        val message = exportMessage ?: return@LaunchedEffect
        delay(4000)
        if (vm.exportMessage.value == message) vm.exportMessage.value = null
    }
    Surface(modifier = modifier, color = scheme.surface, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selectionMode) "选择导出" else "标注与问答", style = typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(if (selectionMode) "${selectedIds.size} 条" else "${if (hits != null) hits!!.size else itemCount}",
                        style = typography.labelMedium, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    if (selectionMode) {
                        TextButton(onClick = { vm.setSelectionMode(false) }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("取消") }
                    } else {
                        IconButton(onClick = { searchOpen = true }, modifier = Modifier.size(40.dp)) {
                            Text("⌕", style = typography.headlineSmall)
                        }
                        Box {
                            IconButton(onClick = { moreOpen = true }, modifier = Modifier.size(40.dp)) {
                                Text("⋯", style = typography.titleLarge)
                            }
                            DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                                DropdownMenuItem(text = { Text(if (sortRecent) "按页码排序" else "最近更新") },
                                    onClick = { vm.sortRecent.value = !sortRecent; moreOpen = false })
                                DropdownMenuItem(text = { Text("导出学习笔记") },
                                    onClick = { vm.setSelectionMode(true); searchOpen = false; vm.onQueryChange(""); moreOpen = false })
                            }
                        }
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) { Text("×", style = typography.titleLarge) }
                }
                when {
                    selectionMode -> Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = vm::selectAll) { Text("全选") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = {
                            val sel = entries.filter { it.anchor.id in selectedIds }
                            val list = sel.ifEmpty { entries }
                            val hasImages = list.any { it.anchor.kind == "region" }
                            val name = "FoliKeep笔记-$dateTag" + if (hasImages) ".zip" else ".md"
                            (if (hasImages) zipLauncher else mdLauncher).launch(name)
                        }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                            Text(if (selectedIds.isEmpty()) "导出全书" else "导出所选")
                        }
                    }
                    searchOpen || query.isNotEmpty() -> OutlinedTextField(query, vm::onQueryChange,
                        modifier = Modifier.fillMaxWidth().height(52.dp), singleLine = true,
                        shape = RoundedCornerShape(10.dp), placeholder = { Text("搜索原文、提问和回答") },
                        trailingIcon = { TextButton(onClick = { searchOpen = false; vm.onQueryChange("") }) { Text("取消") } })
                    else -> Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.width(112.dp).background(scheme.surfaceVariant, RoundedCornerShape(9.dp)).padding(2.dp)) {
                            CatalogScopeTab("全书", !onlyPage, Modifier.weight(1f)) { vm.onlyCurrentPage.value = false }
                            CatalogScopeTab("本页", onlyPage, Modifier.weight(1f)) { vm.onlyCurrentPage.value = true }
                        }
                        Spacer(Modifier.width(6.dp))
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("all" to "全部", "text" to "高亮", "region" to "圈图", "answered" to "问答").forEach { (value, label) ->
                                CatalogTypeChip(label, typeFilter == value) { vm.typeFilter.value = value }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = scheme.outlineVariant)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val hitList = hits
                if (hitList != null) {
                    if (hitList.isEmpty()) {
                        Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (searchFailed) "搜索执行出错，请重试" else "没有匹配的内容", color = if (searchFailed) scheme.error else scheme.onSurfaceVariant)
                            if (searchFailed) TextButton(onClick = vm::retrySearch) { Text("重试") }
                            TextButton(onClick = { vm.onQueryChange("") }) { Text("清空关键词") }
                        }
                    } else {
                        val grouped = hitList.groupBy { it.anchorId }
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            grouped.forEach { (anchorId, list) ->
                                item(key = "hit-$anchorId") {
                                    HitGroupCard(entries.firstOrNull { it.anchor.id == anchorId }, list, onSelectHit)
                                }
                            }
                        }
                    }
                } else if (rows.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (onlyPage) "本页没有标注，长按选字或圈选试试" else "还没有标注。长按选字高亮，或用圈选问 AI。",
                            color = scheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        rows.forEach { row ->
                            when (row) {
                                is CatalogRowUi.Header -> item(key = "h-${row.pageIndex}") { PageHeader(row) }
                                is CatalogRowUi.Item -> item(key = row.entry.anchor.id) {
                                    EntryCard(row.entry,
                                        { if (selectionMode) vm.toggleSelect(row.entry.anchor.id) else onSelectEntry(row.entry) },
                                        vm::thumbnail, selectionMode, row.entry.anchor.id in selectedIds, sortRecent,
                                        { onDeleteEntry(row.entry) },
                                        { vm.toggleSelect(row.entry.anchor.id) })
                                }
                            }
                        }
                    }
                }
                if (exportStage != null || exportMessage != null) {
                    Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                        color = scheme.inverseSurface, shape = RoundedCornerShape(10.dp), shadowElevation = 3.dp) {
                        Text(exportStage?.let { "正在导出：$it…" } ?: exportMessage.orEmpty(),
                            Modifier.padding(12.dp), style = typography.bodySmall, color = scheme.inverseOnSurface)
                    }
                }
            }
        }
    }
}

@Composable private fun CatalogScopeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(9.dp),
        color = if (selected) scheme.surface else scheme.surfaceVariant,
        shadowElevation = if (selected) 1.dp else 0.dp) {
        Box(Modifier.height(36.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable private fun CatalogTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = if (selected) scheme.onSurface else scheme.surface,
        border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant)) {
        Text(label, Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.surface else scheme.onSurfaceVariant)
    }
}

@Composable private fun PageHeader(row: CatalogRowUi.Header) {
    val typography = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 1.dp)) {
        Text("第 ${row.pageIndex + 1} 页", style = typography.labelSmall, fontWeight = FontWeight.SemiBold)
        if (row.isCurrent) Text("当前", style = typography.labelSmall, color = scheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = scheme.outlineVariant)
    }
}

@Composable private fun EntryCard(
    entry: CatalogEntry,
    onClick: () -> Unit,
    thumbnail: suspend (String) -> Bitmap?,
    selecting: Boolean = false,
    selected: Boolean = false,
    showPage: Boolean = false,
    onDelete: () -> Unit = {},
    onToggle: () -> Unit = {}
) {
    val anchor = entry.anchor
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val sourcePreview = anchor.quote.replace('\n', ' ').trim()
    val title = if (anchor.kind == "text") sourcePreview.ifBlank { "第 ${anchor.pageIndex + 1} 页的文字高亮" }
        else entry.lastUserQuestion?.takeIf { it.isNotBlank() }
            ?: entry.title?.takeIf { it.isNotBlank() } ?: "圈选的图片"
    Column {
        var menuOpen by remember { mutableStateOf(false) }
        Surface(onClick = onClick, color = if (selected) scheme.surfaceVariant else scheme.surface,
            modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (selecting) Checkbox(checked = selected, onCheckedChange = { onToggle() }, modifier = Modifier.size(36.dp))
                if (anchor.kind == "region") RegionThumb(anchor.imageAssetId, thumbnail)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = typography.bodyMedium, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (anchor.kind == "text" && entry.hasDiscussion && !entry.lastUserQuestion.isNullOrBlank()) {
                        Text(entry.lastUserQuestion!!, style = typography.labelSmall, color = scheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(buildString {
                            append(if (anchor.kind == "text") "高亮" else "圈图")
                            if(anchor.markRemovedAt!=null)append(" · 原标注已移除")
                            if (showPage) append(" · 第 ${anchor.pageIndex + 1} 页")
                            if (entry.hasDiscussion) append(" · ${entry.messageCount} 条消息")
                            else if(entry.hasDraft)append(" · 草稿")
                        }, style = typography.labelSmall, color = scheme.onSurfaceVariant,
                            maxLines = 1, modifier = Modifier.weight(1f))
                        Text(formatTime(entry.lastActivityAt), style = typography.labelSmall,
                            color = scheme.onSurfaceVariant)
                    }
                }
                if (!selecting) Box {
                    IconButton(onClick={menuOpen=true},modifier=Modifier.size(40.dp)) { Text("⋯") }
                    DropdownMenu(expanded=menuOpen,onDismissRequest={menuOpen=false}) {
                        DropdownMenuItem(text={Text("删除标注")},onClick={menuOpen=false;onDelete()})
                    }
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
    }
}

@Composable private fun HitGroupCard(
    entry: CatalogEntry?,
    hits: List<CatalogHit>,
    onHit: (CatalogHit, CatalogEntry?) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    Surface(shape = RoundedCornerShape(14.dp), color = scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val heading = entry?.title ?: entry?.let { "第 ${it.anchor.pageIndex + 1} 页" } ?: "已失效的标注"
            Text(heading, style = typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${hits.size} 处匹配", style = typography.labelSmall, color = scheme.onSurfaceVariant)
            hits.forEachIndexed { index, hit ->
                if (index > 0) HorizontalDivider(color = scheme.outlineVariant)
                Surface(onClick = { onHit(hit, entry) }, color = scheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(when (hit.hitKind) { "quote" -> "高亮原文"; "user" -> "我的提问"; "title" -> "会话题目"; else -> "AI 回答" },
                            style = typography.labelSmall, color = scheme.onSurfaceVariant)
                        Text(hit.snippet, style = typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable private fun RegionThumb(assetId: String?, load: suspend (String) -> Bitmap?) {
    var bitmap by remember(assetId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(assetId) { if (assetId != null) bitmap = load(assetId) }
    val size = 42.dp
    bitmap?.let {
        Image(it.asImageBitmap(), "圈选缩略图", Modifier.size(size).clip(RoundedCornerShape(9.dp)))
    } ?: Box(Modifier.size(size).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)))
}

private val timeFormat = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))
