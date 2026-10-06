package com.yuejian.model

import org.junit.Assert.*
import org.junit.Test

class ConversationMemoryTest {
    private fun rounds(count: Int)=(1..count).flatMap { listOf(
        ModelTurn("user","问题 $it",id="q$it"),ModelTurn("assistant","回答 $it",id="a$it")) }
    @Test fun protectsFourCompleteRecentRounds() {
        val history=rounds(8)
        val end=ConversationHarness.compactionEnd(history,0,4,10000,100)
        assertEquals(8,end)
        assertEquals("a4",history[end-1].id)
        assertEquals("q5",history[end].id)
        val sent=ConversationHarness.assemble(history,end,"较早摘要",ModelTurn("user","新问题"))
        assertEquals(history.drop(8),sent.drop(1).dropLast(1))
    }
    @Test fun fourRoundsOrFewerAreNeverSummarized() {
        assertEquals(0,ConversationHarness.compactionEnd(rounds(4),0,4,10000,0))
    }
    @Test fun largeOldAnswerDoesNotGetSplitFromItsQuestion() {
        val history=rounds(6).toMutableList()
        history[1]=ModelTurn("assistant","大".repeat(5000),id="a1")
        assertEquals(0,ConversationHarness.compactionEnd(history,0,4,1000,100))
    }
    @Test fun supplementsDoNotCountAsQuestionRoundsAndSurviveCompaction() {
        val source=ModelTurn("user","补充资料",id="s1",imageAssetId="img",isSource=true)
        val history=rounds(6).toMutableList().apply { add(2,source) }
        val end=ConversationHarness.compactionEnd(history,0,4,10000,0)
        assertEquals(5,end)
        val sent=ConversationHarness.assemble(history,end,"摘要",null)
        assertEquals(source,sent.first())
        assertEquals(history.drop(end),sent.drop(2))
        assertEquals(1,sent.count { it.id=="s1" })
    }
    @Test fun newSourcesAppendWithoutChangingExistingConversationPrefix() {
        val old=rounds(2)
        val source=ModelTurn("user","新的依据",id="s",isSource=true)
        val sent=ConversationHarness.assemble(old+source,0,null,ModelTurn("user","继续"))
        assertEquals(old,sent.take(old.size))
        assertEquals(source,sent[old.size])
    }
    @Test fun imageBudgetIncludesRootAndSupplementImages() {
        val cfg=ModelConfig(imageTokenBudget=1000)
        val text=AiRequest(cfg,"m","原文",listOf(ModelTurn("user","问题")))
        val pictures=text.copy(image=byteArrayOf(1),history=text.history+ModelTurn("user","补充",imageAssetId="img",isSource=true))
        assertTrue(ConversationHarness.estimate(pictures)-ConversationHarness.estimate(text)>=2000)
    }
    @Test fun sourceRemovalInvalidatesSummaryOfAffectedHistory() {
        val source=ModelTurn("user","定义 n",id="s",isSource=true)
        val full=rounds(6).toMutableList().apply { add(2,source) }
        val end=ConversationHarness.olderBoundary(full,4)
        val summary=ContextSummary("root",full[end-1].id!!,ConversationHarness.historyHash(full.take(end)),"摘要")
        assertEquals(end,ConversationHarness.coveredCount(summary,"root",full))
        assertEquals(0,ConversationHarness.coveredCount(summary,"root",full.filterNot { it.isSource }))
    }
    @Test fun persistedSourceMessagesKeepTheQuestionAnswerPairs() {
        fun m(id: String,role: String,image: String?=null)=ChatMessage(id,"c",role,id,"complete",null,null,image,1)
        val history=ConversationHarness.history(listOf(m("q1","user"),m("a1","assistant"),m("s","source","image"),m("q2","user"),m("a2","assistant")))
        assertEquals(listOf("q1","a1","s","q2","a2"),history.map { it.id })
        assertTrue(history[2].isSource)
        assertEquals("image",history[2].imageAssetId)
    }
}
