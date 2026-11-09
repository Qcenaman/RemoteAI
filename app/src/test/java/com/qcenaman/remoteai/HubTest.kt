package com.qcenaman.remoteai

import com.qcenaman.remoteai.data.ChatItem
import com.qcenaman.remoteai.data.MemoryConversationStore
import com.qcenaman.remoteai.data.Settings
import com.qcenaman.remoteai.data.SettingsStore
import com.qcenaman.remoteai.net.HUB_PRESETS
import com.qcenaman.remoteai.net.HubState
import com.qcenaman.remoteai.net.HubStream
import com.qcenaman.remoteai.net.hubMessages
import com.qcenaman.remoteai.net.hubStartScript
import com.qcenaman.remoteai.net.parseHubStates
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class HubTest {
    private class FakeSettings(var s: Settings) : SettingsStore {
        override fun load() = s
        override fun save(s: Settings) { this.s = s }
    }

    private fun roles(a: kotlinx.serialization.json.JsonArray) = a.map { it.jsonObject["role"]!!.jsonPrimitive.content }
    private fun contents(a: kotlinx.serialization.json.JsonArray) = a.map { it.jsonObject["content"]!!.jsonPrimitive.content }

    @Test
    fun readsHubStates() {
        val m = parseHubStates("""{"data":[{"id":"coder","status":{"value":"loaded"}},{"id":"ripple","status":{"value":"loading"}},{"id":"deepseek","status":{"value":"unloaded","failed":true}}]}""")
        assertEquals(HubState.LOADED, m["coder"])
        assertEquals(HubState.LOADING, m["ripple"])
        assertEquals(HubState.FAILED, m["deepseek"])
    }

    @Test
    fun historyAlternatesAndSkipsNotes() {
        val items = listOf(ChatItem("assistant", "orphan"), ChatItem("user", "a"), ChatItem("note", "x"), ChatItem("user", "b"), ChatItem("assistant", "c"))
        val m = hubMessages(items, "d", 0)
        assertEquals(listOf("user", "assistant", "user"), roles(m))
        assertEquals(listOf("a\n\nb", "c", "d"), contents(m))
    }

    @Test
    fun historyKeepsTheNewestThatFit() {
        val big = "x".repeat(400)
        val items = listOf(ChatItem("user", "old $big"), ChatItem("assistant", big), ChatItem("user", "new"), ChatItem("assistant", "reply"))
        val m = hubMessages(items, "now", 40)
        assertEquals(listOf("new", "reply", "now"), contents(m))
    }

    @Test
    fun streamCollectsThinkingAnswerAndStats() {
        val s = HubStream()
        listOf(
            """data: {"choices":[{"delta":{"role":"assistant","content":null}}]}""",
            """data: {"choices":[{"delta":{"reasoning_content":"We"}}]}""",
            """data: {"choices":[{"delta":{"content":"Hi"}}]}""",
            """data: {"choices":[],"usage":{"completion_tokens":27,"prompt_tokens":54},"timings":{"predicted_per_second":80.5}}""",
            "data: [DONE]",
        ).forEach(s::feedLine)
        assertTrue(s.done)
        val r = s.reply()
        assertEquals("Hi", r.text)
        assertEquals("We", r.reasoning)
        assertEquals(54, r.stats!!.inputTokens)
        assertEquals(27, r.stats!!.outputTokens)
        assertEquals(80.5, r.stats!!.tokensPerSecond, 0.01)
        assertFalse(r.stats!!.estimated)
    }

    @Test
    fun startScriptUsesTheShortcutPreset() {
        val script = hubStartScript(HUB_PRESETS.first { it.id == "ripple" })
        assertTrue(script.contains("ripple-64k.ini"))
        assertTrue(script.contains("Hub started"))
        assertFalse(script.contains("§"))
    }

    @Test
    fun pickingAHubModelStartsItThenChatsWithHistory() = runBlocking {
        val polls = AtomicInteger()
        val started = AtomicInteger()
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\ndata: [DONE]\n\n"
        val pc = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/mcp" -> {
                        started.incrementAndGet()
                        MockResponse().setBody("""{"result":{"content":[{"type":"text","text":"Hub started"}]}}""")
                    }
                    "/models" -> when {
                        started.get() == 0 -> MockResponse().setResponseCode(503)
                        polls.incrementAndGet() < 2 -> MockResponse().setBody("""{"data":[{"id":"ripple","status":{"value":"loading"}}]}""")
                        else -> MockResponse().setBody("""{"data":[{"id":"ripple","status":{"value":"loaded"}}]}""")
                    }
                    "/v1/chat/completions" -> {
                        bodies += request.body.readUtf8()
                        MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
        try {
            val c = ChatController(
                FakeSettings(Settings(host = "127.0.0.1", lmPort = 1, approvalPort = 1, tokenSaverPort = pc.port, hubPort = pc.port, tokenSaverToken = "t")),
                MemoryConversationStore(),
                hubPollMs = 5,
            )
            c.refreshModels()
            assertTrue(c.state.value.models.any { it.id == "deepseek" && it.hub && !it.loaded })
            assertTrue(c.chooseModel("ripple"))
            assertEquals("ripple", c.state.value.model)
            assertEquals("ripple", c.state.value.settings.lastModel)
            assertEquals(Link.OK, c.state.value.lm)
            c.send("one")
            c.send("two")
            assertEquals("hello", c.state.value.conversation.items.last().text)
            assertTrue(bodies.last().contains("\"model\":\"ripple\""))
            assertTrue(bodies.last().contains("one"))
            assertTrue(bodies.last().contains("two"))
            assertFalse(bodies.last().contains("mcp/contextsaver"))
        } finally {
            pc.shutdown()
        }
    }
}
