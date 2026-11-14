package com.qcenaman.remoteai

import com.qcenaman.remoteai.data.MemoryConversationStore
import com.qcenaman.remoteai.data.Settings
import com.qcenaman.remoteai.data.SettingsStore
import com.qcenaman.remoteai.net.OpenCodeClient
import com.qcenaman.remoteai.net.Pc
import com.qcenaman.remoteai.net.parseOpenCodeSessions
import com.qcenaman.remoteai.net.parseOpenCodeTranscript
import com.qcenaman.remoteai.net.pickWindowSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class PcLiveTest {
    private class FakeSettings(var s: Settings) : SettingsStore {
        override fun load() = s
        override fun save(s: Settings) { this.s = s }
    }

    private val sessions = """{"data":[
        {"id":"ses_new","title":"Idle one","time":{"created":5,"updated":50,"busy":40,"idle":45},"location":{"directory":"/p/a"}},
        {"id":"ses_old","title":"Working","time":{"created":1,"updated":30,"busy":31,"idle":20},"location":{"directory":"/p/b"}}]}"""

    @Test
    fun theWindowsChatIsTheWorkingOneElseTheLatest() {
        val list = parseOpenCodeSessions(sessions)
        assertEquals("/p/a", list[0].directory)
        assertFalse(list[0].busy)
        assertTrue(list[1].busy)
        assertEquals("ses_old", pickWindowSession(list, emptySet())!!.id)
        assertEquals("ses_new", pickWindowSession(list.map { it.copy(busy = false) }, emptySet())!!.id)
        assertEquals("ses_new", pickWindowSession(list.map { it.copy(busy = false) }, setOf("ses_new"))!!.id)
        assertNull(pickWindowSession(emptyList(), emptySet()))
    }

    @Test
    fun transcriptKeepsTheWindowsOrder() {
        val body = """{"data":[
            {"id":"m4","type":"idle"},
            {"id":"m3","type":"assistant","content":[
                {"type":"reasoning","text":"think"},{"type":"text","text":"running it"},
                {"type":"tool","name":"bash","state":{"status":"completed","input":{"cmd":"ls"},"content":[{"type":"text","text":"a.txt"}]}},
                {"type":"text","text":"done"}]},
            {"id":"m2","type":"user","text":"typed on the PC"},
            {"id":"m1","type":"user","text":"first"}]}"""
        val items = parseOpenCodeTranscript(body)
        assertEquals(listOf("user", "user", "assistant", "tool", "assistant"), items.map { it.role })
        assertEquals("typed on the PC", items[1].text)
        assertEquals("think", items[2].reasoning)
        assertEquals("bash", items[3].tool)
        assertEquals("a.txt", items[3].text)
        assertTrue(items[3].args.contains("ls"))
        assertEquals("done", items[4].text)
    }

    @Test
    fun pickingTheLaptopStartsItThenMirrorsAndSendsIntoItsWindow() = laptopFlow(elsewhere = false)

    @Test
    fun workInTheLaptopsOwnWindowIsJoinedAndMessagesWaitForItsTurn() = laptopFlow(elsewhere = true)

    private fun laptopFlow(elsewhere: Boolean) = runBlocking {
        val idle = java.util.concurrent.atomic.AtomicBoolean(false)
        val mcp = Collections.synchronizedList(mutableListOf<String>())
        val prompts = Collections.synchronizedList(mutableListOf<String>())
        val transcript = java.util.concurrent.atomic.AtomicReference("""{"data":[{"id":"m1","type":"user","text":"hi from the laptop"}]}""")
        val laptop = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    if (path == "/mcp") {
                        mcp += request.body.readUtf8()
                        return MockResponse().setBody("""{"result":{"content":[{"type":"text","text":"Job x: exited\n$ echo \\\"OCPW:$(cat \\\"${'$'}HOME/ELAI/remote/password\\\")\\\"\nFolder: /p/a\nREADY\nOCPW:lpw"}]}}""")
                    }
                    if (request.getHeader("Authorization") != okhttp3.Credentials.basic("opencode", "lpw")) return MockResponse().setResponseCode(401)
                    return when {
                        path.startsWith("/api/session?") -> MockResponse().setBody(if (idle.get()) sessions.replace("\"idle\":20", "\"idle\":99") else sessions)
                        path == "/api/session/active" -> MockResponse().setBody(if (elsewhere || idle.get()) """{"data":{}}""" else """{"data":{"ses_old":{}}}""")
                        path == "/api/session/ses_old/permission" -> MockResponse().setBody("""{"data":[{"id":"per_1","action":"bash","resources":["rm x"]}]}""")
                        path.startsWith("/api/session/ses_old/message") -> MockResponse().setBody(transcript.get())
                        path == "/api/session/ses_old/prompt" -> {
                            prompts += request.body.readUtf8()
                            transcript.set("""{"data":[{"id":"m2","type":"user","text":"from phone"},{"id":"m1","type":"user","text":"hi from the laptop"}]}""")
                            MockResponse().setBody("{}")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        try {
            val settings = FakeSettings(Settings(host = "127.0.0.1", lmPort = 1, approvalPort = 1, tokenSaverPort = 1, hubPort = 1,
                laptopHost = "127.0.0.1", laptopTokenSaverPort = laptop.port, laptopOpenCodePort = laptop.port, laptopTokenSaverToken = "lt"))
            val c = ChatController(settings, MemoryConversationStore(), openCodeFactory = { b, p -> OpenCodeClient(b, p, pollMs = 5) })
            assertTrue(c.selectPc(Pc.LAPTOP))
            // Already running: only the password is fetched, nothing is started or restarted.
            assertTrue(mcp.single().contains("remote/password"))
            assertFalse(mcp.single().contains("start"))
            assertEquals("laptop", settings.s.pc)
            assertEquals("lpw", settings.s.laptopOpenCodePassword)
            val st = c.state.value
            assertEquals(Pc.LAPTOP, st.pc)
            assertTrue(st.pcWorking)
            assertEquals("ses_old", st.conversation.responseId)
            assertEquals("hi from the laptop", st.conversation.items.single().text)
            assertEquals("per_1", st.pending.single().id)

            c.send("from phone")
            if (elsewhere) {
                assertTrue(prompts.isEmpty())
                assertEquals(listOf("from phone"), c.state.value.pcQueued)
                assertEquals("note", c.state.value.conversation.items.last().role)
                idle.set(true) // the window's turn ended
                c.pollLive()
                assertTrue(c.state.value.pcQueued.isEmpty())
            }
            assertTrue(prompts.single().contains("from phone"))
            assertEquals(listOf("hi from the laptop", "from phone"), c.state.value.conversation.items.map { it.text })

            c.newConversation()
            assertNull(c.state.value.pc)
            assertEquals("", settings.s.pc)
            assertTrue(c.state.value.pending.isEmpty())
        } finally {
            laptop.shutdown()
        }
    }

    @Test
    fun thinkingAndTextStreamInLiveLikeOnThePcWindow() = runBlocking {
        val ev = { type: String, data: String -> "data: {\"id\":\"e\",\"type\":\"$type\",\"data\":$data}\n\n" }
        val m = "\"sessionID\":\"ses_old\",\"assistantMessageID\":\"msg_9\",\"ordinal\":0"
        val stream = ev("server.connected", "{}") + ": heartbeat\n\n" +
            ev("session.reasoning.delta", "{$m,\"delta\":\"Let me\"}") +
            ev("session.reasoning.delta", "{$m,\"delta\":\" check\"}") +
            ev("session.reasoning.delta", "{\"sessionID\":\"ses_other\",\"assistantMessageID\":\"x\",\"ordinal\":0,\"delta\":\"not this\"}") +
            ev("session.tool.input.started", "{$m,\"id\":\"t1\",\"name\":\"write\"}") +
            ev("session.tool.input.delta", "{$m,\"id\":\"t1\",\"delta\":\"abcde\"}") +
            ev("session.text.delta", "{$m,\"delta\":\"Two issues:\"}")
        val laptop = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    return when {
                        path == "/mcp" -> MockResponse().setBody("""{"result":{"content":[{"type":"text","text":"OCPW:lpw"}]}}""")
                        path.startsWith("/api/session?") -> MockResponse().setBody(sessions)
                        path == "/api/session/active" -> MockResponse().setBody("""{"data":{"ses_old":{}}}""")
                        path == "/api/session/ses_old/permission" -> MockResponse().setBody("""{"data":[]}""")
                        path.startsWith("/api/session/ses_old/message") -> MockResponse().setBody(
                            """{"data":[{"id":"msg_9","type":"assistant","content":[{"type":"reasoning","text":""},{"type":"tool","name":"write","state":{"status":"streaming"}}]},{"id":"m1","type":"user","text":"go"}]}""")
                        path == "/api/event" -> MockResponse().setHeader("Content-Type", "text/event-stream").setBody(stream)
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        try {
            val settings = FakeSettings(Settings(lmPort = 1, approvalPort = 1, tokenSaverPort = 1, hubPort = 1,
                laptopHost = "127.0.0.1", laptopTokenSaverPort = laptop.port, laptopOpenCodePort = laptop.port, laptopTokenSaverToken = "lt"))
            val c = ChatController(settings, MemoryConversationStore())
            assertTrue(c.selectPc(Pc.LAPTOP))
            // Saved so far: the prompt and a tool still being written (not a failure).
            assertEquals("(writing its input…)", c.state.value.conversation.items.single { it.role == "tool" }.text)
            c.streamLive()
            val live = c.state.value.conversation.items.filter { it.streaming }
            assertEquals("Let me check", live[0].reasoning)
            assertEquals("write", live[1].tool)
            assertTrue(live[1].text.contains("5 characters"))
            assertEquals("Two issues:", live[2].text)
            assertFalse(c.state.value.conversation.items.any { it.reasoning.contains("not this") })
        } finally {
            laptop.shutdown()
        }
    }

    @Test
    fun theEchoedCommandIsNotThePassword() {
        assertEquals("pw1", passwordIn("Job 1\n$ echo \"OCPW:\$(cat \"\$HOME/ELAI/remote/password\")\"\nLast 1 of 1 lines:\n  OCPW:pw1"))
        assertNull(passwordIn("$ echo \"OCPW:\$(cat x)\""))
    }

    @Test
    fun laptopGpuInUseIsReportedNotOverridden() = runBlocking {
        val laptop = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse().setBody("""{"result":{"content":[{"type":"text","text":"GPU-BUSY\n123 python ComfyUI/main.py\nOCPW:x"}]}}""")
            }
            start()
        }
        try {
            val settings = FakeSettings(Settings(lmPort = 1, approvalPort = 1, tokenSaverPort = 1, hubPort = 1,
                laptopHost = "127.0.0.1", laptopTokenSaverPort = laptop.port, laptopTokenSaverToken = "lt"))
            val c = ChatController(settings, MemoryConversationStore())
            assertFalse(c.selectPc(Pc.LAPTOP))
            assertTrue(c.state.value.pcGpuBusy.contains("ComfyUI"))
        } finally {
            laptop.shutdown()
        }
    }
}
