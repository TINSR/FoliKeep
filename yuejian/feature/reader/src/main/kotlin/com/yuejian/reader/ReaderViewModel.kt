package com.yuejian.reader

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuejian.model.*
import com.yuejian.pdf.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import java.io.ByteArrayOutputStream

data class SelectionDraft(val page: Int, val kind: String, val quote: String, val bounds: List<NormalizedRect>)

data class ReaderState(
    val document: Document? = null,
    val pages: List<PageInfo> = emptyList(),
    val page: Int = 0,
    val offset: Float = 0f,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val loading: Boolean = true,
    val error: String? = null
)

@HiltViewModel class ReaderViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repository: DocumentRepository,
    private val source: PageSource,
    private val annotations: AnnotationRepository,
    private val preferences: ReadingPreferences
) : ViewModel() {
    private val id: String = checkNotNull(saved["documentId"])
    private val mutable = MutableStateFlow(ReaderState())
    val state = mutable.asStateFlow()
    val anchors = annotations.anchors(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val selection = MutableStateFlow<SelectionDraft?>(null)
    val selecting = MutableStateFlow(false)
    val regionMode = MutableStateFlow(false)
    val activeAnchor = MutableStateFlow<SourceAnchor?>(null)
    val focusedSourceId = MutableStateFlow<String?>(null)
    val supplementTarget = MutableStateFlow<SourceAnchor?>(null)
    fun beginSupplement(anchor: SourceAnchor) {
        supplementTarget.value=anchor;activeAnchor.value=null;selection.value=null;regionMode.value=false
    }
    fun cancelSupplement() {
        val target=supplementTarget.value
        supplementTarget.value=null;selection.value=null;regionMode.value=false
        if(target!=null)activeAnchor.value=target
    }
    val savingSelection = MutableStateFlow(false)
    val recentColor = preferences.recentHighlightColor.stateIn(viewModelScope, SharingStarted.Eagerly, "gray")
    fun chooseColor(value: String, anchorId: String? = null) { viewModelScope.launch {
        runCatching {
            if(anchorId!=null) annotations.setAnchorColor(anchorId,value)
            preferences.setRecentHighlightColor(value)
            if(anchorId!=null && activeAnchor.value?.id==anchorId)
                activeAnchor.value=activeAnchor.value?.copy(colorKey=value)
        }.onFailure { showNotice("修改高亮颜色失败") }
    } }
    fun removeAnnotation(anchor: SourceAnchor, keepDiscussion: Boolean, onDone: () -> Unit) { viewModelScope.launch {
        runCatching {
            if(keepDiscussion) annotations.removeMark(anchor.id,true)
            else annotations.deleteAnnotation(anchor.id,true)
            if(activeAnchor.value?.id==anchor.id)activeAnchor.value=null
            onDone()
        }.onFailure { showNotice("删除标注失败") }
    } }
    fun restoreAnnotation(anchor: SourceAnchor, keptDiscussion: Boolean, onDone: () -> Unit = {}) { viewModelScope.launch {
        runCatching {
            if(keptDiscussion)annotations.removeMark(anchor.id,false)
            else annotations.deleteAnnotation(anchor.id,false)
            onDone()
        }.onFailure { showNotice("撤销失败") }
    } }
    suspend fun annotationCounts(anchorId: String) = annotations.annotationCounts(anchorId)
    val panelWidth = preferences.answerPanelWidthDp.stateIn(viewModelScope, SharingStarted.Eagerly, 400f)
    val answerTextScale = preferences.answerTextScale.stateIn(viewModelScope, SharingStarted.Eagerly, 1)
    val cardTextScales = preferences.cardTextScales.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    fun saveCardTextScale(cardId: String, scale: Float) {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                runCatching { preferences.setCardTextScale(cardId, scale) }
                    .onFailure { showNotice("卡片字号保存失败") }
            }
        }
    }
    fun savePanelWidth(dp: Float) { viewModelScope.launch { preferences.setAnswerPanelWidthDp(dp) } }
    // null 表示尚未读取；不得用默认值提前初始化悬浮工具球。
    val floatingPos = preferences.floatingToolsPosition(id)
        .map<Pair<Float, Float>, Pair<Float, Float>?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun saveFloatingPos(x: Float, y: Float) {
        viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            // 只保护这次短暂的本地写入，离开文档时也完成最后一次拖动保存。
            withContext(NonCancellable) {
                runCatching { preferences.setFloatingToolsPosition(id,x,y) }
                    .onFailure { showNotice("悬浮位置保存失败") }
            }
        }
    }
    /** 精选卡片（阶段 D）：文档级列表；网格按当前页/选中标注过滤展示。 */
    val cards = annotations.cards(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val cardOrderMutex = Mutex()
    fun editCard(cardId: String, title: String, body: String) { viewModelScope.launch { annotations.editCard(cardId, title, body) } }
    fun deleteCard(cardId: String) { viewModelScope.launch { annotations.deleteCard(cardId, System.currentTimeMillis()) } }
    fun restoreCard(cardId: String) { viewModelScope.launch {
        runCatching { annotations.deleteCard(cardId, null) }
            .onFailure { showNotice(it.message ?: "恢复卡片失败") }
    } }
    fun orderCard(cardId: String, sortOrder: Int) { viewModelScope.launch { annotations.orderCard(cardId, sortOrder) } }
    fun moveCard(cardId: String, targetId: String) { viewModelScope.launch { cardOrderMutex.withLock {
        val ordered = annotations.cards(id).first().toMutableList()
        val from = ordered.indexOfFirst { it.id == cardId }
        val to = ordered.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return@withLock
        val card = ordered.removeAt(from)
        ordered.add(to, card)
        annotations.reorderCards(id, ordered.map { it.id })
    } } }
    fun swapCards(firstId: String, secondId: String) { viewModelScope.launch { cardOrderMutex.withLock {
        val ordered = annotations.cards(id).first().toMutableList()
        val first = ordered.indexOfFirst { it.id == firstId }
        val second = ordered.indexOfFirst { it.id == secondId }
        if (first < 0 || second < 0 || first == second) return@withLock
        java.util.Collections.swap(ordered, first, second)
        annotations.reorderCards(id, ordered.map { it.id })
    } } }
    private var session: PageSession? = null
    private var openJob: Job? = null
    private val writes = Channel<ReaderState>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (position in writes) {
                try { save(position) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { mutable.update { it.copy(error = "阅读位置保存失败，请检查可用空间") } }
                delay(200) // Coalesce scrolling updates instead of writing each frame.
            }
        }
        openDocument()
    }

    private suspend fun save(position: ReaderState) {
        repository.savePosition(id, position.page, position.zoom, position.panX, position.offset)
    }

    fun openDocument() {
        if (openJob?.isActive == true || session != null) return
        mutable.update { it.copy(loading = true, error = null) }
        openJob = viewModelScope.launch {
            try {
                val document = repository.observeDocument(id).first() ?: error("找不到文档")
                val pages = repository.pageInfo(id)
                require(pages.size == document.pageCount && pages.all { it.size.width > 0 && it.size.height > 0 })
                val active = source.open(id)
                session = active
                mutable.value = ReaderState(document = document, pages = pages,
                    page = document.lastPageIndex.coerceIn(pages.indices),
                    offset = document.lastOffset.coerceIn(0f, .999f),
                    zoom = document.zoom.coerceIn(1f, 5f),
                    panX = document.panX.coerceIn(-(document.zoom-1)/2f, (document.zoom-1)/2f),
                    loading = false)
                repository.opened(id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(loading = false, error = "无法打开资料，请返回书架重新导入。") } }
        }
    }

    /** Only visible LazyColumn items request bitmaps; the shared byte cache stays bounded. */
    suspend fun renderPage(page: Int, width: Int): Bitmap =
        checkNotNull(session).render(page, width.coerceIn(64, 3072))

    suspend fun text(page: Int) = checkNotNull(session).text(page)
    fun select(draft: SelectionDraft?) { selection.value=draft }
    fun setRegionMode(value: Boolean) { regionMode.value=value;selection.value=null }
    fun clearError() { mutable.update { it.copy(error=null) } }
    fun showNotice(message: String) { mutable.update { it.copy(error=message) } }
    fun openAnchor(anchor: SourceAnchor) {
        selection.value=null;regionMode.value=false
        viewModelScope.launch {
            runCatching { annotations.discussionAnchor(anchor.id) }.onSuccess {
                focusedSourceId.value=anchor.id;activeAnchor.value=it;supplementTarget.value=null
            }.onFailure { showNotice(it.message ?: "无法打开问答") }
        }
    }
    fun saveSelection(ask: Boolean) {
        val draft=selection.value ?: return
        val target=supplementTarget.value
        if(savingSelection.value)return
        savingSelection.value=true
        viewModelScope.launch {
            try {
                require(draft.quote.length<=16000) { "选中文字过长，请缩小范围后再试" }
                val current=checkNotNull(session)
                val rotation=current.rotation(draft.page)
                val image=if(draft.kind=="region")withContext(Dispatchers.IO) {
                    val rect=draft.bounds.single()
                    val x=(rect.x-.02f).coerceAtLeast(0f);val y=(rect.y-.02f).coerceAtLeast(0f)
                    val right=(rect.x+rect.width+.02f).coerceAtMost(1f);val bottom=(rect.y+rect.height+.02f).coerceAtMost(1f)
                    val bitmap=current.crop(draft.page,NormalizedRect(x,y,right-x,bottom-y))
                    try { ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it);it.toByteArray() } }
                    finally { bitmap.recycle() }
                } else null
                val anchor=annotations.create(id,draft.page,draft.kind,draft.quote,draft.bounds,rotation,image,
                    target?.colorKey ?: recentColor.value,target?.id)
                selection.value=null;regionMode.value=false
                if(target!=null) {
                    supplementTarget.value=null;focusedSourceId.value=anchor.id;activeAnchor.value=target
                } else if(ask) { focusedSourceId.value=anchor.id;activeAnchor.value=anchor }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { showNotice(e.message ?: "无法保存选区，请重试") }
            finally { savingSelection.value=false }
        }
    }

    fun visiblePosition(page: Int, fraction: Float) {
        if (state.value.loading || page !in state.value.pages.indices) return
        mutable.update { it.copy(page = page, offset = fraction.coerceIn(0f, .999f)) }
        writes.trySend(state.value)
    }

    fun transform(factor: Float, pan: Float) {
        val before = state.value
        val zoom = (before.zoom * factor).coerceIn(1f, 5f)
        val limit = (zoom-1f)/2f
        mutable.update { it.copy(zoom = zoom, panX = (before.panX + pan).coerceIn(-limit, limit)) }
        writes.trySend(state.value)
    }

    fun resetZoom() {
        mutable.update { it.copy(zoom = 1f, panX = 0f) }
        writes.trySend(state.value)
    }

    override fun onCleared() {
        val active = session
        val last = state.value
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { if (last.document != null) save(last) }
            finally { active?.close() }
        }
    }
}
