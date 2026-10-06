package com.yuejian.conversation

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuejian.markdown.AnswerRenderHost
import com.yuejian.markdown.AnswerRichText
import com.yuejian.markdown.AnswerStyle
import com.yuejian.markdown.rememberAnswerRenderHost
import com.yuejian.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable fun ConversationPanel(anchor: SourceAnchor, onClose: () -> Unit, onSettings: () -> Unit,
    onChangeColor: () -> Unit = {}, onDelete: () -> Unit = {},
    onSupplement: () -> Unit = {}, onLocateSource: (SourceAnchor) -> Unit = {}, focusedSourceId: String? = null,
    modifier: Modifier = Modifier, scrollToMessageId: String? = null, initialQuote: Pair<String,String>? = null,
    vm: ConversationViewModel = hiltViewModel(key="conversation-${anchor.id}")) {
    LaunchedEffect(anchor.id) { vm.open(anchor) }
    DisposableEffect(vm) { onDispose { vm.saveDraft() } }
    val messages by vm.messages.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val hasImages=sources.any { it.kind=="region" }
    val convTitle by vm.title.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val draftQuote by vm.draftQuote.collectAsStateWithLifecycle()
    val quoteNotice by vm.quoteNotice.collectAsStateWithLifecycle()
    val sending by vm.sending.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val cfg by vm.config.collectAsStateWithLifecycle()
    val thinkingOverride by vm.thinkingOverride.collectAsStateWithLifecycle()
    val qaCards by vm.qaCards.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val stream by vm.streamState.collectAsStateWithLifecycle()
    val usageMap by vm.usageMap.collectAsStateWithLifecycle()
    val thinking by vm.thinkingText.collectAsStateWithLifecycle()
    val thinkingCut by vm.thinkingCut.collectAsStateWithLifecycle()
    var showThinking by remember { mutableStateOf(false) }
    var nowTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(stream.active) {
        if (stream.active) while (true) { delay(500); nowTick = android.os.SystemClock.elapsedRealtime() }
    }
    val initialEditing by vm.initialEditing.collectAsStateWithLifecycle()
    val answerScale by vm.answerScale.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf<ChatMessage?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    var focusAnswer by rememberSaveable(anchor.id) { mutableStateOf(false) }
    var showRegionImage by rememberSaveable(anchor.id) { mutableStateOf(false) }
    var sourceExpanded by rememberSaveable(anchor.id) { mutableStateOf(false) }
    LaunchedEffect(focusedSourceId) {
        if(focusedSourceId!=null && focusedSourceId!=anchor.id)sourceExpanded=true
    }
    var headerMenu by remember(anchor.id) { mutableStateOf(false) }
    var showQuestionDetails by remember(anchor.id) { mutableStateOf(false) }
    var qaDraft by remember { mutableStateOf<ChatMessage?>(null) }
    var qaDiscard by remember { mutableStateOf(false) }
    var editing by rememberSaveable(anchor.id) { mutableStateOf(true) }
    var quickOpen by rememberSaveable(anchor.id) { mutableStateOf(anchor.kind=="text") }
    var highlightId by remember { mutableStateOf<String?>(null) }
    var jumpNotice by remember { mutableStateOf<String?>(null) }
    var selectionGuard by remember { mutableStateOf(false) }
    var editingDecided by remember(anchor.id) { mutableStateOf(false) }
    var pendingFocus by remember(anchor.id) { mutableStateOf(false) }
    var pendingFollow by remember { mutableStateOf(false) }
    var lastFocusToggle by remember(anchor.id) { mutableLongStateOf(0L) }
    val list=rememberLazyListState()
    var followTail by remember(anchor.id) { mutableStateOf(true) }
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val clipboard=LocalClipboardManager.current
    val density=LocalDensity.current
    val focusManager=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val inputFocus=remember { FocusRequester() }
    fun toggleFocus() {
        if(selectionGuard)return // 文本选择/拖动手柄期间不切换专注
        val now=SystemClock.elapsedRealtime()
        if(now-lastFocusToggle<300) return // Native WebView and Compose may observe the same double tap.
        lastFocusToggle=now
        if(!focusAnswer) { focusManager.clearFocus();keyboard?.hide() }
        focusAnswer=!focusAnswer
    }
    fun askQuote(messageId: String, text: String) {
        if(text.isBlank()) { vm.quoteNotice.value="未取到选文，请重新选择后重试";return }
        focusAnswer=false // 选择追问时退出专注并展开输入区
        editing=true
        pendingFocus=true
        vm.quoteFrom(messageId,text)
    }
    var cardDraft by remember { mutableStateOf<Pair<String,String>?>(null) }
    fun askCard(messageId: String, text: String) {
        if(text.isBlank()) { vm.quoteNotice.value="未取到选文，请重新选择后重试";return }
        cardDraft=messageId to text
    }
    // 卡片追问：等消息加载后把卡片正文设为本次引用（10.7），并展开输入区
    var quoteHandled by remember(anchor.id) { mutableStateOf(false) }
    LaunchedEffect(initialQuote, messages) {
        val q = initialQuote
        if (q != null && !quoteHandled && messages.any { it.id == q.first }) {
            quoteHandled = true
            vm.quoteFrom(q.first, q.second)
            editing = true
            pendingFocus = true
        }
    }
    fun jumpToQuote(messageId: String) {
        val index=messages.indexOfFirst { it.id==messageId }
        if(index<0) { jumpNotice="原回答已不在当前对话中，引用快照仍可查看";return }
        followTail=false
        scope.launch { list.animateScrollToItem(index) }
        highlightId=messageId
    }
    val scheme=MaterialTheme.colorScheme
    val typography=MaterialTheme.typography
    val scale=when(answerScale){0->0.85f;2->1.25f;else->1f}
    val basePx=with(density){typography.bodyMedium.fontSize.toPx()}*scale
    val answerStyle=remember(scheme,basePx) { AnswerStyle(
        foreground=scheme.onSurface.toArgb(),
        muted=scheme.onSurfaceVariant.toArgb(),
        accent=scheme.primary.toArgb(),
        line=scheme.outlineVariant.toArgb(),
        codeBackground=scheme.surfaceVariant.toArgb(),
        quoteBar=scheme.outline.toArgb(),
        baseSizePx=basePx
    ) }
    val currentToggleFocus by rememberUpdatedState(::toggleFocus)
    val currentAskQuote by rememberUpdatedState(::askQuote)
    val currentAskCard by rememberUpdatedState(::askCard)
    val renderHost=rememberAnswerRenderHost(onLinkClick={ pendingLink=it },
        onAnswerDoubleTap={ currentToggleFocus() },
        onQuoteRequest={ id,text -> currentAskQuote(id,text) },
        onSelectionChange={ selectionGuard=it;if(it)followTail=false },
        onCardRequest={ id,text -> currentAskCard(id,text) })
    LaunchedEffect(copied) { if(copied!=null) { delay(1800);copied=null } }
    LaunchedEffect(highlightId) { if(highlightId!=null) { delay(1900);highlightId=null } }
    LaunchedEffect(jumpNotice) { if(jumpNotice!=null) { delay(2600);jumpNotice=null } }
    // 有历史默认收起输入区；新对话直接进编辑态
    LaunchedEffect(initialEditing) {
        if(!editingDecided && initialEditing!=null) { editingDecided=true;editing=initialEditing==true }
    }
    // 成功入队后收起编辑区、收起键盘，把空间交还回答；拒绝确认/失败时保留编辑态与草稿
    LaunchedEffect(Unit) { vm.enqueued.collect { editing=false;focusManager.clearFocus();keyboard?.hide() } }
    LaunchedEffect(pendingFocus,editing) {
        if(pendingFocus && editing && !focusAnswer) { pendingFocus=false;runCatching { inputFocus.requestFocus() } }
    }
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(atBottom) { if(atBottom)pendingFollow=false }
    LaunchedEffect(atBottom,list.isScrollInProgress) {
        if(atBottom && list.isScrollInProgress)followTail=true
    }
    val userScroll=remember(anchor.id) { object: NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if(source==NestedScrollSource.UserInput && available.y>2f) followTail=false
            return Offset.Zero
        }
    } }
    suspend fun scrollToTail() {
        val last=(list.layoutInfo.totalItemsCount-1).coerceAtLeast(0)
        list.scrollToItem(last)
        list.scrollBy(100000f)
    }
    // 外部定位请求（目录搜索命中）：滚到指定消息并短暂高亮；定位失败给说明，不跳错消息
    var scrollHandled by remember(anchor.id) { mutableStateOf(scrollToMessageId==null) }
    LaunchedEffect(messages.size,ready) {
        if(!scrollHandled && ready) {
            val idx=messages.indexOfFirst { it.id==scrollToMessageId }
            if(idx>=0) { followTail=false;list.animateScrollToItem(idx);highlightId=scrollToMessageId;scrollHandled=true }
            else if(messages.isNotEmpty() && initialEditing!=null) {
                jumpNotice="要找的消息不在当前对话中";scrollHandled=true
            }
        }
    }
    // 新消息到来：只有在原本就贴着底部时才跟随，用户在读上文时不动；有外部定位请求时不抢滚动
    LaunchedEffect(messages.size) {
        if(!scrollHandled)return@LaunchedEffect
        val last=messages.lastIndex
        if(last<0)return@LaunchedEffect
        val lastVisible=list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if(followTail)scrollToTail() else pendingFollow=true
    }
    // 流式过程中只有接近底部才继续跟随
    LaunchedEffect(live?.first) {
        val id=live?.first ?: return@LaunchedEffect
        while(live?.first==id) {
            delay(400)
            if(live?.first!=id)break
            val info=list.layoutInfo
            val total=info.totalItemsCount
            val lastVisible=info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if(total>0 && followTail)scrollToTail()
        }
    }
    val showFollow=!atBottom && (live!=null || pendingFollow || !followTail)
    Surface(modifier=modifier) {
        Column(Modifier.fillMaxSize().imePadding().padding(horizontal=14.dp,vertical=8.dp),
            verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(convTitle ?: "读懂这里",modifier=Modifier.weight(1f).clickable { showQuestionDetails=true },
                    style=typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(sending && focusAnswer)TextButton(onClick=vm::stop,contentPadding=PaddingValues(horizontal=8.dp)) {
                    Text("停止",maxLines=1,softWrap=false)
                }
                TextButton(onClick=::toggleFocus,contentPadding=PaddingValues(horizontal=8.dp)) {
                    Text(if(focusAnswer)"恢复" else "专注",maxLines=1,softWrap=false)
                }
                Box {
                    TextButton(onClick={headerMenu=true},modifier=Modifier.width(48.dp),contentPadding=PaddingValues(0.dp)) {
                        Text("⋯",style=typography.titleLarge)
                    }
                    DropdownMenu(expanded=headerMenu,onDismissRequest={headerMenu=false}) {
                        DropdownMenuItem(text={Text("查看完整问题")},onClick={headerMenu=false;showQuestionDetails=true})
                        DropdownMenuItem(text={Column {
                            Text("模型设置")
                            Text(if(cfg.keyPresent)"${cfg.name} · ${if(hasImages)cfg.imageModel else cfg.textModel}"
                                else "先配置模型和 API Key",style=typography.labelSmall,color=scheme.onSurfaceVariant)
                        }},onClick={headerMenu=false;onSettings()})
                        if(anchor.kind=="text")DropdownMenuItem(text={Text("高亮颜色")},onClick={headerMenu=false;onChangeColor()})
                        DropdownMenuItem(text={Text("删除标注")},onClick={headerMenu=false;onDelete()})
                        DropdownMenuItem(text={Text("收起问答")},onClick={headerMenu=false;onClose()})
                    }
                }
                TextButton(onClick=onClose,modifier=Modifier.width(48.dp),contentPadding=PaddingValues(0.dp)) {
                    Text("×",style=typography.titleLarge)
                }
            }
            AnimatedVisibility(visible=!focusAnswer,
                enter=slideInVertically(initialOffsetY={-it/2})+fadeIn(),
                exit=slideOutVertically(targetOffsetY={-it/2})+fadeOut()) {
                Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min=40.dp),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={followTail=false;sourceExpanded=!sourceExpanded},
                            modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=4.dp)) {
                            Text("资料 · ${sources.size} 处 ${if(sourceExpanded)"▴" else "▾"}",maxLines=1)
                        }
                        TextButton(onClick=onSupplement,enabled=ready && !sending,
                            contentPadding=PaddingValues(horizontal=6.dp)) { Text("补充资料",maxLines=1) }
                        if(sources.any { it.conversationAnchorId!=null })TextButton(enabled=ready && !sending,onClick={
                            if(draft.isBlank())vm.prepareContinuation()
                            retry=null
                            if(vm.isConfirmed(vm.sendScopeKey(if(hasImages)cfg.imageModel else cfg.textModel,cfg,anchor,draftQuote,null)))
                                vm.send() else confirm=true
                        },contentPadding=PaddingValues(horizontal=6.dp)) { Text("继续回答",maxLines=1) }
                    }
                    AnimatedVisibility(visible=sourceExpanded) {
                        ConversationSources(sources,focusedSourceId,ready && !sending,onLocateSource,vm::removeSource,vm::sourcePreview)
                    }
                    HorizontalDivider()
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state=list,modifier=Modifier.fillMaxSize().nestedScroll(userScroll)
                    .onPassiveDoubleTap(anchor.id) { currentToggleFocus() },verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(messages.isEmpty())item { Text("围绕这处原文持续追问。对话和高亮会保存在本机。",color=scheme.onSurfaceVariant) }
                    items(messages,key={it.id}) { message ->
                        if(message.role=="source") {
                            Surface(shape=RoundedCornerShape(8.dp),color=scheme.surfaceVariant) {
                                Text(message.content,Modifier.fillMaxWidth().padding(8.dp),style=typography.bodySmall,color=scheme.onSurfaceVariant)
                            }
                        } else if(message.role=="user") {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                                Surface(modifier=Modifier.fillMaxWidth(.88f),shape=RoundedCornerShape(14.dp),
                                    color=scheme.primaryContainer,contentColor=scheme.onPrimaryContainer) {
                                    Column(Modifier.padding(horizontal=12.dp,vertical=9.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                        message.quote?.let { q ->
                                            // 引用卡片与提问正文区分显示；点击回看被引用的 AI 回答
                                            Surface(modifier=Modifier.fillMaxWidth().clickable { jumpToQuote(q.sourceMessageId) },
                                                shape=RoundedCornerShape(8.dp),color=scheme.surfaceVariant,contentColor=scheme.onSurface) {
                                                Column(Modifier.padding(8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                                    Text("引用 AI 回答 · 点击回看",style=typography.labelSmall,color=scheme.onSurfaceVariant)
                                                    Text(q.textSnapshot,style=typography.bodySmall,maxLines=3,overflow=TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                        Text("我的提问",style=typography.labelMedium)
                                        SelectionContainer { Text(message.content,style=typography.bodyMedium) }
                                    }
                                }
                            }
                        } else {
                            val streaming=live?.first==message.id
                            val highlighted=highlightId==message.id
                            Column(Modifier.fillMaxWidth()
                                .then(if(highlighted)Modifier.background(scheme.primaryContainer.copy(alpha=.35f),RoundedCornerShape(8.dp)).padding(6.dp) else Modifier)) {
                                Text("AI · ${message.modelId.orEmpty()}",style=typography.labelMedium,color=scheme.onSurfaceVariant)
                                if(message.id==stream.answerId) {
                                    GenStatusRow(stream,nowTick,thinking,showThinking){ followTail=false;showThinking=!showThinking }
                                    if(showThinking && thinking.isNotEmpty()) ThinkingCard(
                                        throttledAnswer(thinking,stream.active), thinkingCut,
                                        answerStyle.copy(baseSizePx=answerStyle.baseSizePx*.82f), renderHost) { showThinking=false }
                                }
                                // 已保存的思考记录：可回看；没有记录（开关未开/非思考模型/为空）则不显示任何入口
                                if(message.id!=stream.answerId && !message.thinking.isNullOrBlank()) {
                                    var showSaved by remember(message.id) { mutableStateOf(false) }
                                    TextButton(onClick={followTail=false;showSaved=!showSaved},contentPadding=PaddingValues(horizontal=4.dp,vertical=0.dp)) {
                                        Text(if(showSaved)"查看思考 ▾" else "查看思考 ▸",style=typography.labelMedium,color=scheme.onSurfaceVariant)
                                    }
                                    if(showSaved) ThinkingCard(message.thinking!!,false,
                                        answerStyle.copy(baseSizePx=answerStyle.baseSizePx*.82f),renderHost,
                                        note=if(message.status!="complete")"思考随中断保留，可能不完整" else null){ showSaved=false }
                                }
                                val raw=if(streaming)live!!.second else message.content
                                val shown=throttledAnswer(raw,streaming)
                                if(shown.isEmpty()) {
                                    Text(if(message.status in listOf("queued","generating"))"正在等待模型回答…" else "没有生成正文",style=typography.bodyMedium)
                                } else {
                                    AnswerRichText(markdown=shown,key="${message.id}:${if(streaming)"streaming" else message.status}",
                                        messageId=message.id,heightKey=message.id,style=answerStyle,host=renderHost,
                                        quoteEnabled=!streaming && message.status in listOf("complete","stopped","failed"))
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        TextButton(onClick={ clipboard.setText(AnnotatedString(raw));copied=message.id }) {
                                            Text("复制",style=typography.labelMedium)
                                        }
                                        if(message.status !in listOf("queued","generating") && raw.isNotBlank())
                                            TextButton(onClick={qaDraft=message}) {
                                                Text(if(qaCards.containsKey(message.id))"已存卡片" else "存为卡片",style=typography.labelMedium)
                                            }
                                        if(copied==message.id)Text("已复制原始 Markdown/LaTeX",style=typography.labelSmall,color=scheme.onSurfaceVariant)
                                    }
                                }
                                if(message.status in listOf("stopped","failed","truncated")) {
                                    Text(message.error ?: "已停止，收到的部分已保存",style=typography.bodySmall)
                                    if(message.id==messages.lastOrNull()?.id)TextButton(enabled=!sending,onClick={
                                        retry=message
                                        if(vm.isConfirmed(vm.sendScopeKey(if(hasImages)cfg.imageModel else cfg.textModel,cfg,anchor,null,message)))
                                            vm.send(message) else confirm=true
                                    }) { Text("重试这次回答") }
                                }
                                usageMap[message.id]?.let { UsageRow(it) }
                            }
                        }
                    }
                }
                if(showFollow)Surface(modifier=Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    shape=RoundedCornerShape(16.dp),color=scheme.surfaceVariant,contentColor=scheme.onSurface,
                    shadowElevation=3.dp) {
                    TextButton(onClick={ followTail=true;pendingFollow=false;scope.launch { scrollToTail() } }) {
                        Text("回到底部 ↓",style=typography.labelMedium)
                    }
                }
            }
            error?.let { Text(it,color=scheme.error,style=typography.bodySmall) }
            jumpNotice?.let { Text(it,color=scheme.onSurfaceVariant,style=typography.labelSmall) }
            AnimatedVisibility(visible=!focusAnswer,
                enter=slideInVertically(initialOffsetY={-it/2})+fadeIn(),
                exit=slideOutVertically(targetOffsetY={-it/2})+fadeOut()) {
                Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(!editing) {
                        // 收起态：单行“继续追问…”入口；有草稿或引用时显示标记；生成中保留可见停止按钮
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={editing=true;pendingFocus=true},modifier=Modifier.weight(1f)) {
                                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                    Text("继续追问…",maxLines=1,overflow=TextOverflow.Ellipsis)
                                    if(draft.isNotBlank())Text("草稿",style=typography.labelSmall,color=scheme.onSurfaceVariant)
                                    if(draftQuote!=null)Text("引用",style=typography.labelSmall,color=scheme.primary)
                                }
                            }
                            if(sending)Button(onClick=vm::stop) { Text("停止") }
                        }
                    } else {
                        draftQuote?.let { q ->
                            Surface(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(10.dp),color=scheme.surfaceVariant) {
                                Column(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                                        val src=messages.firstOrNull { it.id==q.sourceMessageId }
                                        Text(if(src!=null)"引用 AI 回答${src.modelId?.let{" · $it"} ?: ""} · 点击查看原回答" else "引用 AI 回答 · 原回答不在当前对话",
                                            style=typography.labelMedium,color=scheme.onSurfaceVariant,
                                            modifier=Modifier.weight(1f).clickable { jumpToQuote(q.sourceMessageId) })
                                        TextButton(onClick=vm::clearQuote) { Text("移除",style=typography.labelMedium) }
                                    }
                                    Text(q.textSnapshot,style=typography.bodySmall,maxLines=3,overflow=TextOverflow.Ellipsis,
                                        modifier=Modifier.fillMaxWidth().clickable { jumpToQuote(q.sourceMessageId) })
                                }
                            }
                        }
                        val quickActions: List<Pair<String,String>> = when {
                            draftQuote!=null -> listOf(
                                "解释这段" to "请解释引用段落的含义。",
                                "展开步骤" to "请把引用段落中的步骤展开，逐步说明。",
                                "举个例子" to "请结合引用段落举一个具体例子。")
                            anchor.kind=="text" -> listOf(
                                "解释" to "请解释这里的含义。",
                                "举例" to "请结合具体例子解释。",
                                "推导" to "请逐步推导并说明每一步依据。")
                            else -> emptyList()
                        }
                        if(quickActions.isNotEmpty())Row(verticalAlignment=Alignment.CenterVertically) {
                            if(quickOpen)quickActions.forEach { (label,text) ->
                                TextButton(enabled=!sending,onClick={vm.editDraft(text)}) { Text(label) }
                            }
                            TextButton(enabled=!sending,onClick={quickOpen=!quickOpen}) { Text(if(quickOpen)"收起快捷提问 ▴" else "快捷提问 ▾") }
                        }
                        OutlinedTextField(value=draft,onValueChange=vm::editDraft,
                            modifier=Modifier.fillMaxWidth().heightIn(max=160.dp).focusRequester(inputFocus),
                            enabled=ready&&!sending,label={Text("输入问题或继续追问")},minLines=1,maxLines=4)
                        val supported=!hasImages && thinkingProvider(cfg.baseUrl)!=null
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("深度思考",style=typography.labelMedium,modifier=Modifier.weight(1f))
                            Switch(checked=when(thinkingOverride){"enabled"->true;"disabled"->false;else->cfg.thinkingMode=="enabled"},
                                onCheckedChange=vm::setThinking,enabled=supported)
                        }
                        if(!supported)Text("当前请求不支持切换思考",style=typography.labelSmall,color=scheme.onSurfaceVariant)
                        else if(sending)Text("切换将于下次提问生效",style=typography.labelSmall,color=scheme.onSurfaceVariant)
                        if(sending)Button(onClick=vm::stop,modifier=Modifier.fillMaxWidth()) { Text("停止生成 · 保留已收到内容") }
                        else Button(onClick={
                            retry=null
                            // 同一服务商/范围连续追问不再反复弹确认（9.1）
                            if(vm.isConfirmed(vm.sendScopeKey(if(hasImages)cfg.imageModel else cfg.textModel,cfg,anchor,draftQuote,null)))
                                vm.send(null) else confirm=true
                        },enabled=ready&&draft.isNotBlank(),modifier=Modifier.fillMaxWidth()) { Text("发送给 AI") }
                        quoteNotice?.let { Text(it,style=typography.labelSmall,color=scheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    if(showQuestionDetails)AlertDialog(onDismissRequest={showQuestionDetails=false},title={Text("当前问题")},
        text={SelectionContainer { Text(convTitle ?: "读懂这里",
            modifier=Modifier.heightIn(max=240.dp).verticalScroll(rememberScrollState())) }},
        confirmButton={TextButton(onClick={showQuestionDetails=false}) { Text("关闭") }})
    if(showRegionImage) preview?.let { bitmap ->
        AlertDialog(onDismissRequest={showRegionImage=false},title={Text("圈选原图")},
            text={ Image(bitmap.asImageBitmap(),"本次问答发送的圈选原图",Modifier.fillMaxWidth().heightIn(max=420.dp)) },
            confirmButton={TextButton(onClick={showRegionImage=false}) { Text("关闭") }})
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("确认本次发送范围")},text={
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("服务商：${cfg.name}\n接口：${cfg.baseUrl}\n模型：${if(hasImages)cfg.imageModel else cfg.textModel}")
            Text(buildString {
                append("发送：当前关联的 ${sources.size} 处资料（${sources.count { it.kind=="region" }} 张图片）")
                if(retry==null && draftQuote!=null)append("、引用的先前 AI 回答摘录（作为待讨论数据）")
                append("、本次问题和同处必要历史。")
                if(hasImages)append("关联原图随追问发送。")
            })
            if(retry==null)draftQuote?.let { Text("引用快照：${it.textSnapshot.take(60)}…",style=typography.bodySmall,color=scheme.onSurfaceVariant) }
            Text("不会上传整页或整本资料。服务商可能按调用计费。",style=typography.bodySmall)
            if(!cfg.keyPresent)Text("请先配置 API Key。")
            if(hasImages&&!cfg.imageEnabled)Text("尚未启用图片模型，无法发送圈选原图。")
        }
    },confirmButton={TextButton(enabled=cfg.keyPresent&&(!hasImages||cfg.imageEnabled),onClick={
        confirm=false
        vm.markConfirmed(vm.sendScopeKey(if(hasImages)cfg.imageModel else cfg.textModel,cfg,anchor,if(retry==null)draftQuote else null,retry))
        vm.send(retry)
    }) { Text("确认发送") }},
        dismissButton={TextButton(onClick={confirm=false}) { Text("取消") }})
    cardDraft?.let { (draftMessageId, draftText) ->
        var cardTitle by remember(draftMessageId, draftText) { mutableStateOf(draftText.replace('\n',' ').trim().take(24)) }
        var cardBody by remember(draftMessageId, draftText) { mutableStateOf(draftText) }
        val srcMessage = messages.firstOrNull { it.id==draftMessageId }
        AlertDialog(onDismissRequest={ cardDraft=null }, title={ Text("保存卡片") }, text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cardTitle,{ cardTitle=it.take(60) },label={ Text("卡片标题（可改，本地生成）") },
                    singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(cardBody,{ cardBody=it },label={ Text("精选正文（可删去不需要的部分）") },
                    minLines=3,maxLines=8,modifier=Modifier.fillMaxWidth())
                Text("来源：AI 回答 · 第 ${anchor.pageIndex+1} 页${srcMessage?.let { if(it.status!="complete")" · 该回答未完成，摘录可能不完整" } ?: ""}",
                    style=typography.labelSmall,color=scheme.onSurfaceVariant)
                Text("保存后可编辑，不会修改 AI 原回答。",style=typography.labelSmall,color=scheme.onSurfaceVariant)
            }
        },confirmButton={ TextButton(enabled=cardBody.isNotBlank(),onClick={
            cardDraft=null
            vm.saveCard(draftMessageId,cardBody,cardTitle)
        }) { Text("保存卡片") } },
            dismissButton={ TextButton(onClick={ cardDraft=null }) { Text("取消") } })
    }
    qaDraft?.let { answer ->
        val existing=qaCards[answer.id]
        val index=messages.indexOfFirst { it.id==answer.id }
        val originalQuestion=(answer.sourceQuestionId?.let { id -> messages.firstOrNull { it.id==id && it.role=="user" } }
            ?: messages.take(index.coerceAtLeast(0)).lastOrNull { it.role=="user" })?.content.orEmpty()
        var question by remember(answer.id,existing?.id) { mutableStateOf(existing?.title ?: originalQuestion) }
        var body by remember(answer.id,existing?.id) { mutableStateOf(existing?.bodyMarkdown ?: answer.content) }
        val changed=question!=(existing?.title ?: originalQuestion) || body!=(existing?.bodyMarkdown ?: answer.content)
        fun closePreview() { if(changed)qaDiscard=true else qaDraft=null }
        AlertDialog(onDismissRequest=::closePreview,title={Text(if(existing==null)"保存问答卡片" else "问答卡片")},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(question,{question=it.take(500)},label={Text("问题")},modifier=Modifier.fillMaxWidth(),maxLines=3)
                OutlinedTextField(body,{body=it},label={Text("回答")},modifier=Modifier.fillMaxWidth().heightIn(max=240.dp),
                    minLines=3,maxLines=8)
                Text("第 ${anchor.pageIndex+1} 页 · 不包含思考内容。保存后可在卡片墙编辑。",
                    style=typography.labelSmall,color=scheme.onSurfaceVariant)
                if(answer.status!="complete")Text("这条回答尚未完整生成，保存的是已有正文。",style=typography.labelSmall)
            }
        },confirmButton={TextButton(enabled=question.isNotBlank()&&body.isNotBlank(),onClick={
            if(existing==null)vm.saveQaCard(answer.id,originalQuestion,question,answer.content,body)
            else vm.editQaCard(existing.id,question,body)
            qaDraft=null
        }) { Text(if(existing==null)"保存卡片" else "保存修改") }},
            dismissButton={TextButton(onClick=::closePreview) { Text("取消") }})
    }
    if(qaDiscard)AlertDialog(onDismissRequest={qaDiscard=false},title={Text("放弃卡片修改？")},
        text={Text("未保存的问题和回答修改将丢失。")},
        confirmButton={TextButton(onClick={qaDiscard=false;qaDraft=null}) { Text("放弃修改") }},
        dismissButton={TextButton(onClick={qaDiscard=false}) { Text("继续编辑") }})
    pendingLink?.let { url->
        AlertDialog(onDismissRequest={pendingLink=null},title={Text("打开链接")},
            text={ Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(url,style=typography.bodySmall)
                Text("回答里的链接不会自动加载，确认后用浏览器打开。",style=typography.labelSmall,color=scheme.onSurfaceVariant)
            } },
            confirmButton={TextButton(onClick={ pendingLink=null;runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) } }) { Text("打开") }},
            dismissButton={TextButton(onClick={pendingLink=null}) { Text("取消") }})
    }
}

/** Observe taps before scrolling, text selection, and AndroidView consume them; never consume events ourselves. */
private fun Modifier.onPassiveDoubleTap(key: Any, onDoubleTap: () -> Unit): Modifier = pointerInput(key) {
    val tapSlop = 24.dp.toPx()
    val doubleTapDistance = 64.dp.toPx()
    awaitPointerEventScope {
        var down: Offset? = null
        var previousTap: Offset? = null
        var previousTapTime = 0L
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.singleOrNull()
            if (change == null) {
                down = null
                previousTap = null
                continue
            }
            if (change.pressed && !change.previousPressed) down = change.position
            if (!change.pressed && change.previousPressed) {
                val start = down
                if (start != null && (change.position - start).getDistance() <= tapSlop) {
                    val last = previousTap
                    if (last != null && change.uptimeMillis - previousTapTime in 40..450 &&
                        (change.position - last).getDistance() <= doubleTapDistance) {
                        onDoubleTap()
                        previousTap = null
                    } else {
                        previousTap = change.position
                        previousTapTime = change.uptimeMillis
                    }
                } else previousTap = null
                down = null
            }
        }
    }
}

/**
 * 流式输出节流：原文始终在 ViewModel 里实时累积，只有"喂给渲染器"的内容按 ~140ms 采样更新，
 * 避免每个 token 都重建一次 HTML 并注入 WebView。结束后直接使用最终原文再渲染一次。
 */
@Composable
private fun throttledAnswer(source: String, streaming: Boolean, windowMs: Long = 140): String {
    val latest by rememberUpdatedState(source)
    var shown by remember(streaming) { mutableStateOf(source) }
    LaunchedEffect(streaming) {
        if(!streaming) { shown=latest;return@LaunchedEffect }
        var published=shown
        while(isActive) {
            val now=latest
            if(now!=published) { published=now;shown=now }
            delay(windowMs)
        }
    }
    return if(streaming) shown else source
}

/**
 * 生成状态行（对齐状态机文案）：等待/思考/正文阶段各有真实计时；
 * 有可见思考内容时可点击展开思考卡片。
 */
@Composable
private fun GenStatusRow(st: StreamStatus, now: Long, thinking: String, expanded: Boolean, onToggle: () -> Unit) {
    val typography = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    val elapsed = { start: Long -> if (start == 0L) 0L else ((now - start) / 1000).coerceAtLeast(0L) }
    val text = when (st.phase) {
        GenPhase.Preparing -> "正在准备…"
        GenPhase.Summarizing -> "正在整理较早的对话 · ${elapsed(st.summarizeAt)} 秒"
        GenPhase.Waiting -> "等待响应 · ${elapsed(st.startedAt)} 秒"
        GenPhase.Thinking -> "思考中 · ${elapsed(st.thinkingAt)} 秒"
        GenPhase.Answering ->
            if (st.thinkingAt > 0) "思考完成 · ${((st.answerAt - st.thinkingAt) / 1000).coerceAtLeast(0L)} 秒"
            else "正在生成…"
        GenPhase.Done ->
            if (st.thinkingAt > 0 && st.answerAt > 0) "思考完成 · ${((st.answerAt - st.thinkingAt) / 1000).coerceAtLeast(0L)} 秒"
            else "完成"
        else -> ""
    }
    if (text.isEmpty()) return
    if (thinking.isNotEmpty()) {
        TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Text(if (expanded) "$text ▾" else "$text ▸", style = typography.labelMedium, color = scheme.onSurfaceVariant)
        }
    } else {
        Text(text, style = typography.labelMedium, color = scheme.onSurfaceVariant)
    }
}

/** 本次思考卡片：Markdown/KaTeX 渲染（公式可见），限高内滚；默认收起。 */
@Composable
private fun ThinkingCard(
    markdown: String,
    truncated: Boolean,
    style: AnswerStyle,
    host: AnswerRenderHost,
    note: String? = null,
    onClose: () -> Unit
) {
    val typography = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = scheme.surfaceVariant,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("本次思考", style = typography.labelMedium, color = scheme.onSurfaceVariant)
                TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("关闭") }
            }
            if (truncated) Text("仅保留部分思考记录", style = typography.labelSmall, color = scheme.error)
            if (note != null) Text(note, style = typography.labelSmall, color = scheme.error)
            Box(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                AnswerRichText(markdown = markdown, key = "thinking", messageId = "thinking",
                    style = style, host = host, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 本次用量（默认折叠）：估算与实际分列；未知显示“未知”，不显示为 0。 */
@Composable
private fun UsageRow(record: UsageRecord) {
    val typography = MaterialTheme.typography
    val scheme = MaterialTheme.colorScheme
    var open by remember(record.id) { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
        Text(if (open) "本次用量 ▾" else "本次用量 ▸", style = typography.labelMedium, color = scheme.onSurfaceVariant)
    }
    if (open) {
        val cost = record.costEstimate?.let { "估算 ¥${"%.4f".format(it)}（按已配置单价）" } ?: "费用未知"
        Text(
            "输入 ${record.inputTokens ?: "未知"}（缓存命中 ${record.cachedTokens ?: "未知"}）· 输出 ${record.outputTokens ?: "未知"}" +
                (record.reasoningTokens?.let { " · 思考 $it" } ?: "") + " · " + cost +
                (if (record.status == "费用待确认") " · 费用待确认，以服务商账单为准" else ""),
            style = typography.labelSmall, color = scheme.onSurfaceVariant
        )
    }
}
