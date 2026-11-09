package com.qcenaman.remoteai

import com.qcenaman.remoteai.data.MemoryConversationStore
import com.qcenaman.remoteai.data.Settings
import com.qcenaman.remoteai.data.SettingsStore
import com.qcenaman.remoteai.net.HUB_PRESETS
import com.qcenaman.remoteai.net.HubState
import com.qcenaman.remoteai.net.OpenCodeClient
import com.qcenaman.remoteai.net.hubStartScript
import com.qcenaman.remoteai.net.openCodeLinkScript
import com.qcenaman.remoteai.net.parseHubStates
import com.qcenaman.remoteai.net.parseOpenCodePermissions
import com.qcenaman.remoteai.net.parseOpenCodeTurn
import kotlinx.coroutines.runBlocking
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

    @Test
    fun readsHubStates() {
        val m = parseHubStates("""{"data":[{"id":"coder","status":{"value":"loaded"}},{"id":"ripple","status":{"value":"loading"}},{"id":"deepseek","status":{"value":"unloaded","failed":true}}]}""")
        assertEquals(HubState.LOADED, m["coder"])
        assertEquals(HubState.LOADING, m["ripple"])
        assertEquals(HubState.FAILED, m["deepseek"])
    }

    @Test
    fun startScriptUsesTheShortcutPreset() {
        val script = hubStartScript(HUB_PRESETS.first { it.id == "ripple" })
        assertTrue(script.contains("ripple-64k.ini"))
        assertFalse(script.contains("\u00a7"))
    }

    @Test
    fun linkScriptStaysOnTheTailnet() {
        val script = openCodeLinkScript(4096)
        assertTrue(script.contains("tailscale serve --bg --tcp 4096"))
        assertFalse(script.contains("funnel"))
    }

    @Test
    fun turnKeepsOnlyNewAssistantWorkOldestFirst() {
        val body = """{"data":[
            {"id":"m3","type":"assistant","content":[{"type":"reasoning","text":"think"},{"type":"text","text":"done"}]},
            {"id":"m2","type":"assistant","content":[{"type":"tool","name":"read","state":{"status":"completed","input":{"path":"a"},"content":{"type":"text","text":"file"}}},
                {"type":"tool","name":"bash","state":{"status":"error","input":{},"error":{"message":"nope"}}},{"type":"text","text":"first"}]},
            {"id":"m1","type":"assistant","content":[{"type":"text","text":"old"}]}]}"""
        val r = parseOpenCodeTurn(body, setOf("m1"))
        assertEquals("first\n\ndone", r.text)
        assertEquals("think", r.reasoning)
        assertEquals(listOf("read", "bash"), r.toolCalls.map { it.tool })
        assertEquals("file", r.toolCalls[0].output)
        assertEquals("Failed: nope", r.toolCalls[1].output)
    }

    @Test
    fun readsPermissionRequests() {
        val a = parseOpenCodePermissions("""{"data":[{"id":"per_1","sessionID":"ses_1","action":"bash","resources":["del x","del y"]}]}""").single()
        assertEquals("per_1", a.id)
        assertEquals("bash: del x, del y", a.what)
        assertTrue(a.waiting)
    }

    @Test
    fun pickingAHubModelRunsPromptsThroughOpenCode() = runBlocking {
        val polls = AtomicInteger()
        val started = AtomicInteger()
        val runningPolls = AtomicInteger()
        val prompts = AtomicInteger()
        val log = Collections.synchronizedList(mutableListOf<String>())
        val done = """{"data":[{"id":"m9","type":"assistant","content":[{"type":"tool","name":"read","state":{"status":"completed","input":{},"content":{"type":"text","text":"ok"}}},{"type":"text","text":"hello"}]}]}"""
        val pc = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    if (path.startsWith("/api/") && request.getHeader("Authorization").isNullOrBlank()) return MockResponse().setResponseCode(401)
                    return when {
                        path == "/mcp" -> {
                            val b = request.body.readUtf8()
                            started.incrementAndGet()
                            MockResponse().setBody("""{"result":{"content":[{"type":"text","text":"Hub started\nOCPW:secret"}]}}""")
                        }
                        path == "/models" -> when {
                            started.get() == 0 -> MockResponse().setResponseCode(503)
                            polls.incrementAndGet() < 2 -> MockResponse().setBody("""{"data":[{"id":"ripple","status":{"value":"loading"}}]}""")
                            else -> MockResponse().setBody("""{"data":[{"id":"ripple","status":{"value":"loaded"}}]}""")
                        }
                        path == "/api/session" -> {
                            log += request.body.readUtf8()
                            MockResponse().setBody("""{"data":{"id":"ses_1"}}""")
                        }
                        path == "/api/session/ses_1/prompt" -> {
                            log += request.body.readUtf8()
                            prompts.incrementAndGet()
                            runningPolls.set(0)
                            MockResponse().setBody("{}")
                        }
                        path == "/api/session/active" -> MockResponse().setBody(
                            if (runningPolls.incrementAndGet() < 3) """{"data":{"ses_1":{"type":"running"}}}""" else """{"data":{}}"""
                        )
                        path == "/api/session/ses_1/permission" -> MockResponse().setBody("""{"data":[]}""")
                        path.startsWith("/api/session/ses_1/message") -> MockResponse().setBody(if (prompts.get() == 0 || runningPolls.get() == 0) """{"data":[]}""" else done)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        try {
            val settings = FakeSettings(Settings(host = "127.0.0.1", lmPort = 1, approvalPort = 1, tokenSaverPort = pc.port, hubPort = pc.port,
                openCodePort = pc.port, tokenSaverToken = "t", projectFolder = "D:\\work"))
            val c = ChatController(
                settings,
                MemoryConversationStore(),
                openCodeFactory = { b, p -> OpenCodeClient(b, p, pollMs = 5) },
                hubPollMs = 5,
            )
            c.refreshModels()
            assertTrue(c.state.value.models.any { it.id == "deepseek" && it.hub && !it.loaded })
            assertTrue(c.chooseModel("ripple"))
            assertEquals("ripple", c.state.value.model)
            c.send("one")
            val items = c.state.value.conversation.items
            assertEquals("hello", items.last().text)
            assertEquals("read", items.first { it.role == "tool" }.tool)
            assertEquals("ses_1", c.state.value.conversation.responseId)
            assertEquals("secret", settings.s.openCodePassword)
            assertTrue(log.first().contains("\"agent\":\"elai-ripple\""))
            assertTrue(log.first().contains("D:\\\\work"))
            c.send("two")
            assertEquals(2, prompts.get())
            assertEquals(1, log.count { it.contains("location") })
        } finally {
            pc.shutdown()
        }
    }
}
