package com.yuejian.conversation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.os.SystemClock
import com.yuejian.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import javax.inject.Inject

/** 发送生命周期阶段：准备→等待→(整理较早对话)→等待→思考→正文→完成/停止/失败。 */
enum class GenPhase { Idle, Preparing, Summarizing, Waiting, Thinking, Answering, Done, Stopped, Failed }

/** 一次生成的计时与归属（全部为单调时钟，避免系统时间跳变）。 */
data class StreamStatus(
    val phase: GenPhase = GenPhase.Idle,
    val answerId: String? = null,
    val startedAt: Long = 0,
    val summarizeAt: Long = 0,
    val thinkingAt: Long = 0,
    val answerAt: Long = 0,
    val endedAt: Long = 0
) {
    val active: Boolean get() = phase==GenPhase.Preparing || phase==GenPhase.Summarizing ||
        phase==GenPhase.Waiting || phase==GenPhase.Thinking || phase==GenPhase.Answering
}

@HiltViewModel class ConversationViewModel @Inject constructor(
    private val repository: AnnotationRepository, private val settings: ModelSettings,
    private val provider: ModelProvider, private val preferences: ReadingPreferences
) : ViewModel() {
    companion object {
        /** 引用选区上限：超长提示缩短，不静默截断。 */
        const val QUOTE_LIMIT = 4000
        /** 思考内容临时缓冲上限（字符）；超出只保留前段并明确标记，不静默冒充完整。 */
        const val THINKING_CAP = 60000
    }

    private fun appendThinking(text: String) {
        val current = thinkingText.value
        if (current.length >= THINKING_CAP) { thinkingCut.value = true; return }
        val room = THINKING_CAP - current.length
        thinkingText.value = current + text.take(room)
        if (text.length > room) thinkingCut.value = true
    }

    /** 本次/每日预算预检（按已配置单价保守估算；0=不限）；超限抛出可读错误且不发送。 */
    private suspend fun checkBudget(cfg: ModelConfig, estInput: Int, extraYuan: Double = 0.0) {
        val estimate = (ConversationHarness.usageCost(cfg, TokenUsage(estInput.toLong(), 0L, cfg.outputBudget.toLong(), null))
            ?: 0.0) + extraYuan
        if (cfg.turnLimitYuan > 0 && estimate > cfg.turnLimitYuan) {
            error("按已配置单价估算本次约 ¥${"%.3f".format(estimate)}，超过单次预算 ¥${"%.2f".format(cfg.turnLimitYuan)}；可在设置中调整")
        }
        if (cfg.dailyLimitYuan > 0) {
            val startOfDay = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val spent = runCatching { repository.usageSince(startOfDay) }.getOrDefault(emptyList())
                .sumOf { it.costEstimate ?: 0.0 }
            if (spent + estimate > cfg.dailyLimitYuan) {
                error("本应用今日估算消费已达每日预算 ¥${"%.2f".format(cfg.dailyLimitYuan)}（仅统计本机账本，不含其他程序）")
            }
        }
    }

    /** 落一条用量账本；没有实际用量的中断/失败标“费用待确认”，不当作免费。 */
    private fun recordUsage(actionId: String, answerId: String, purpose: String, cfg: ModelConfig,
        model: String, estInput: Int, usage: TokenUsage? = lastUsage.value) {
        val record = UsageRecord(UUID.randomUUID().toString(), actionId,
            conversationId ?: return, answerId, purpose, model, cfg.name,
            estInput, usage?.input, usage?.cached, usage?.output, usage?.reasoning,
            ConversationHarness.usageCost(cfg, usage),
            if (usage == null) "费用待确认" else "已记录", System.currentTimeMillis())
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { repository.recordUsage(record) }
            runCatching { refreshUsage() }
        }
    }

    private suspend fun refreshUsage() {
        val id = conversationId ?: return
        runCatching { usageMap.value = repository.usageOf(id).associateBy { it.answerId } }
    }

    /** 摘录成精选卡片：正文=选中文本（选区提取已含公式 LaTeX）；来源快照不可变，编辑卡片不回写原回答。 */
    fun saveCard(messageId: String, body: String, title: String) {
        val source=anchor ?: return
        val convId=conversationId ?: return
        viewModelScope.launch {
            runCatching {
                val now=System.currentTimeMillis()
                val existing=repository.cards(source.documentId).first()
                val dup=existing.any { it.sourceMessageId==messageId && it.bodyMarkdown==body }
                val next=(existing.maxOfOrNull { it.sortOrder } ?: 0)+1
                repository.createCard(ExcerptCard(UUID.randomUUID().toString(),source.documentId,source.pageIndex,
                    source.id,convId,messageId,body,body,source.kind,source.quote,
                    title.ifBlank { body.replace('\n',' ').trim().take(24) },body,next,now,now))
                quoteNotice.value=if(dup)"已保存卡片（该片段此前已摘录过，有意可再存一份）" else "已保存卡片"
            }.onFailure { quoteNotice.value="保存卡片失败：${it.message?.take(80)}" }
        }
    }

    /** 同一服务商/范围/图片口径的连续追问只确认一次；任一项变化重新确认。 */
    private var confirmedScope: String? = null
    fun isConfirmed(scope: String) = confirmedScope == scope
    fun markConfirmed(scope: String) { confirmedScope = scope }
    fun sendScopeKey(model: String, cfg: ModelConfig, source: SourceAnchor, quote: AnswerQuote?, retry: ChatMessage?): String =
        "${cfg.baseUrl}|$model|${source.kind}|${sources.value.joinToString { it.id }}|${quote != null}|${retry != null}"

    /** 开关开启时把可见思考随回答存库（供回看）；为空不存，界面上也就不显入口。 */
    private fun persistThinking(answerId: String?) {
        val id = answerId ?: return
        val text = thinkingText.value
        if (text.isBlank()) return
        // 独立于 ViewModel 生命周期：退出对话/进程收尾时仍能落库（闭环关键）
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                if (preferences.keepThinking.first()) repository.thinking(id, text)
            }
        }
    }
    val config=settings.config.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),ModelConfig())
    val thinkingOverride=MutableStateFlow("inherit")
    val qaCards=MutableStateFlow<Map<String,ExcerptCard>>(emptyMap())
    val sources=MutableStateFlow<List<SourceAnchor>>(emptyList())
    val hasImages get() = sources.value.any { it.kind=="region" }
    fun removeSource(source: SourceAnchor) {
        if(sending.value)return
        viewModelScope.launch {
            runCatching { repository.removeSourceFromContext(source.id) }
                .onFailure { error.value="移除资料失败：${it.message}" }
        }
    }
    suspend fun sourcePreview(source: SourceAnchor): Bitmap? = withContext(Dispatchers.IO) {
        source.imageAssetId?.let { asset ->
            val bytes=repository.image(asset)
            BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=2 })
        }
    }
    fun prepareContinuation() {
        editDraft("请结合新补充的资料，继续回答此前的问题；如果依据仍不足，请具体说明还缺少什么资料。")
    }
    fun setThinking(enabled: Boolean) {
        val id=conversationId ?: return
        val value=if(enabled)"enabled" else "disabled"
        thinkingOverride.value=value
        viewModelScope.launch { runCatching { repository.setThinkingOverride(id,value) }
            .onFailure { error.value="思考开关保存失败" } }
    }
    fun saveQaCard(messageId: String, originalQuestion: String, editedQuestion: String, originalAnswer: String, editedAnswer: String) {
        val source=anchor ?: return
        val convId=conversationId ?: return
        viewModelScope.launch {
            runCatching {
                val now=System.currentTimeMillis()
                val next=(repository.cards(source.documentId).first().maxOfOrNull { it.sortOrder } ?: 0)+1
                repository.createQaCard(ExcerptCard(UUID.randomUUID().toString(),source.documentId,source.pageIndex,
                    source.id,convId,messageId,originalAnswer,originalAnswer,source.kind,source.quote,
                    editedQuestion.trim(),editedAnswer.trim(),next,now,now,"qa",originalQuestion))
                quoteNotice.value="已保存问答卡片"
            }.onFailure { error.value="保存问答卡片失败：${it.message?.take(80)}" }
        }
    }
    fun editQaCard(cardId: String, question: String, answer: String) { viewModelScope.launch {
        runCatching { repository.editCard(cardId,question.trim(),answer.trim()) }
            .onFailure { error.value="修改卡片失败：${it.message?.take(80)}" }
    } }
    val messages=MutableStateFlow<List<ChatMessage>>(emptyList())
    val title=MutableStateFlow<String?>(null)
    val draft=MutableStateFlow("")
    val draftQuote=MutableStateFlow<AnswerQuote?>(null)
    val quoteNotice=MutableStateFlow<String?>(null)
    val sending=MutableStateFlow(false)
    val error=MutableStateFlow<String?>(null)
    val live=MutableStateFlow<Pair<String,String>?>(null)
    /** 发送生命周期状态（含单调时钟计时锚点）。 */
    val streamState=MutableStateFlow(StreamStatus())
    /** 本次可见思考内容（会话级临时缓冲，不持久化；超限截断并标记）。 */
    val thinkingText=MutableStateFlow("")
    val thinkingCut=MutableStateFlow(false)
    /** 本次用量（未知字段为 null；不持久化，账本随阶段 B 落库）。 */
    val lastUsage=MutableStateFlow<TokenUsage?>(null)
    /** 各回答的用量账本（answerId -> 记录），供“本次用量”折叠展示。 */
    val usageMap=MutableStateFlow<Map<String,UsageRecord>>(emptyMap())
    val preview=MutableStateFlow<Bitmap?>(null)
    val ready=MutableStateFlow(false)
    val initialEditing=MutableStateFlow<Boolean?>(null)
    val enqueued=MutableSharedFlow<Unit>(extraBufferCapacity=1)
    val answerScale=preferences.answerTextScale.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),1)
    private var anchor: SourceAnchor?=null
    private var conversationId: String?=null
    private var job: Job?=null
    private var draftJob: Job?=null
    fun open(source: SourceAnchor) {
        if(anchor!=null)return
        anchor=source
        viewModelScope.launch {
            try {
                val id=repository.ensureConversation(source.id)
                conversationId=id
                val saved=repository.conversation(source.id).first()
                draft.value=saved?.draft.orEmpty()
                draftQuote.value=saved?.draftQuote
                title.value=saved?.title
                thinkingOverride.value=saved?.thinkingOverride ?: "inherit"
                val first=repository.messages(id).first()
                initialEditing.value=first.isEmpty()
                if(draft.value.isBlank() && draftQuote.value==null && first.isEmpty()) {
                    draft.value=if(source.kind=="text")"请解释这段原文的含义。" else "请解释圈选的图片、公式或图表。"
                }
                sources.value=repository.anchors(source.documentId).first().filter { it.discussionAnchorId==source.id && it.contextRemovedAt==null }
                ready.value=true
                launch { repository.anchors(source.documentId).collect { current ->
                    sources.value=current.filter { it.discussionAnchorId==source.id && it.contextRemovedAt==null }
                    if(current.none { it.id==source.id }) job?.cancel()
                } }
                launch { combine(repository.messages(id),sources) { all, attached ->
                    val ids=attached.map { it.id }.toSet()
                    all.filter { it.role!="source" || it.sourceAnchorId in ids }
                }.collect { messages.value=it } }
                launch { repository.cards(source.documentId).collect { all ->
                    qaCards.value=all.filter { it.cardType=="qa" && it.sourceConversationId==id }
                        .associateBy { it.sourceMessageId }
                } }
                launch { refreshUsage() }
                source.imageAssetId?.let { asset ->
                    preview.value=withContext(Dispatchers.IO) {
                        val bytes=repository.image(asset)
                        BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=2 })
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error.value="无法读取对话或原图，请关闭后重试" }
        }
    }
    fun editDraft(text: String) {
        draft.value=text.take(8000)
        scheduleDraftSave()
    }
    /**
     * 把已结束 AI 回答的局部选文设为待发送引用。
     * 只信任本地消息库里的 ID；替换引用时保留问题草稿并给轻量提示。
     */
    fun quoteFrom(messageId: String, text: String) {
        val convId=conversationId ?: return
        val msg=messages.value.firstOrNull { it.id==messageId }
        if(msg==null) { error.value="原回答已不在当前对话中，无法引用";return }
        if(msg.role!="assistant" || msg.status in listOf("queued","generating")) { error.value="只能引用已经结束的 AI 回答";return }
        val trimmed=text.trim()
        if(trimmed.isEmpty())return
        if(trimmed.length>QUOTE_LIMIT) { error.value="选区过长（${trimmed.length} 字符），请缩短到 $QUOTE_LIMIT 字符以内";return }
        quoteNotice.value=if(draftQuote.value!=null)"已替换引用，问题草稿保留" else null
        draftQuote.value=AnswerQuote(messageId,convId,trimmed)
        scheduleDraftSave()
    }
    /** 只取消引用，不删除问题草稿。 */
    fun clearQuote() {
        if(draftQuote.value==null)return
        draftQuote.value=null
        quoteNotice.value=null
        scheduleDraftSave()
    }
    private fun scheduleDraftSave() {
        draftJob?.cancel()
        draftJob=viewModelScope.launch { delay(250);flushDraft() }
    }
    suspend fun flushDraft() { conversationId?.let { repository.draft(it,draft.value,draftQuote.value) } }
    fun saveDraft() { viewModelScope.launch { runCatching { flushDraft() } } }
    fun stop() { job?.cancel() }
    fun send(retry: ChatMessage?=null) {
        if(sending.value || !ready.value)return
        val source=anchor ?: return
        val cfg=config.value.let { base ->
            if(hasImages || thinkingProvider(base.baseUrl)==null) base.copy(thinkingMode="disabled")
            else base.copy(thinkingMode=when(thinkingOverride.value) {
                "enabled" -> "enabled"; "disabled" -> "disabled"; else -> base.thinkingMode
            })
        }
        val question=draft.value.trim()
        val quote=draftQuote.value.takeIf { retry==null }
        if(retry==null && question.isBlank())return
        if(!cfg.keyPresent) { error.value="请先在模型设置中保存 API Key";return }
        if(hasImages && (!cfg.imageEnabled || cfg.imageModel.isBlank())) {
            error.value="圈选问答需要图片模型，请在设置中填写图片模型并启用看图能力";return
        }
        sending.value=true;error.value=null
        thinkingText.value="";thinkingCut.value=false;lastUsage.value=null
        streamState.value=StreamStatus(phase=GenPhase.Preparing,startedAt=SystemClock.elapsedRealtime())
        job=viewModelScope.launch {
            var answerId: String?=null
            val buffer=StringBuilder()
            var persister: Job?=null
            try {
                val attached=repository.anchors(source.documentId).first().filter { it.discussionAnchorId==source.id && it.contextRemovedAt==null }
                val attachedIds=attached.map { it.id }.toSet()
                val useImages=attached.any { it.kind=="region" }
                require(attached.none { it.kind=="region" && it.imageAssetId==null }) { "关联图片缺失，请移除该资料后重新补充" }
                require(!useImages || (cfg.imageEnabled && cfg.imageModel.isNotBlank())) { "补充图片需要启用图片模型" }
                val model=if(useImages)cfg.imageModel else cfg.textModel
                val existing=repository.messages(requireNotNull(conversationId)).first()
                    .filter { it.role!="source" || it.sourceAnchorId in attachedIds }
                if(retry!=null)require(existing.lastOrNull()?.id==retry.id) { "只能重试最后一条中断回答" }
                val queued=repository.enqueue(source.id,question,quote,model,cfg.name,source.kind=="region" && source.id in attachedIds,retry?.id)
                answerId=queued.answerId
                if(retry==null) { draftJob?.cancel();draft.value="";draftQuote.value=null;quoteNotice.value=null }
                enqueued.tryEmit(Unit)
                repository.updateAnswer(queued.answerId,"","generating")
                live.value=queued.answerId to ""
                streamState.value=streamState.value.copy(phase=GenPhase.Waiting,answerId=queued.answerId)
                var lastThinkingSaved=0
                persister=launch {
                    while(isActive) {
                        delay(500)
                        repository.updateAnswer(queued.answerId,buffer.toString(),"generating")
                        // 思考同样周期持久化（开关开启时）：强杀进程/崩溃也能留到最近一段
                        val t=thinkingText.value
                        if(t.length>lastThinkingSaved && runCatching { preferences.keepThinking.first() }.getOrDefault(false)) {
                            runCatching { repository.thinking(queued.answerId,t);lastThinkingSaved=t.length }
                        }
                    }
                }
                val primary=attached.firstOrNull { it.id==source.id }
                val image=primary?.imageAssetId?.takeIf { primary.kind=="region" }?.let { repository.image(it) }
                val description=if(primary==null)"原始资料已由用户移除。以仍关联的补充资料为依据，不将历史回答当成教材原文。" else "第 ${source.pageIndex+1} 页\n"+if(source.kind=="text")source.quote else "用户圈选的局部图片，每轮追问均附上相同原图。"
                // 逻辑轮次装配：重试取最新有效回答、未完成回答显式标注；引用快照随所属问题保留
                val retryHistory=if(retry!=null)existing.take(existing.indexOfLast { it.role=="user" }+1) else existing
                val full=ConversationHarness.history(retryHistory)
                val outputLimit=cfg.outputBudget
                val budget=ConversationHarness.inputLimit(cfg,outputLimit)
                val sourceHash=ConversationHarness.hash(description)
                // 有效阶段摘要（覆盖范围指纹不符自动失效为 0）
                val saved=runCatching { repository.contextSummary(requireNotNull(conversationId)) }.getOrNull()
                val oldBoundary=ConversationHarness.olderBoundary(full,ConversationHarness.KEEP_RECENT_TURNS)
                var covered=ConversationHarness.coveredCount(saved,sourceHash,full).takeIf { it<=oldBoundary } ?: 0
                var summaryText=if(covered>0) saved!!.text else null
                fun assemble(): List<ModelTurn> = ConversationHarness.assemble(full,covered,summaryText,
                    if(retry==null)ModelTurn("user",question,quote?.textSnapshot) else null)
                fun estimate()=ConversationHarness.estimate(AiRequest(cfg,model,description,assemble(),image,outputLimit=outputLimit))
                val minimal=ConversationHarness.assemble(full,oldBoundary,null,
                    if(retry==null)ModelTurn("user",question,quote?.textSnapshot) else null)
                if(ConversationHarness.estimate(AiRequest(cfg,model,description,minimal,image,outputLimit=outputLimit))>budget)
                    error("资料与最近 4 轮问答本身已超出输入预算；请移除不需要的资料，或提高输入预算／上下文容量、降低回答上限。未发起付费摘要。")
                val actionId=UUID.randomUUID().toString()
                // 每日预算先查后花：整理调用也要花钱，进入整理前先挡一道
                checkBudget(cfg,0)
                // 超预算：发送前整理较早对话（≤3 次；按预算分块；停止/失败不提交半成品）
                var summarizeCalls=0
                var summaryCostYuan=0.0
                while(estimate()>budget && summarizeCalls<3 && covered<oldBoundary) {
                    if(streamState.value.summarizeAt==0L) {
                        streamState.value=streamState.value.copy(phase=GenPhase.Summarizing,summarizeAt=SystemClock.elapsedRealtime())
                    } else streamState.value=streamState.value.copy(phase=GenPhase.Summarizing)
                    // 分块推进：单块估算不超过预算 2/3，绝不把超限历史一次性塞进摘要请求
                    val overhead=ConversationHarness.tokens(ReadingPrompts.COMPACT)+256+
                        (summaryText?.let { ConversationHarness.tokens(ReadingPrompts.summary(it))+24 } ?: 0)
                    val target=ConversationHarness.compactionEnd(full,covered,ConversationHarness.KEEP_RECENT_TURNS,budget*2/3,overhead)
                    if(target==covered)break
                    val chunkTokens=overhead+full.subList(covered,target).sumOf { ConversationHarness.tokens(ReadingPrompts.turn(it))+24 }
                    val chunkText=buildString {
                        if(summaryText!=null)append("已有摘要：\n"+summaryText+"\n\n")
                        append("需要并入摘要的对话记录：\n\n")
                        full.subList(covered,target).forEach { append(ReadingPrompts.turn(it)+"\n\n") }
                    }
                    var result: String?=null
                    var sumFailed: String?=null
                    var sumUsage: TokenUsage?=null
                    try {
                        provider.stream(AiRequest(cfg.copy(thinkingMode="disabled"),model,chunkText,
                            listOf(ModelTurn("user","请整理以上对话记录。")),outputLimit=1024,purpose="summary")).collect { ev ->
                            when(ev) {
                                is AiEvent.AnswerDelta -> result=(result?:"")+ev.text
                                is AiEvent.Usage -> sumUsage=ev.value
                                is AiEvent.Finished -> if(ev.reason!=FinishReason.Stop) sumFailed="摘要被截断，未提交"
                                is AiEvent.Failed -> sumFailed=ev.message
                                else -> {}
                            }
                        }
                    } catch (e: CancellationException) {
                        // 取消的整理调用同样入账（无 usage 则“费用待确认”），不当免费
                        recordUsage(actionId,queued.answerId,"summary",cfg,model,chunkTokens,sumUsage)
                        throw e
                    }
                    summarizeCalls++
                    // 整理调用同样计费入账（同一动作），别让整理白花钱不进账本
                    val sumCost=ConversationHarness.usageCost(cfg,sumUsage) ?: 0.0
                    recordUsage(actionId,queued.answerId,"summary",cfg,model,chunkTokens,sumUsage)
                    summaryCostYuan+=sumCost
                    val text=result?.trim().orEmpty()
                    if(text.isEmpty()) error(sumFailed ?: "对话整理失败，请重试")
                    if(sumFailed!=null) error("对话整理$sumFailed；本次未提交摘要，请重试或调高输出上限")
                    summaryText=text
                    covered=target
                    runCatching { repository.saveContextSummary(requireNotNull(conversationId),
                        ContextSummary(sourceHash,full[target-1].id.orEmpty(),ConversationHarness.historyHash(full.take(target)),text,1,model)) }
                }
                val history=assemble()
                val estInput=ConversationHarness.estimate(AiRequest(cfg,model,description,history.toList(),image,outputLimit=outputLimit))
                // 整理后仍超预算：明确报错，绝不静默截断教材条件（文档 5.2/5.4）
                if(estInput>budget) {
                    error("资料与最近 4 轮问答仍超出输入预算；请移除不需要的补充资料，或提高输入预算／上下文容量、降低回答上限。近期问答未被自动删减。")
                }
                checkBudget(cfg,estInput,summaryCostYuan) // 超单次/每日预算即拦截（含整理花费）
                val images=full.mapNotNull { it.imageAssetId }.distinct().associateWith { repository.image(it) }
                var lastSaved=0
                var failure: AiEvent.Failed?=null
                var truncated=false
                try {
                provider.stream(AiRequest(cfg,model,description,history,image,outputLimit=outputLimit,images=images)).collect { ev ->
                    when(ev) {
                        is AiEvent.ThinkingDelta -> {
                            val st=streamState.value
                            if(st.thinkingAt==0L) streamState.value=st.copy(phase=GenPhase.Thinking,thinkingAt=SystemClock.elapsedRealtime())
                            appendThinking(ev.text)
                        }
                        is AiEvent.AnswerDelta -> {
                            val st=streamState.value
                            if(st.answerAt==0L) streamState.value=st.copy(phase=GenPhase.Answering,answerAt=SystemClock.elapsedRealtime())
                            buffer.append(ev.text)
                            live.value=queued.answerId to buffer.toString()
                            if(buffer.length-lastSaved>=200) {
                                repository.updateAnswer(queued.answerId,buffer.toString(),"generating");lastSaved=buffer.length
                            }
                        }
                        is AiEvent.Usage -> lastUsage.value=ev.value
                        is AiEvent.Finished -> if(ev.reason==FinishReason.Length) truncated=true
                        is AiEvent.Failed -> failure=ev
                    }
                }
                } catch (e: CancellationException) {
                    // 取消的回答调用同样入账（无 usage 则“费用待确认”），不当免费
                    recordUsage(actionId,queued.answerId,"answer",cfg,model,estInput)
                    throw e
                }
                persister.cancelAndJoin();persister=null
                val endAt=SystemClock.elapsedRealtime()
                val finalState=streamState.value
                when {
                    failure!=null -> {
                        // 失败保留已收正文；连接断开/空回答等语义由事件携带
                        repository.updateAnswer(queued.answerId,buffer.toString(),"failed",failure!!.message)
                        streamState.value=finalState.copy(phase=GenPhase.Failed,endedAt=endAt)
                    }
                    truncated -> {
                        // finish_reason=length：标为未完成，不当正常完成
                        repository.updateAnswer(queued.answerId,buffer.toString(),"truncated","输出达到上限，回答未完成；可重试或继续追问")
                        streamState.value=finalState.copy(phase=GenPhase.Done,endedAt=endAt)
                    }
                    else -> {
                        repository.updateAnswer(queued.answerId,buffer.toString(),"complete")
                        streamState.value=finalState.copy(phase=GenPhase.Done,endedAt=endAt)
                    }
                }
                persistThinking(queued.answerId)
                recordUsage(actionId,queued.answerId,"answer",cfg,model,estInput)
                // 首条完成的回答：本地生成会话标题（取消默认付费起题，7.6）；失败/截断不起题
                if(failure==null && !truncated) runCatching {
                    if(repository.conversation(source.id).first()?.title.isNullOrBlank()) {
                        val convId=requireNotNull(conversationId)
                        val headQuestion=existing.firstOrNull { it.role=="user" }?.content ?: question
                        val local=headQuestion.replace('\n',' ').trim().take(24)
                        if(local.isNotBlank()) {
                            repository.title(convId,local)
                            title.value=local
                        }
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    persister?.cancelAndJoin()
                    answerId?.let { repository.updateAnswer(it,buffer.toString(),"stopped") }
                    streamState.value=streamState.value.copy(phase=GenPhase.Stopped,endedAt=SystemClock.elapsedRealtime())
                    persistThinking(answerId)
                }
                throw e
            } catch (e: Exception) {
                persister?.cancelAndJoin()
                val message=e.message?.takeIf { it.length<180 } ?: "请求失败，问题和已收到的回答已保存"
                error.value=message
                answerId?.let { repository.updateAnswer(it,buffer.toString(),"failed",message) }
                streamState.value=streamState.value.copy(phase=GenPhase.Failed,endedAt=SystemClock.elapsedRealtime())
            } finally { live.value=null;sending.value=false }
        }
    }
    override fun onCleared() {
        val id=conversationId;val text=draft.value;val quote=draftQuote.value
        if(id!=null)CoroutineScope(SupervisorJob()+Dispatchers.IO).launch { runCatching { repository.draft(id,text,quote) } }
    }
}
