package com.yuejian.reader

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.yuejian.markdown.AnswerRichText
import com.yuejian.markdown.AnswerStyle
import com.yuejian.markdown.rememberAnswerRenderHost
import com.yuejian.model.ExcerptCard
import com.yuejian.model.SourceAnchor
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 卡片墙覆盖层。网格负责最终布局；飞行副本只绘制当前视口的卡片。 */
@Composable
fun CardsOverlay(
    answerTextScale: Int,
    cardTextScales: Map<String, Float>, onSaveTextScale: (String, Float) -> Unit,
    cards: List<ExcerptCard>, pageIndex: Int, anchorScoped: Boolean, anchors: List<SourceAnchor>,
    entryX: Float, entryY: Float, open: Boolean, gridState: LazyGridState,
    onRequestClose: () -> Unit, onToggle: () -> Unit, onClosed: () -> Unit,
    onAsk: (ExcerptCard) -> Unit, onOpenConversation: (ExcerptCard) -> Unit,
    onLocate: (ExcerptCard) -> Unit, onEdit: (ExcerptCard, String, String) -> Unit,
    onDelete: (ExcerptCard) -> Unit, onRestore: (ExcerptCard) -> Unit,
    onReorder: (ExcerptCard, ExcerptCard) -> Unit,
    onMove: (ExcerptCard, ExcerptCard) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val detailProgress = remember { Animatable(0f) }
    val cardBounds = remember { mutableStateMapOf<String, Rect>() }
    var rootBounds by remember { mutableStateOf<Rect?>(null) }
    var gridBounds by remember { mutableStateOf<Rect?>(null) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var pageOnly by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedOrigin by remember { mutableStateOf<Rect?>(null) }
    var editing by remember { mutableStateOf<ExcerptCard?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var lastDeleted by remember { mutableStateOf<ExcerptCard?>(null) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragStartBounds by remember { mutableStateOf<Rect?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragFingerStart by remember { mutableStateOf(Offset.Zero) }
    var dragScrollDirection by remember { mutableFloatStateOf(0f) }
    var dragScrollJob by remember { mutableStateOf<Job?>(null) }
    val pageCount = cards.count { it.pageIndex == pageIndex }
    val scoped = if (pageOnly && !anchorScoped) cards.filter { it.pageIndex == pageIndex } else cards
    val shown = remember(scoped, query) {
        if (query.isBlank()) scoped else scoped.filter {
            it.title.contains(query, true) || it.bodyMarkdown.contains(query, true)
        }
    }
    val selected = shown.firstOrNull { it.id == selectedId }
    val canUseSource: (ExcerptCard) -> Boolean = { card ->
        card.anchorId != null && anchors.any { it.id == card.anchorId }
    }

    LaunchedEffect(lastDeleted) {
        if (lastDeleted != null) { delay(5000); lastDeleted = null }
    }
    LaunchedEffect(open, shown.isEmpty(), cardBounds.isNotEmpty()) {
        if (open && shown.isNotEmpty() && cardBounds.isEmpty()) return@LaunchedEffect
        if (!open && selectedId != null) {
            if (reduceMotion) detailProgress.snapTo(0f)
            else detailProgress.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
            selectedId = null
            selectedOrigin = null
        }
        if (reduceMotion) progress.snapTo(if (open) 1f else 0f)
        else progress.animateTo(if (open) 1f else 0f,
            tween(if (open) 480 else 360, easing = FastOutSlowInEasing))
        if (!open) onClosed()
    }
    LaunchedEffect(selectedId) {
        if (selectedId != null) {
            if (reduceMotion) detailProgress.snapTo(1f)
            else detailProgress.animateTo(1f, tween(310, easing = FastOutSlowInEasing))
        }
    }
    fun closeDetail(after: (() -> Unit)? = null) {
        scope.launch {
            if (reduceMotion) detailProgress.snapTo(0f)
            else detailProgress.animateTo(0f, tween(280, easing = FastOutSlowInEasing))
            selectedId = null
            selectedOrigin = null
            after?.invoke()
        }
    }
    fun closeTopLayer() {
        when {
            editing != null -> confirmDiscard = true
            selectedId != null -> closeDetail()
            else -> onRequestClose()
        }
    }
    fun finishDrag() {
        val id = draggedId
        val origin = dragStartBounds
        val point = if (origin != null) origin.center + dragOffset else null
        if (id != null && point != null && gridBounds?.contains(point) == true) {
            val target = shown.mapNotNull { card ->
                val bounds = cardBounds[card.id] ?: return@mapNotNull null
                val dx = bounds.center.x - point.x
                val dy = bounds.center.y - point.y
                card to (dx * dx + dy * dy)
            }.minByOrNull { it.second }?.first
            val source = shown.firstOrNull { it.id == id }
            if (source != null && target != null && source.id != target.id) onMove(source, target)
        }
        dragScrollJob?.cancel()
        dragScrollDirection = 0f
        draggedId = null
        dragStartBounds = null
        dragOffset = Offset.Zero
    }
    BackHandler { closeTopLayer() }

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned {
        val next = it.boundsInWindow()
        if (rootBounds != next) rootBounds = next
    }) {
        val root = rootBounds
        val compactHeader = maxWidth < 600.dp
        val entry = if (root != null && entryX.isFinite() && entryY.isFinite())
            Offset(entryX - root.left, entryY - root.top)
        else Offset(with(density) { maxWidth.toPx() } - with(density) { 190.dp.toPx() },
            with(density) { 28.dp.toPx() })
        val visibleCards = shown.mapNotNull { card ->
            val rect = cardBounds[card.id] ?: return@mapNotNull null
            val viewport = gridBounds ?: return@mapNotNull null
            if (rect.bottom <= viewport.top || rect.top >= viewport.bottom) null else card to rect
        }

        Box(Modifier.fillMaxSize().background(scheme.scrim.copy(alpha = .48f * progress.value))
            .clickable { closeTopLayer() })

        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp)
            .padding(top = 102.dp, bottom = 24.dp)
            .graphicsLayer { alpha = progress.value }) {
            Row(Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (searchOpen) {
                    OutlinedTextField(query, { query = it }, modifier = Modifier.weight(1f),
                        singleLine = true, placeholder = { Text("搜索卡片") },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = scheme.onSurface,
                            unfocusedTextColor = scheme.onSurface,
                            focusedContainerColor = scheme.surface,
                            unfocusedContainerColor = scheme.surface,
                            focusedBorderColor = scheme.surface,
                            unfocusedBorderColor = scheme.surface))
                    TextButton(onClick = { query = ""; searchOpen = false },
                        modifier = Modifier.background(Color.Black.copy(alpha = .36f), RoundedCornerShape(12.dp))) {
                        Text("取消", color = Color.White)
                    }
                } else {
                    if (anchorScoped) {
                        Text("此选区卡片  ·  ${shown.size} 张", Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, color = Color.White)
                    } else {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ScopeTab(if (compactHeader) "全部" else "全部卡片 · ${cards.size}", !pageOnly) {
                                pageOnly = false
                                scope.launch { gridState.scrollToItem(0) }
                            }
                            ScopeTab(if (compactHeader) "本页" else "本页卡片 · $pageCount", pageOnly) {
                                pageOnly = true
                                scope.launch { gridState.scrollToItem(0) }
                            }
                        }
                    }
                    TextButton(onClick = { searchOpen = true },
                        modifier = Modifier.background(Color.Black.copy(alpha = .36f), RoundedCornerShape(12.dp))) {
                        Text("搜索", color = Color.White)
                    }
                }
                TextButton(onClick = ::closeTopLayer,
                    modifier = Modifier.background(Color.Black.copy(alpha = .36f), RoundedCornerShape(12.dp))) {
                    Text("关闭", color = Color.White)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(18.dp), color = scheme.surface,
                        border = BorderStroke(1.dp, scheme.outlineVariant)) {
                        Text(when {
                            query.isNotBlank() -> "没有匹配的卡片"
                            anchorScoped -> "此选区还没有精选卡片\n可以从关联的 AI 回答中摘录。"
                            pageOnly -> "本页还没有精选卡片\n点上方「全部卡片」查看其他页面。"
                            else -> "本书还没有精选卡片\n在 AI 回答中选中内容并保存，卡片会出现在这里。"
                        }, Modifier.padding(28.dp),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                LazyVerticalGrid(columns = GridCells.Adaptive(210.dp), state = gridState,
                    modifier = Modifier.fillMaxSize().onGloballyPositioned {
                        val next = it.boundsInWindow()
                        if (gridBounds != next) gridBounds = next
                    },
                    contentPadding = PaddingValues(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    itemsIndexed(shown, key = { _, card -> card.id }) { _, card ->
                        DisposableEffect(card.id) { onDispose { cardBounds.remove(card.id) } }
                        val canDrag = query.isBlank() && !anchorScoped && progress.value >= .999f && selectedId == null
                        Box(Modifier.fillMaxWidth().height(238.dp)
                            .animateItem(placementSpec = tween(280, easing = FastOutSlowInEasing))
                            .onGloballyPositioned {
                            val next = it.boundsInWindow()
                            if (cardBounds[card.id] != next) cardBounds[card.id] = next
                        }.pointerInput(card.id, canDrag) {
                            if (canDrag) detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    draggedId = card.id
                                    dragStartBounds = cardBounds[card.id]
                                    dragOffset = Offset.Zero
                                    dragFingerStart = Offset((dragStartBounds?.left ?: 0f) + start.x,
                                        (dragStartBounds?.top ?: 0f) + start.y)
                                },
                                onDragEnd = { finishDrag() },
                                onDragCancel = {
                                    dragScrollJob?.cancel()
                                    dragScrollDirection = 0f
                                    draggedId = null
                                    dragStartBounds = null
                                    dragOffset = Offset.Zero
                                }
                            ) { change, amount ->
                                change.consume()
                                dragOffset += amount
                                val viewport = gridBounds
                                val fingerY = dragFingerStart.y + dragOffset.y
                                val edge = 64.dp.toPx()
                                val scroll = when {
                                    viewport == null -> 0f
                                    fingerY < viewport.top + edge -> -24.dp.toPx()
                                    fingerY > viewport.bottom - edge -> 24.dp.toPx()
                                    else -> 0f
                                }
                                if (scroll != dragScrollDirection) {
                                    dragScrollJob?.cancel()
                                    dragScrollDirection = scroll
                                    if (scroll != 0f) dragScrollJob = scope.launch {
                                        while (draggedId == card.id && dragScrollDirection == scroll) {
                                            gridState.scrollBy(scroll)
                                            delay(16)
                                        }
                                    }
                                }
                            }
                        }) {
                        CardPreview(card, enabled = progress.value >= .999f && selectedId == null,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                    alpha = if (progress.value >= .999f && selectedId != card.id && draggedId != card.id) 1f else 0f
                                },
                            onClick = {
                                selectedOrigin = cardBounds[card.id]
                                selectedId = card.id
                            }, showDragHint = canDrag)
                        }
                    }
                }
                val total = gridState.layoutInfo.totalItemsCount
                val visible = gridState.layoutInfo.visibleItemsInfo.size
                if (total > visible && visible > 0) {
                    val trackPx = with(density) { maxHeight.toPx() }
                    val thumbPx = (trackPx * visible / total).coerceAtLeast(with(density) { 32.dp.toPx() })
                    val fraction = gridState.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
                    Box(Modifier.align(Alignment.TopEnd).padding(end = 2.dp)
                        .offset { IntOffset(0, ((trackPx - thumbPx) * fraction.coerceIn(0f, 1f)).roundToInt()) }
                        .width(3.dp).height(with(density) { thumbPx.toDp() })
                        .background(scheme.onSurface.copy(alpha = .45f), RoundedCornerShape(2.dp)))
                }
                }
            }
            lastDeleted?.let { deleted ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("卡片已删除", color = scheme.onSurfaceVariant)
                    TextButton(onClick = { onRestore(deleted); lastDeleted = null }) { Text("撤销") }
                }
            }
        }

        // 可见卡片从按钮小口飞到各自格位；收起从当前滚动位置逆向运行。
        if (root != null && progress.value < .999f && shown.isNotEmpty()) {
            visibleCards.forEachIndexed { index, (card, rect) ->
                val delayFraction = (index * .025f).coerceAtMost(.15f)
                val t = ((progress.value - delayFraction) / (1f - delayFraction)).coerceIn(0f, 1f)
                val localLeft = rect.left - root.left
                val localTop = rect.top - root.top
                CardPreview(card, enabled = false,
                    modifier = Modifier.offset { IntOffset(localLeft.roundToInt(), localTop.roundToInt()) }
                        .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                        .graphicsLayer {
                            transformOrigin = TransformOrigin.Center
                            translationX = (entry.x - (localLeft + rect.width / 2f)) * (1f - t)
                            translationY = (entry.y - (localTop + rect.height / 2f)) * (1f - t)
                            scaleX = .12f + .88f * t
                            scaleY = .07f + .93f * t
                            alpha = (.12f + .88f * t) * progress.value.coerceIn(0f, 1f)
                        }, onClick = {})
            }
        }

        val draggedCard = shown.firstOrNull { it.id == draggedId }
        val draggedBounds = dragStartBounds
        if (root != null && draggedCard != null && draggedBounds != null) {
            CardPreview(draggedCard, enabled = false,
                modifier = Modifier.offset {
                    IntOffset((draggedBounds.left - root.left + dragOffset.x).roundToInt(),
                        (draggedBounds.top - root.top + dragOffset.y).roundToInt())
                }.size(with(density) { draggedBounds.width.toDp() },
                    with(density) { draggedBounds.height.toDp() })
                    .graphicsLayer { scaleX = 1.05f; scaleY = 1.05f; shadowElevation = 18.dp.toPx() },
                onClick = {})
        }

        // 遮罩之上的入口替身与原标题栏按钮同坐标，可再次点击反向收回。
        if (root != null && progress.value > 0f) {
            TextButton(onClick = {
                when {
                    editing != null -> confirmDiscard = true
                    selectedId != null -> closeDetail(onRequestClose)
                    else -> onToggle()
                }
            },
                modifier = Modifier.offset {
                    IntOffset((entry.x - with(density) { 43.dp.toPx() }).roundToInt(),
                        (entry.y - with(density) { 24.dp.toPx() }).roundToInt())
                }.background(scheme.surface, RoundedCornerShape(12.dp))) {
                Text("卡片 ${cards.size}")
            }
        }

        // 单卡由原格位放大到中央；网格状态保留在下层。
        if (selected != null && root != null) {
            Box(Modifier.fillMaxSize().background(scheme.scrim.copy(alpha = .30f * detailProgress.value))
                .clickable { closeDetail() })
            val detailW = minOf(with(density) { maxWidth.toPx() } - with(density) { 48.dp.toPx() },
                with(density) { 660.dp.toPx() }).coerceAtLeast(1f)
            val detailH = minOf(with(density) { maxHeight.toPx() } - with(density) { 72.dp.toPx() },
                with(density) { 730.dp.toPx() }).coerceAtLeast(1f)
            val detailLeft = (with(density) { maxWidth.toPx() } - detailW) / 2f
            val detailTop = (with(density) { maxHeight.toPx() } - detailH) / 2f
            val origin = selectedOrigin ?: cardBounds[selected.id]
            val p = detailProgress.value
            Surface(shape = RoundedCornerShape(20.dp), color = scheme.surface,
                shadowElevation = 18.dp, border = BorderStroke(1.dp, scheme.outlineVariant),
                modifier = Modifier.offset { IntOffset(detailLeft.roundToInt(), detailTop.roundToInt()) }
                    .size(with(density) { detailW.toDp() }, with(density) { detailH.toDp() })
                    .graphicsLayer {
                        transformOrigin = TransformOrigin.Center
                        if (origin != null) {
                            translationX = (origin.center.x - root.left - (detailLeft + detailW / 2f)) * (1f - p)
                            translationY = (origin.center.y - root.top - (detailTop + detailH / 2f)) * (1f - p)
                            scaleX = origin.width / detailW * (1f - p) + p
                            scaleY = origin.height / detailH * (1f - p) + p
                        }
                        alpha = .7f + .3f * p
                    }) {
                CardDetail(selected, canUseSource(selected), answerTextScale,
                    savedTextScale = cardTextScales[selected.id] ?: 1f,
                    onSaveTextScale = { onSaveTextScale(selected.id, it) },
                    onClose = { closeDetail() },
                    onAsk = { onAsk(selected) },
                    onOpenConversation = { onOpenConversation(selected) },
                    onLocate = { onLocate(selected) },
                    onEdit = { editing = selected },
                    onDelete = { closeDetail { onDelete(selected); lastDeleted = selected } },
                    onReorder = { delta ->
                        if (query.isBlank()) {
                            val index = shown.indexOfFirst { it.id == selected.id }
                            if (index >= 0) shown.getOrNull(index + delta)?.let { other -> onReorder(selected, other) }
                        }
                    })
            }
        }
    }

    editing?.let { card ->
        var title by remember(card.id) { mutableStateOf(card.title) }
        var body by remember(card.id) { mutableStateOf(card.bodyMarkdown) }
        AlertDialog(onDismissRequest = { confirmDiscard = true }, title = { Text("编辑卡片") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it.take(60) }, label = { Text("标题") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(body, { body = it }, label = { Text("正文（Markdown / LaTeX）") },
                    minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
                Text("修改只影响卡片，不改动 AI 原回答。", style = MaterialTheme.typography.labelSmall)
            }
        }, confirmButton = {
            TextButton(enabled = body.isNotBlank(), onClick = {
                onEdit(card, title, body); editing = null
            }) { Text("保存") }
        }, dismissButton = { TextButton(onClick = { confirmDiscard = true }) { Text("取消") } })
        if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃编辑？") }, text = { Text("尚未保存的修改会丢失。") },
            confirmButton = { TextButton(onClick = { editing = null; confirmDiscard = false }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } })
    }
}

@Composable
private fun ScopeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick,
        modifier = Modifier.background(
            if (selected) Color.White else Color.Black.copy(alpha = .36f),
            RoundedCornerShape(12.dp))) {
        Text(label, color = if (selected) Color.Black else Color.White)
    }
}

@Composable
private fun CardPreview(card: ExcerptCard, enabled: Boolean, modifier: Modifier,
    onClick: () -> Unit, showDragHint: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, modifier = modifier,
        shape = RoundedCornerShape(17.dp), color = scheme.surface,
        shadowElevation = 8.dp, border = BorderStroke(1.dp, scheme.outlineVariant)) {
        Column(Modifier.fillMaxSize().padding(17.dp)) {
            Text(if(card.cardType=="qa")"问答卡片" else if (card.sourceKind == "text") "文字摘录" else "圈选摘录",
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(11.dp))
            Text(card.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Text(card.bodyMarkdown, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant, maxLines = 6, overflow = TextOverflow.Ellipsis)
            HorizontalDivider(color = scheme.outlineVariant)
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("第 ${card.pageIndex + 1} 页", style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant)
                if (showDragHint) Text("长按拖动", style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CardDetail(card: ExcerptCard, sourceAvailable: Boolean, answerTextScale: Int,
    savedTextScale: Float, onSaveTextScale: (Float) -> Unit,
    onClose: () -> Unit, onAsk: () -> Unit, onOpenConversation: () -> Unit,
    onLocate: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
    onReorder: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val typography = MaterialTheme.typography
    val textScale = when (answerTextScale) { 0 -> .85f; 2 -> 1.25f; else -> 1f }
    // AnswerStyle expects physical px; match the conversation's sp-to-px conversion.
    val bodySizePx = with(density) { typography.bodyMedium.fontSize.toPx() } * textScale
    var cardTextScale by remember(card.id) { mutableFloatStateOf(savedTextScale) }
    LaunchedEffect(card.id, savedTextScale) { cardTextScale = savedTextScale }
    var sourceExpanded by remember(card.id) { mutableStateOf(false) }
    val host = rememberAnswerRenderHost(onLinkClick = {})
    val bodyStyle = AnswerStyle(
        foreground = scheme.onSurface.toArgbSafe(), muted = scheme.onSurfaceVariant.toArgbSafe(),
        accent = scheme.primary.toArgbSafe(), line = scheme.outlineVariant.toArgbSafe(),
        codeBackground = scheme.surfaceVariant.toArgbSafe(), quoteBar = scheme.outline.toArgbSafe(),
        baseSizePx = bodySizePx)
    Column(Modifier.fillMaxSize().padding(22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(card.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("第 ${card.pageIndex + 1} 页 · ${if(card.cardType=="qa")"问答卡片" else if (card.sourceKind == "text") "高亮文字" else "圈选区域"}",
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            }
            TextButton(onClick = onClose) { Text("关闭") }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            val sourceQuote = card.sourceQuoteSnapshot
            if (card.sourceKind == "text" && !sourceQuote.isNullOrBlank()) {
                Text("原文", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                Text(sourceQuote, Modifier.padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (sourceExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis)
                if (sourceQuote.length > 100) TextButton(onClick = { sourceExpanded = !sourceExpanded }) {
                    Text(if (sourceExpanded) "收起原文" else "展开原文")
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("精选解答", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                TextButton(enabled = cardTextScale > .8f, onClick = {
                    cardTextScale = (cardTextScale - .1f).coerceIn(.8f, 2f); onSaveTextScale(cardTextScale)
                }) { Text("A−") }
                TextButton(onClick = { cardTextScale = 1f; onSaveTextScale(1f) }) {
                    Text("${(cardTextScale * 100).roundToInt()}%")
                }
                TextButton(enabled = cardTextScale < 2f, onClick = {
                    cardTextScale = (cardTextScale + .1f).coerceIn(.8f, 2f); onSaveTextScale(cardTextScale)
                }) { Text("A+") }
            }
            Spacer(Modifier.height(8.dp))
            AnswerRichText(markdown = card.bodyMarkdown, key = "card-${card.id}-${card.updatedAt}",
                messageId = card.id, style = bodyStyle, host = host,
                textScale = cardTextScale,
                onTextScaleGesture = { factor -> cardTextScale = (cardTextScale * factor).coerceIn(.8f, 2f) },
                onTextScaleGestureEnd = { onSaveTextScale(cardTextScale) })
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = sourceAvailable, onClick = onAsk) { Text("继续追问") }
            TextButton(enabled = sourceAvailable, onClick = onOpenConversation) { Text(if(sourceAvailable)"原始问答" else "来源已删除") }
            TextButton(onClick = onLocate) { Text("定位原文") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onReorder(-1) }) { Text("前移") }
            TextButton(onClick = { onReorder(1) }) { Text("后移") }
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }
}

private fun androidx.compose.ui.graphics.Color.toArgbSafe(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
