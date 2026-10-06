package com.yuejian.reader

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yuejian.conversation.ConversationPanel
import com.yuejian.model.*
import com.yuejian.pdf.TextRun
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuejian.model.PageInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 一次性锚点跳转请求：token 防止重组重复触发定位。 */
data class AnchorJump(val pageIndex: Int, val anchorId: String, val token: Long)
private data class UndoAnnotation(val token: Long, val anchor: SourceAnchor, val keptDiscussion: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ReaderScreen(onBack: () -> Unit, onSettings: () -> Unit, vm: ReaderViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val region by vm.regionMode.collectAsStateWithLifecycle()
    val active by vm.activeAnchor.collectAsStateWithLifecycle()
    val supplementTarget by vm.supplementTarget.collectAsStateWithLifecycle()
    val focusedSourceId by vm.focusedSourceId.collectAsStateWithLifecycle()
    val saving by vm.savingSelection.collectAsStateWithLifecycle()
    val recentColor by vm.recentColor.collectAsStateWithLifecycle()
    val answerTextScale by vm.answerTextScale.collectAsStateWithLifecycle()
    val cardTextScales by vm.cardTextScales.collectAsStateWithLifecycle()
    var colorPicker by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<SourceAnchor?>(null) }
    var deleteCounts by remember { mutableStateOf(0 to false) }
    val undoQueue=remember { mutableStateListOf<UndoAnnotation>() }
    var undoToken by remember { mutableLongStateOf(0L) }
    fun queueUndo(anchor: SourceAnchor, keptDiscussion: Boolean) {
        undoQueue.add(UndoAnnotation(++undoToken,anchor,keptDiscussion))
    }
    val uiScope=rememberCoroutineScope()
    var jump by rememberSaveable { mutableStateOf(false) }
    var pageInput by rememberSaveable { mutableStateOf("") }
    var jumpTarget by remember { mutableStateOf<Int?>(null) }
    var answerWidth by rememberSaveable { mutableFloatStateOf(400f) }
    val savedWidth by vm.panelWidth.collectAsStateWithLifecycle()
    var widthLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(savedWidth) { if(!widthLoaded) { widthLoaded=true;answerWidth=savedWidth } }
    var catalogOpen by rememberSaveable { mutableStateOf(false) }
    var showCards by rememberSaveable { mutableStateOf(false) }
    var cardsMounted by rememberSaveable { mutableStateOf(false) }
    val cardGridState = rememberLazyGridState()
    var cardEntry by remember { mutableStateOf<Offset?>(null) }
    var cardQuote by remember { mutableStateOf<Pair<String,String>?>(null) }
    val cards by vm.cards.collectAsStateWithLifecycle()
    val anchorsList by vm.anchors.collectAsStateWithLifecycle()
    val activeNow = active
    val scopedCards = when {
        selection != null -> emptyList() // 临时选区还没有保存锚点，不能沿用上一次问答的卡片
        activeNow != null -> cards.filter { it.anchorId == activeNow.id }
        else -> cards // 默认显示本书全部卡片；本页筛选在卡片墙中切换
    }
    LaunchedEffect(active) { if(active==null) cardQuote=null }
    val catalogVm: CatalogViewModel = hiltViewModel()
    var anchorJump by remember { mutableStateOf<AnchorJump?>(null) }
    var highlightAnchorId by remember { mutableStateOf<String?>(null) }
    var askBar by remember { mutableStateOf<SourceAnchor?>(null) }
    var scrollMessageId by remember { mutableStateOf<String?>(null) }
    var jumpToken by remember { mutableLongStateOf(0L) }
    var immersive by rememberSaveable { mutableStateOf(false) }
    var lastImmersiveToggle by remember { mutableLongStateOf(0L) }
    // 横屏问答栏在全屏阅读时隐藏；一旦选区或已有标注打开问答，退出全屏以保证面板可见。
    LaunchedEffect(active?.id, immersive) {
        if (active != null && immersive) immersive = false
    }
    fun toggleImmersive() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastImmersiveToggle < 350) return // 双层手势入口可能同时触发
        lastImmersiveToggle = now
        immersive = !immersive
    }
    LaunchedEffect(state.page) { catalogVm.onPageChanged(state.page) }
    LaunchedEffect(highlightAnchorId) { if(highlightAnchorId!=null) { kotlinx.coroutines.delay(1800);highlightAnchorId=null } }
    LaunchedEffect(active) { if(active==null) scrollMessageId=null }
    fun openEntry(entry: CatalogEntry) {
        // 打开目录不修改阅读进度；点条目才跳转，并用一次性 token 防重复定位
        catalogOpen=false
        anchorJump=AnchorJump(entry.anchor.pageIndex,entry.anchor.id,++jumpToken)
        highlightAnchorId=entry.anchor.id
        if(entry.hasDiscussion || entry.hasDraft) { askBar=null;vm.openAnchor(entry.anchor) } else askBar=entry.anchor
    }
    fun openHit(hit: CatalogHit, entry: CatalogEntry?) {
        catalogOpen=false
        val anchor=entry?.anchor ?: return
        anchorJump=AnchorJump(anchor.pageIndex,anchor.id,++jumpToken)
        highlightAnchorId=anchor.id
        if(hit.messageId!=null || entry.hasDiscussion) {
            askBar=null
            vm.openAnchor(anchor)
            scrollMessageId=hit.messageId
        } else askBar=anchor
    }
    val clipboard=LocalClipboardManager.current
    fun requestDelete(anchor: SourceAnchor) {
        uiScope.launch {
            deleteCounts=vm.annotationCounts(anchor.id)
            if(deleteCounts.first==0 && !deleteCounts.second) {
                vm.removeAnnotation(anchor,false) {
                    if(askBar?.id==anchor.id)askBar=null
                    queueUndo(anchor,false);catalogVm.retrySearch()
                }
            } else deleteCandidate=anchor
        }
    }
    LaunchedEffect(undoQueue.firstOrNull()?.token) {
        val token=undoQueue.firstOrNull()?.token ?: return@LaunchedEffect
        delay(5000)
        if(undoQueue.firstOrNull()?.token==token)undoQueue.removeAt(0)
    }
    val count=state.document?.pageCount ?: 1
    val density=LocalDensity.current
    BackHandler(enabled=immersive || active!=null || selection!=null || region || supplementTarget!=null) {
        if(saving)return@BackHandler
        when {
            supplementTarget!=null -> vm.cancelSupplement()
            immersive -> immersive=false
            active!=null -> vm.activeAnchor.value=null
            selection!=null -> vm.select(null)
            else -> vm.setRegionMode(false)
        }
    }
    Box(Modifier.fillMaxSize()) {
    Scaffold(topBar={
        AnimatedVisibility(visible=!immersive,enter=slideInVertically{-it}+fadeIn(),exit=slideOutVertically{-it}+fadeOut()) {
            TopAppBar(title={Column {
                Text(state.document?.title ?: "正在打开…",maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
        }},navigationIcon={TextButton(onClick=onBack){Text("书架")}},actions={
            TextButton(onClick={cardsMounted=true;showCards=!showCards},enabled=state.document!=null,
                modifier=Modifier.onGloballyPositioned { cardEntry=it.boundsInWindow().center }){Text("卡片 ${scopedCards.size}")}
            TextButton(onClick={vm.transform(1/1.25f,0f)},enabled=state.zoom>1f){Text("−")}
                TextButton(onClick={vm.transform(1.25f,0f)},enabled=state.zoom<5f){Text("+")}
                TextButton(onClick=vm::resetZoom){Text("复位")}
            })
        }
    },bottomBar={ AnimatedVisibility(visible=!immersive,enter=slideInVertically{it}+fadeIn(),exit=slideOutVertically{it}+fadeOut()) {
        Surface { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=16.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            TextButton(onClick=onSettings){Text("模型设置")}
            TextButton(onClick={pageInput=(state.page+1).toString();jump=true},enabled=!state.loading){Text("${state.page+1} / $count 页")}
        } }
    } }) { inset ->
        BoxWithConstraints(Modifier.padding(inset).fillMaxSize()) {
            val wide=maxWidth>=1000.dp
            val maxAnswerWidth=(maxWidth.value-358f).coerceAtMost(maxWidth.value*.65f).coerceAtLeast(320f)
            // 旋转/分屏/窗口变窄后按当前可用宽度重新限制，问答栏偏好只在可用范围内生效
            val actualAnswerWidth=answerWidth.coerceIn(320f,maxAnswerWidth)
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(visible=wide && catalogOpen && !immersive,enter=slideInHorizontally{-it}+fadeIn(),exit=slideOutHorizontally{-it}+fadeOut()) {
                    Surface(color=MaterialTheme.colorScheme.surface,tonalElevation=0.dp,modifier=Modifier.width(360.dp).fillMaxHeight()) {
                        Row {
                            CatalogPanel(vm=catalogVm,onSelectEntry=::openEntry,onSelectHit=::openHit,
                                onDeleteEntry={requestDelete(it.anchor)},onClose={catalogOpen=false},
                                modifier=Modifier.weight(1f).fillMaxHeight())
                            VerticalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center) {
                        if(!state.loading&&state.pages.isNotEmpty())ContinuousPages(state,vm,jumpTarget,anchorJump,highlightAnchorId,
                            onJumpHandled={jumpTarget=null},onAnchorJumpHandled={anchorJump=null},
                            onToggleImmersive=::toggleImmersive,onColor={colorPicker=true},onDelete={requestDelete(it)})
                        if(state.loading)CircularProgressIndicator()
                        state.error?.let { message -> Surface(Modifier.padding(24.dp),shadowElevation=4.dp) {
                            Column(Modifier.padding(20.dp)) {
                                Text(message)
                                Row { TextButton(onClick=vm::clearError){Text("知道了")}
                                    if(state.pages.isEmpty())TextButton(onClick=vm::openDocument){Text("重试")} }
                            }
                        } }
                    }
                    AnimatedVisibility(visible=!immersive,enter=slideInVertically{it}+fadeIn(),exit=slideOutVertically{it}+fadeOut()) {
                        Column {
                    askBar?.let { a -> Surface(tonalElevation=2.dp) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically,
                            horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("已定位到第 ${a.pageIndex+1} 页的${if(a.kind=="text")"高亮" else "圈图"}",
                                Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                            Button(onClick={ vm.openAnchor(a);askBar=null }) { Text("问 AI") }
                            if(a.kind=="text")TextButton(onClick={colorPicker=true}) { Text("颜色") }
                            TextButton(onClick={requestDelete(a)}) { Text("删除") }
                            TextButton(onClick={ askBar=null }) { Text("关闭") }
                        }
                    } }
                        }
                    }
                }
                AnimatedVisibility(visible=wide && !catalogOpen && !immersive && active!=null,
                    enter=slideInHorizontally{it}+fadeIn(),exit=slideOutHorizontally{it}+fadeOut()) {
                Row {
                active?.let { anchor ->
                    Box(Modifier.width(18.dp).fillMaxHeight()
                        .pointerInput(maxAnswerWidth,density) {
                            detectDragGestures(onDragEnd={ vm.savePanelWidth(answerWidth) }) { change, drag ->
                                change.consume()
                                answerWidth=(answerWidth-drag.x/density.density).coerceIn(320f,maxAnswerWidth)
                            }
                        },contentAlignment=Alignment.Center) {
                        VerticalDivider(Modifier.fillMaxHeight())
                        Surface(color=MaterialTheme.colorScheme.outlineVariant,shape=MaterialTheme.shapes.small) {
                            Text("⋮",Modifier.padding(horizontal=2.dp,vertical=14.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    key(anchor.id) { ConversationPanel(anchor,onClose={vm.activeAnchor.value=null},onSettings=onSettings,
                        onChangeColor={colorPicker=true},onDelete={requestDelete(anchor)},
                        onSupplement={vm.beginSupplement(anchor);catalogOpen=false;askBar=null},
                        focusedSourceId=focusedSourceId,
                        onLocateSource={ s ->
                            vm.focusedSourceId.value=s.id
                            anchorJump=AnchorJump(s.pageIndex,s.id,++jumpToken);highlightAnchorId=s.id
                            if(!wide)vm.activeAnchor.value=null
                        },
                        modifier=Modifier.width(actualAnswerWidth.dp).fillMaxHeight(),scrollToMessageId=scrollMessageId,
                        initialQuote=cardQuote) }
                }
                }
                }
            }
            if(!wide)active?.let { anchor ->
                Dialog(onDismissRequest={vm.activeAnchor.value=null},properties=DialogProperties(usePlatformDefaultWidth=false)) {
                    key(anchor.id) { ConversationPanel(anchor,onClose={vm.activeAnchor.value=null},onSettings=onSettings,
                        onChangeColor={colorPicker=true},onDelete={requestDelete(anchor)},
                        onSupplement={vm.beginSupplement(anchor);catalogOpen=false;askBar=null},
                        focusedSourceId=focusedSourceId,
                        onLocateSource={ s ->
                            vm.focusedSourceId.value=s.id
                            anchorJump=AnchorJump(s.pageIndex,s.id,++jumpToken);highlightAnchorId=s.id
                            if(!wide)vm.activeAnchor.value=null
                        },
                        modifier=Modifier.fillMaxWidth(.96f).fillMaxHeight(.94f),scrollToMessageId=scrollMessageId,
                        initialQuote=cardQuote) }
                }
            }
            if(!wide && catalogOpen)ModalBottomSheet(onDismissRequest={catalogOpen=false},
                sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
                CatalogPanel(vm=catalogVm,onSelectEntry=::openEntry,onSelectHit=::openHit,
                    onDeleteEntry={requestDelete(it.anchor)},onClose={catalogOpen=false},
                    modifier=Modifier.fillMaxWidth().fillMaxHeight(.72f))
            }
            val floatingPos by vm.floatingPos.collectAsStateWithLifecycle()
            floatingPos?.let { position -> key(vm) {
                FloatingTools(immersive=immersive, regionActive=region, catalogActive=catalogOpen,
                    onRead={ vm.setRegionMode(false) }, onRegion={ vm.setRegionMode(true) }, onCatalog={ catalogOpen=!catalogOpen },
                    initialX=position.first, initialY=position.second,
                    onMoveEnd={ x, y -> vm.saveFloatingPos(x, y) })
            } }
            supplementTarget?.let {
                Surface(Modifier.align(Alignment.TopCenter).padding(8.dp),shape=MaterialTheme.shapes.medium,shadowElevation=4.dp) {
                    Row(Modifier.padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text("补充到当前问答",style=MaterialTheme.typography.labelMedium)
                        TextButton(onClick={vm.setRegionMode(false)},enabled=!saving) { Text("选文字") }
                        TextButton(onClick={vm.setRegionMode(true)},enabled=!saving) { Text("圈图片") }
                        TextButton(onClick=vm::cancelSupplement,enabled=!saving) { Text("返回问答") }
                    }
                }
            }
        }
    }
    if(cardsMounted) {
        fun anchorOf(card: ExcerptCard) = card.anchorId?.let { aid -> anchorsList.firstOrNull { it.id==aid } }
        CardsOverlay(
            cards=scopedCards,
            answerTextScale=answerTextScale,
            cardTextScales=cardTextScales,
            onSaveTextScale=vm::saveCardTextScale,
            pageIndex=state.page,
            anchorScoped=selection!=null || active!=null,
            anchors=anchorsList,
            entryX=cardEntry?.x ?: Float.NaN,
            entryY=cardEntry?.y ?: Float.NaN,
            open=showCards,
            gridState=cardGridState,
            onRequestClose={ showCards=false },
            onToggle={ showCards=!showCards },
            onClosed={ cardsMounted=false },
            onAsk={ card -> anchorOf(card)?.let { a ->
                vm.openAnchor(a); cardQuote=card.sourceMessageId to card.bodyMarkdown; showCards=false } },
            onOpenConversation={ card -> anchorOf(card)?.let { a ->
                vm.openAnchor(a); scrollMessageId=card.sourceMessageId; showCards=false } },
            onLocate={ card ->
                val a=anchorOf(card)
                if(a!=null) {
                    anchorJump=AnchorJump(a.pageIndex,a.id,++jumpToken)
                    highlightAnchorId=a.id
                } else jumpTarget=card.pageIndex
                showCards=false },
            onEdit={ c,t,b -> vm.editCard(c.id,t,b) },
            onDelete={ c -> vm.deleteCard(c.id) },
            onRestore={ c -> vm.restoreCard(c.id) },
            onReorder={ c, other ->
                vm.swapCards(c.id,other.id)
            },
            onMove={ c, target -> vm.moveCard(c.id,target.id) })
    }
    }
    if(jump)AlertDialog(onDismissRequest={jump=false},title={Text("跳转到页面")},text={
        OutlinedTextField(pageInput,{pageInput=it.filter(Char::isDigit).take(6)},label={Text("页码 1–$count")},singleLine=true)
    },confirmButton={TextButton(onClick={jumpTarget=pageInput.toIntOrNull()?.minus(1);jump=false},enabled=pageInput.toIntOrNull()?.let { it in 1..count }==true){Text("跳转")}},
        dismissButton={TextButton(onClick={jump=false}){Text("取消")}})
    if(colorPicker)AlertDialog(onDismissRequest={colorPicker=false},title={Text("高亮颜色")},text={
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            val selected=if(selection!=null)null else active ?: askBar
            highlightKeys.forEach { key ->
                Surface(onClick={
                    vm.chooseColor(key,selected?.takeIf { it.kind=="text" }?.id)
                    if(selected!=null && askBar?.id==selected.id)askBar=askBar?.copy(colorKey=key)
                    colorPicker=false
                },color=highlightColor(key),shape=MaterialTheme.shapes.medium,
                    border=if((selected?.colorKey ?: recentColor)==key) androidx.compose.foundation.BorderStroke(2.dp,Color.Black) else null) {
                    Box(Modifier.size(42.dp),contentAlignment=Alignment.Center) {
                        if((selected?.colorKey ?: recentColor)==key)Text("✓",color=Color.Black)
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick={colorPicker=false}) { Text("关闭") }})
    deleteCandidate?.let { candidate ->
        var keep by remember(candidate.id) { mutableStateOf(true) }
        AlertDialog(onDismissRequest={deleteCandidate=null},title={Text("删除此标注？")},text={
            Column {
                if(candidate.markRemovedAt==null) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=keep,onClick={keep=true})
                        Text("仅移除标注，保留问答",Modifier.weight(1f))
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=!keep || candidate.markRemovedAt!=null,onClick={keep=false})
                    Text("删除标注及问答",Modifier.weight(1f))
                }
                Text(if(keep && candidate.markRemovedAt==null)
                    "页面标记将移除，${deleteCounts.first} 条消息仍可在目录查看"
                    else "将删除 ${deleteCounts.first} 条消息；已保存的精选卡片会保留",
                    style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={TextButton(onClick={
            val kept=keep && candidate.markRemovedAt==null
            vm.removeAnnotation(candidate,kept) {
                if(askBar?.id==candidate.id)askBar=null
                queueUndo(candidate,kept);catalogVm.retrySearch()
            }
            deleteCandidate=null
        }) { Text(if(keep && candidate.markRemovedAt==null)"移除标注" else "删除标注及问答") }},
            dismissButton={TextButton(onClick={deleteCandidate=null}) { Text("取消") }})
    }
    undoQueue.firstOrNull()?.let { operation ->
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.BottomCenter) {
            Surface(Modifier.padding(20.dp),color=MaterialTheme.colorScheme.inverseSurface,
                shape=MaterialTheme.shapes.medium,shadowElevation=4.dp) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("已删除标注",Modifier.padding(start=14.dp),color=MaterialTheme.colorScheme.inverseOnSurface)
                    TextButton(onClick={
                        vm.restoreAnnotation(operation.anchor,operation.keptDiscussion) { catalogVm.retrySearch() }
                        if(undoQueue.firstOrNull()?.token==operation.token)undoQueue.removeAt(0)
                    }) { Text("撤销") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun ContinuousPages(
    state: ReaderState, vm: ReaderViewModel, jumpTarget: Int?, anchorJump: AnchorJump?,
    highlightAnchorId: String?, onJumpHandled: () -> Unit, onAnchorJumpHandled: () -> Unit,
    onToggleImmersive: () -> Unit, onColor: () -> Unit, onDelete: (SourceAnchor) -> Unit
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = state.page)
    val region by vm.regionMode.collectAsStateWithLifecycle()
    val selecting by vm.selecting.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    var restoring by remember { mutableStateOf(true) }
    val latest by rememberUpdatedState(state)
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val margin = if (maxWidth >= 700.dp) 28.dp else 8.dp
        val paperWidth = (maxWidth - margin*2).coerceAtMost(900.dp)
        val paperWidthPx = with(density) { paperWidth.toPx() }
        val transform = rememberTransformableState { scale, pan, _ ->
            vm.transform(scale, pan.x / paperWidthPx)
        }

        // Store page-relative offsets, not screen pixels: zoom/rotation/split-screen keep the same text nearby.
        LaunchedEffect(paperWidthPx, state.zoom) {
            restoring = true
            val current = latest
            val page = current.pages[current.page]
            val height = paperWidthPx * current.zoom * page.size.height / page.size.width
            list.scrollToItem(current.page, (height*current.offset).roundToInt())
            withFrameNanos { }
            restoring = false
        }
        LaunchedEffect(jumpTarget) {
            jumpTarget?.let { target ->
                restoring = true
                list.scrollToItem(target)
                vm.visiblePosition(target, 0f)
                withFrameNanos { }
                restoring = false
                onJumpHandled()
            }
        }
        // 一次性锚点定位：页面加载完成后再跳，token 防止重组重复触发
        LaunchedEffect(anchorJump?.token) {
            val target = anchorJump ?: return@LaunchedEffect
            if (target.pageIndex in state.pages.indices) {
                restoring = true
                list.scrollToItem(target.pageIndex)
                vm.visiblePosition(target.pageIndex, 0f)
                withFrameNanos { }
                restoring = false
            }
            onAnchorJumpHandled()
        }
        LaunchedEffect(list) {
            snapshotFlow {
                if (restoring) null else {
                    val index = list.firstVisibleItemIndex
                    val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                    item?.let { index to (list.firstVisibleItemScrollOffset.toFloat() / it.size.coerceAtLeast(1)).coerceIn(0f, .999f) }
                }
            }.filterNotNull().distinctUntilChanged().collect { (index, fraction) -> vm.visiblePosition(index, fraction) }
        }
        LazyColumn(state = list, userScrollEnabled = !region && !selecting, modifier = Modifier.fillMaxSize()
            .transformable(state = transform, enabled = !region && !selecting, canPan = { pan -> latest.zoom > 1f && abs(pan.x) > abs(pan.y) })
            .pointerInput(vm) { detectTapGestures(onDoubleTap = { onToggleImmersive() }) },
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            itemsIndexed(state.pages, key = { _, page -> page.index }) { _, page ->
                PageSheet(page, paperWidth, paperWidthPx, state.zoom, state.panX, highlightAnchorId,
                    onToggleImmersive, onColor, onDelete, vm)
            }
        }
    }
}

@Composable private fun PageSheet(
    page: PageInfo, paperWidth: Dp, paperWidthPx: Float, zoom: Float, panX: Float, highlightAnchorId: String?,
    onToggleImmersive: () -> Unit, onColor: () -> Unit, onDelete: (SourceAnchor) -> Unit, vm: ReaderViewModel
) {
    var bitmap by remember(page.index) { mutableStateOf<Bitmap?>(null) }
    var error by remember(page.index) { mutableStateOf(false) }
    var retry by remember(page.index) { mutableIntStateOf(0) }
    var runs by remember(page.index) { mutableStateOf<List<TextRun>>(emptyList()) }
    var textReady by remember(page.index) { mutableStateOf(false) }
    val region by vm.regionMode.collectAsStateWithLifecycle()
    val anchors by vm.anchors.collectAsStateWithLifecycle()
    val activeAnchor by vm.activeAnchor.collectAsStateWithLifecycle()
    val supplementTarget by vm.supplementTarget.collectAsStateWithLifecycle()
    val focusedSourceId by vm.focusedSourceId.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val saving by vm.savingSelection.collectAsStateWithLifecycle()
    val clipboard=LocalClipboardManager.current
    var dragging by remember(page.index) { mutableStateOf<SelectionDraft?>(null) }
    val currentRuns by rememberUpdatedState(runs)
    val currentAnchors by rememberUpdatedState(anchors.filter { it.pageIndex==page.index && it.markRemovedAt==null })
    val width=((paperWidthPx*zoom).roundToInt().coerceAtLeast(64)/128+1)*128
    LaunchedEffect(page.index,width,retry) {
        error=false
        try {
            if(bitmap==null)bitmap=vm.renderPage(page.index,480)
            delay(160)
            val rendered=vm.renderPage(page.index,width);ensureActive();bitmap=rendered
        } catch(e: CancellationException){throw e}
        catch(_: Exception){error=true}
    }
    LaunchedEffect(page.index) {
        try { runs=vm.text(page.index) } catch(e: CancellationException){throw e} catch(_: Exception){runs=emptyList()}
        textReady=true
    }
    DisposableEffect(page.index) { onDispose { vm.selecting.value=false } }
    val scaledWidth=paperWidth*zoom
    val scaledHeight=scaledWidth*(page.size.height/page.size.width)
    BoxWithConstraints(Modifier.fillMaxWidth().height(scaledHeight).clipToBounds(),contentAlignment=Alignment.Center) {
        val pageLeft=(maxWidth.value-scaledWidth.value)/2f+panX*paperWidth.value
        fun toolbarLeft(center: Float, width: Dp): Dp {
            val lower=maxOf(6f,6f-pageLeft)
            val upper=minOf(scaledWidth.value-width.value-6f,maxWidth.value-pageLeft-width.value-6f)
            return if(upper>=lower)center.coerceIn(lower,upper).dp else lower.dp
        }
        Box(Modifier.requiredSize(scaledWidth,scaledHeight).graphicsLayer { translationX=panX*paperWidthPx }.background(Color.White)
            .pointerInput(page.index,region) {
                if(!region)detectTapGestures(onTap={ point ->
                    if(vm.supplementTarget.value==null)hitAnchor(currentAnchors,point.x/size.width,point.y/size.height)?.let(vm::openAnchor)
                },onDoubleTap={ point ->
                    // 双击书本的非标注部分：全屏阅读切换（命中标注时不触发）
                    if(hitAnchor(currentAnchors,point.x/size.width,point.y/size.height)==null) onToggleImmersive()
                })
            }
            .pointerInput(page.index,region) {
                fun normalized(point: Offset)=Offset((point.x/size.width).coerceIn(0f,1f),(point.y/size.height).coerceIn(0f,1f))
                if(region) {
                    var start=Offset.Zero
                    detectDragGestures(onDragStart={ point -> start=normalized(point);vm.selecting.value=true },onDragCancel={dragging=null;vm.selecting.value=false},
                        onDragEnd={ dragging?.let(vm::select);dragging=null;vm.selecting.value=false }) { change,_ ->
                        change.consume()
                        val end=normalized(change.position)
                        val x=minOf(start.x,end.x);val y=minOf(start.y,end.y)
                        val w=abs(start.x-end.x);val h=abs(start.y-end.y)
                        dragging=if(w>.003f&&h>.003f)SelectionDraft(page.index,"region","",listOf(NormalizedRect(x,y,w,h))) else null
                    }
                } else {
                    var first=-1
                    fun selectTo(point: Offset) {
                        if(first<0)return
                        val end=nearestRun(currentRuns,normalized(point),false)
                        if(end<0)return
                        val chosen=currentRuns.subList(minOf(first,end),maxOf(first,end)+1)
                        dragging=SelectionDraft(page.index,"text",chosen.joinToString(""){it.text}.trim(),mergeBounds(chosen.map { it.bounds }))
                    }
                    detectDragGesturesAfterLongPress(onDragStart={ point ->
                        first=nearestRun(currentRuns,normalized(point),true)
                        if(first<0)vm.showNotice(if(currentRuns.isEmpty())"此页没有可选文字，或文字层仍在准备中。扫描页请点「圈选问 AI」。" else "请长按文字位置，再拖动扩展选区。")
                        else { vm.selecting.value=true;selectTo(point) }
                    },onDragCancel={dragging=null;vm.selecting.value=false},onDragEnd={
                        dragging?.takeIf { it.quote.isNotBlank() }?.let(vm::select);dragging=null;vm.selecting.value=false
                    }) { change,_ -> if(first>=0){change.consume();selectTo(change.position)} }
                }
            },contentAlignment=Alignment.Center) {
            bitmap?.let { Image(it.asImageBitmap(),"第 ${page.index+1} 页，长按并拖动可选择文字",Modifier.fillMaxSize()) }
            if(bitmap==null&&!error)CircularProgressIndicator(color=Color.Black)
            Canvas(Modifier.fillMaxSize()) {
                fun paint(rect: NormalizedRect,kind: String,selected: Boolean,colorKey: String="gray") {
                    val top=Offset(rect.x*size.width,rect.y*size.height)
                    val extent=Size(rect.width*size.width,rect.height*size.height)
                    if(kind=="text") {
                        if(colorKey=="gray") {
                            drawRect(Color.Black.copy(alpha=if(selected).24f else .13f),top,extent)
                            drawLine(Color.Black,Offset(top.x,top.y+extent.height),Offset(top.x+extent.width,top.y+extent.height),if(selected)3f else 1.5f)
                        } else {
                            drawRect(highlightColor(colorKey).copy(alpha=if(selected).48f else .34f),top,extent)
                            if(selected)drawRect(Color.Black.copy(alpha=.68f),top,extent,style=Stroke(2f))
                        }
                    } else drawRect(Color.Black,top,extent,style=Stroke(if(selected)3f else 2f))
                }
                anchors.filter { it.pageIndex==page.index && (it.markRemovedAt==null || it.id==highlightAnchorId) }
                    .forEach { anchor -> anchor.bounds.forEach { paint(rotateRect(it,anchor.rotation),anchor.kind,
                    anchor.id==highlightAnchorId || anchor.id==focusedSourceId && activeAnchor!=null,anchor.colorKey) } }
                (dragging ?: selection?.takeIf { it.page==page.index })?.let { draft -> draft.bounds.forEach { paint(it,draft.kind,true) } }
            }
            selection?.takeIf { it.page==page.index && dragging==null }?.bounds?.firstOrNull()?.let { rect ->
                val toolbarWidth=if(supplementTarget!=null)288.dp else if(selection?.kind=="text")288.dp else 180.dp
                val left=toolbarLeft((rect.x+rect.width/2f)*scaledWidth.value-toolbarWidth.value/2f,toolbarWidth)
                val top=if(rect.y*scaledHeight.value>55f) (rect.y*scaledHeight.value-52f).dp
                    else ((rect.y+rect.height)*scaledHeight.value+8f).dp
                Surface(Modifier.align(Alignment.TopStart).offset(x=left,y=top),
                    shape=MaterialTheme.shapes.medium,shadowElevation=5.dp,color=MaterialTheme.colorScheme.surface) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        TextButton(onClick={vm.saveSelection(true)},enabled=!saving) { Text(if(saving)"保存中" else if(supplementTarget!=null)"添加到当前问答" else "问 AI") }
                        if(selection?.kind=="text") {
                            if(supplementTarget==null)TextButton(onClick={vm.saveSelection(false)},enabled=!saving) { Text("高亮") }
                            if(supplementTarget==null)TextButton(onClick=onColor,enabled=!saving) { Text("颜色") }
                            TextButton(onClick={clipboard.setText(AnnotatedString(selection!!.quote))}) { Text("复制") }
                        }
                        TextButton(onClick={vm.select(null)},enabled=!saving) { Text("×") }
                    }
                }
            }
            anchors.firstOrNull { it.id==(focusedSourceId ?: activeAnchor?.id) }?.takeIf {
                activeAnchor!=null && selection==null && dragging==null && it.pageIndex==page.index && it.markRemovedAt==null }
                ?.let { anchor -> anchor.bounds.firstOrNull()?.let { stored ->
                    val rect=rotateRect(stored,anchor.rotation)
                    val toolbarWidth=if(anchor.kind=="text")184.dp else 124.dp
                    val left=toolbarLeft((rect.x+rect.width/2f)*scaledWidth.value-toolbarWidth.value/2f,toolbarWidth)
                    val top=if(rect.y*scaledHeight.value>55f)(rect.y*scaledHeight.value-52f).dp
                        else ((rect.y+rect.height)*scaledHeight.value+8f).dp
                    Surface(Modifier.align(Alignment.TopStart).offset(x=left,y=top),
                        shape=MaterialTheme.shapes.medium,shadowElevation=5.dp,color=MaterialTheme.colorScheme.surface) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            if(anchor.kind=="text")TextButton(onClick=onColor) { Text("颜色") }
                            TextButton(onClick={onDelete(anchor)}) { Text("删除") }
                            TextButton(onClick={vm.activeAnchor.value=null}) { Text("×") }
                        }
                    }
                } }
        }
        if(error)Surface(shadowElevation=4.dp) { TextButton(onClick={retry++}){Text("第 ${page.index+1} 页加载失败，点此重试")} }
    }
}

private fun nearestRun(runs: List<TextRun>,point: Offset,limitDistance: Boolean): Int {
    val match=runs.indices.minByOrNull { index ->
        val r=runs[index].bounds
        val dx=(r.x-point.x).coerceAtLeast(0f)+(point.x-r.x-r.width).coerceAtLeast(0f)
        val dy=(r.y-point.y).coerceAtLeast(0f)+(point.y-r.y-r.height).coerceAtLeast(0f)
        dx*dx+dy*dy
    } ?: return -1
    val r=runs[match].bounds
    if(limitDistance && !NormalizedRect((r.x-.015f).coerceAtLeast(0f),(r.y-.015f).coerceAtLeast(0f),
        (r.x+r.width+.015f).coerceAtMost(1f)-(r.x-.015f).coerceAtLeast(0f),
        (r.y+r.height+.015f).coerceAtMost(1f)-(r.y-.015f).coerceAtLeast(0f)).contains(point.x,point.y))return -1
    return match
}
private fun mergeBounds(rects: List<NormalizedRect>): List<NormalizedRect> {
    val merged=mutableListOf<NormalizedRect>()
    rects.forEach { r ->
        val previous=merged.lastOrNull()
        if(previous!=null && abs(previous.y-r.y)<minOf(previous.height,r.height)*.5f && r.x>=previous.x && r.x<=previous.x+previous.width+.02f) {
            val right=maxOf(previous.x+previous.width,r.x+r.width)
            val top=minOf(previous.y,r.y);val bottom=maxOf(previous.y+previous.height,r.y+r.height)
            merged[merged.lastIndex]=NormalizedRect(previous.x,top,right-previous.x,bottom-top)
        } else merged+=r
    }
    return merged
}



