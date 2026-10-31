package com.qcenaman.remoteai

import com.qcenaman.remoteai.net.ChatStream
import com.qcenaman.remoteai.net.ReplyStats
import com.qcenaman.remoteai.net.estimateTokens
import com.qcenaman.remoteai.net.parseChat
import com.qcenaman.remoteai.net.parseLoadedContexts
import com.qcenaman.remoteai.net.parseModels
import com.qcenaman.remoteai.net.promptLine
import com.qcenaman.remoteai.net.replyLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    @Test
    fun readsLmStudioStats() {
        val r = parseChat(
            """{"output":[{"type":"message","content":"hi"}],"stats":{"input_tokens":9000,"total_output_tokens":812,
               "reasoning_output_tokens":300,"tokens_per_second":42.13,"time_to_first_token_seconds":0.6}}"""
        )
        val s = r.stats!!
        assertEquals(9000, s.inputTokens)
        assertEquals(812, s.outputTokens)
        assertEquals(300, s.reasoningTokens)
        assertEquals(9812, s.contextUsed)
    }

    @Test
    fun formatsReplyAndPromptLines() {
        val s = ReplyStats(9000, 812, 300, 42.13, 0.6, 19.3, 32768)
        assertEquals("42.1 tok/s · 812 tokens (300 thinking) · 0.6 s to first · 19.3 s · context 9.8k / 32.8k (29%)", replyLine(s))
        val p = ReplyStats(inputTokens = 9000, contextLength = 32768, promptTokens = 120, estimated = true)
        assertEquals("≈120 tokens · context 9.0k / 32.8k (27%)", promptLine(p))
        assertEquals("≈3 tokens", promptLine(ReplyStats(promptTokens = estimateTokens("hello there"))))
    }

    @Test
    fun countsBrokenStreamAsEstimate() {
        var now = 0L
        val st = ChatStream(clock = { now })
        now = 500
        repeat(20) {
            now += 100
            st.feedLine("""data: {"type":"message.delta","content":"x"}""")
            st.feedLine("")
        }
        val s = st.partial().stats
        assertNotNull(s)
        assertTrue(s!!.estimated)
        assertEquals(20, s.outputTokens)
        assertTrue(replyLine(s).startsWith("≈"))
    }

    @Test
    fun readsContextSizes() {
        val v1 = """{"models":[{"key":"qwen3.6-27b","loaded_instances":[{"id":"qwen3.6-27b","config":{"context_length":32768}}]},
                   {"key":"other","loaded_instances":[]}]}"""
        assertEquals(mapOf("qwen3.6-27b" to 32768), parseLoadedContexts(v1))
        val v0 = """{"data":[{"id":"m","type":"llm","state":"loaded","max_context_length":131072}]}"""
        assertEquals(131072, parseModels(v0).single().contextLength)
    }
}
