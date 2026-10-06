package com.yuejian.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuejian.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 目录列表行：按页码排序时带页眉，按最近更新排序时只有条目。 */
sealed class CatalogRowUi {
    data class Header(val pageIndex: Int, val isCurrent: Boolean) : CatalogRowUi()
    data class Item(val entry: CatalogEntry) : CatalogRowUi()
}

@HiltViewModel class CatalogViewModel @Inject constructor(
    saved: SavedStateHandle, private val repository: AnnotationRepository,
    private val backup: BackupRepository
) : ViewModel() {
    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val exportStage = MutableStateFlow<String?>(null)
    val exportMessage = MutableStateFlow<String?>(null)
    private var exportJob: Job? = null

    fun setSelectionMode(on: Boolean) {
        selectionMode.value = on
        if (!on) selectedIds.value = emptySet()
    }
    fun toggleSelect(anchorId: String) {
        selectedIds.value = if (anchorId in selectedIds.value) selectedIds.value - anchorId else selectedIds.value + anchorId
    }
    fun selectAll() {
        selectedIds.value = rows.value.filterIsInstance<CatalogRowUi.Item>().map { it.entry.anchor.id }.toSet()
    }

    /** 导出学习笔记：未手选时导出本册全部条目；含圈图时为 Markdown+assets ZIP，否则纯 .md。 */
    fun exportNotes(uri: String) {
        if (exportJob?.isActive == true) return
        exportJob = viewModelScope.launch {
            exportMessage.value = null
            try {
                val ids = selectedIds.value.ifEmpty { catalog.value.map { it.anchor.id } }
                val kind = backup.exportNotes(id, ids.toList(), uri) { exportStage.value = it }
                exportMessage.value = if (kind == "zip") "已导出学习笔记（Markdown + 圈图 ZIP）" else "已导出学习笔记（Markdown）"
                setSelectionMode(false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { exportMessage.value = "导出失败：${e.message?.take(120)}" }
            finally { exportStage.value = null }
        }
    }
    private val id: String = checkNotNull(saved["documentId"])
    val typeFilter = MutableStateFlow("all") // all | text | region | answered
    val onlyCurrentPage = MutableStateFlow(false)
    val sortRecent = MutableStateFlow(false)
    val query = MutableStateFlow("")
    val currentPage = MutableStateFlow(0)
    /** null = 不在搜索态；非 null = 命中结果（可能为空列表）。 */
    val hits = MutableStateFlow<List<CatalogHit>?>(null)
    val searchFailed = MutableStateFlow(false)

    val catalog: StateFlow<List<CatalogEntry>> =
        repository.catalog(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rows: StateFlow<List<CatalogRowUi>> =
        combine(catalog, typeFilter, onlyCurrentPage, currentPage, sortRecent) { entries, filter, pageOnly, page, recent ->
            buildRows(entries, filter, pageOnly, page, recent)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // 输入去抖并取消过时查询
        viewModelScope.launch {
            query.collectLatest { q ->
                if (q.isBlank()) { hits.value = null;searchFailed.value = false;return@collectLatest }
                delay(280)
                val result = runCatching { repository.searchCatalog(id, q) }
                searchFailed.value = result.isFailure
                hits.value = result.getOrDefault(emptyList())
            }
        }
    }

    fun onPageChanged(page: Int) { currentPage.value = page }
    fun onQueryChange(text: String) { query.value = text }
    fun retrySearch() {
        val q = query.value
        if (q.isBlank()) return
        viewModelScope.launch {
            val result = runCatching { repository.searchCatalog(id, q) }
            searchFailed.value = result.isFailure
            hits.value = result.getOrDefault(emptyList())
        }
    }

    private fun buildRows(entries: List<CatalogEntry>, filter: String, pageOnly: Boolean, page: Int, recent: Boolean): List<CatalogRowUi> {
        val filtered = entries.filter { entry ->
            when (filter) {
                "text" -> entry.anchor.kind == "text"
                "region" -> entry.anchor.kind == "region"
                "answered" -> entry.hasDiscussion
                else -> true
            }
        }.filter { !pageOnly || it.anchor.pageIndex == page }
        if (recent) {
            return filtered.sortedByDescending { it.lastActivityAt }.map { CatalogRowUi.Item(it) }
        }
        val byPage = filtered.groupBy { it.anchor.pageIndex }.toSortedMap()
        return buildList {
            byPage.forEach { (pageIndex, list) ->
                add(CatalogRowUi.Header(pageIndex, pageIndex == page))
                list.sortedBy { entrySortY(it) }.forEach { add(CatalogRowUi.Item(it)) }
            }
        }
    }

    /** 页内位置按旋转后的归一化 bounds 顶边排，与页面视觉顺序一致。 */
    private fun entrySortY(entry: CatalogEntry): Float =
        entry.anchor.bounds.minOf { rotateRect(it, entry.anchor.rotation).y }

    private val thumbs = LruCache<String, Bitmap>(24)

    suspend fun thumbnail(assetId: String): Bitmap? = withContext(Dispatchers.IO) {
        thumbs.get(assetId) ?: runCatching {
            val bytes = repository.image(assetId)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })
        }.getOrNull()?.also { thumbs.put(assetId, it) }
    }
}
