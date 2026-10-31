package com.qcenaman.remoteai

import com.qcenaman.remoteai.data.MemoryConversationStore
import com.qcenaman.remoteai.data.Settings
import com.qcenaman.remoteai.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

class ControllerTest {
    private lateinit var lm: MockWebServer
    private lateinit var ap: MockWebServer
    private val apLog = Collections.synchronizedList(mutableListOf<String>())
    private val chatBodies = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var pending = """{"pending":[{"id":"abcd1234","what":"pc run: del C:\\tmp\\x","created":"","expires":"","status":"pending"}]}"""
    @Volatile private var lmCode = 200
    @Volatile private var apCode = 200
    @Volatile private var sse: String? = null
    @Volatile private var chatDelayMs = 0L
    @Volatile private var slowStream = false
    @Volatile private var chatReply = """{"output":[{"type":"message","content":"done"}],"response_id":"resp_2"}"""

    private class FakeSettings(var s: Settings) : SettingsStore {
        override fun load() = s
        override fun save(s: Settings) { this.s = s }
    }

    @Before
    fun setUp() {
        lm = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (lmCode != 200) return MockResponse().setResponseCode(lmCode).setBody("""{"error":"unauthorized"}""")
                    return when (request.path) {
                        "/api/v0/models" -> MockResponse().setBody(
                            """{"data":[{"id":"qwen3.6-27b","type":"llm","state":"loaded"},{"id":"nomic-embed","type":"embeddings","state":"loaded"}]}"""
                        )
                        "/api/v1/chat" -> {
                            chatBodies += request.body.readUtf8()
                            if (chatDelayMs > 0) Thread.sleep(chatDelayMs)
                            sse?.let {
                                MockResponse().setHeader("Content-Type", "text/event-stream").setBody(it)
                                    .apply { if (slowStream) throttleBody(64, 100, java.util.concurrent.TimeUnit.MILLISECONDS) }
                            }
                                ?: MockResponse().setBody(chatReply)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
        ap = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    apLog += "${request.method} ${request.path}"
                    if (apCode != 200) return MockResponse().setResponseCode(apCode)
                    return when {
                        request.method == "GET" && request.path == "/approvals" -> MockResponse().setBody(pending)
                        request.method == "POST" && request.path!!.endsWith("/approve") -> {
                            Thread.sleep(150)
                            pending = """{"pending":null}"""
                            MockResponse().setBody("""{"id":"abcd1234","status":"approved"}""")
                        }
                        request.method == "POST" && request.path!!.endsWith("/deny") -> {
                            pending = """{"pending":null}"""
                            MockResponse().setBody("""{"id":"abcd1234","status":"denied"}""")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            start()
        }
    }

    @After
    fun tearDown() {
        lm.shutdown()
        ap.shutdown()
    }

    private fun controller(lmToken: String = "lmtok", apToken: String = "aptok", maxTokens: Int = 8192) = ChatController(
        FakeSettings(Settings(host = "127.0.0.1", lmPort = lm.port, approvalPort = ap.port, lmToken = lmToken, approvalToken = apToken,
            maxReplyTokens = maxTokens)),
        MemoryConversationStore(),
    )

    private fun ev(type: String, content: String) =
        "event: $type\ndata: {\"type\":\"$type\",\"content\":\"$content\"}\n\n"

    @Test
    fun loopingThinkingStopsAndIsNotSentBack() = runBlocking {
        val c = controller()
        c.send("hello")
        assertEquals("resp_2", c.state.value.conversation.responseId)
        sse = ev("chat.start", "") + (1..8).joinToString("") { ev("reasoning.delta", "Search query: AdbSaver on the phone\\n") } +
            ev("message.delta", "never reached")
        c.send("Install AdbSaver on my phone")
        val items = c.state.value.conversation.items
        val answer = items.last { it.role == "assistant" }
        assertTrue(answer.text.contains("(stopped)"))
        assertTrue(answer.reasoning.contains("Search query"))
        val note = items.last()
        assertEquals("note", note.role)
        assertTrue(note.text.startsWith("Stopped: the model was going in circles"))
        assertEquals("Install AdbSaver on my phone", note.retry)
        assertEquals("history still ends at the last good reply", "resp_2", c.state.value.conversation.responseId)

        sse = null
        chatBodies.clear()
        c.retry(note.retry)
        val body = chatBodies.single()
        assertTrue(body.contains("\"input\":\"Install AdbSaver on my phone\""))
        assertTrue(body.contains("\"previous_response_id\":\"resp_2\""))
        assertTrue(!body.contains("Search query"))
    }

    @Test
    fun stopButtonEndsTheReplyAndKeepsWhatArrived() = runBlocking {
        val c = controller()
        slowStream = true
        sse = ev("chat.start", "") + ev("message.delta", "Partial answer") +
            (1..200).joinToString("") { ev("reasoning.delta", "thinking line number $it\\n") }
        coroutineScope {
            val job = async(Dispatchers.IO) { c.send("long task") }
            delay(800)
            c.stop()
            job.await()
        }
        slowStream = false
        val items = c.state.value.conversation.items
        val answer = items.last { it.role == "assistant" }
        assertTrue(answer.text.startsWith("Partial answer"))
        assertTrue(answer.text.contains("(stopped)"))
        assertTrue("no note for a user stop", items.none { it.role == "note" })
        assertTrue(!c.state.value.busy)

        sse = null
        chatBodies.clear()
        c.send("next")
        assertTrue(!chatBodies.single().contains("Partial answer"))
        assertEquals("done", c.state.value.conversation.items.last().text)
    }

    @Test
    fun tokenLimitWhileThinkingShowsRetry() = runBlocking {
        val c = controller()
        chatReply = """{"output":[{"type":"reasoning","content":"hmm"}],"response_id":"resp_9"}"""
        c.send("do it")
        assertEquals("", c.state.value.conversation.responseId.orEmpty())
        assertEquals("do it", c.state.value.conversation.items.last().retry)
        assertTrue(chatBodies.single().contains("\"max_output_tokens\":8192"))
    }

    @Test
    fun approveSendsExactlyOnePostAndRefreshesList() = runBlocking {
        val c = controller()
        c.pollApprovals()
        assertEquals(listOf("abcd1234"), c.state.value.pending.map { it.id })
        apLog.clear()

        // Two quick taps: only one POST may go out.
        listOf(async(Dispatchers.IO) { c.approve("abcd1234") }, async(Dispatchers.IO) { c.approve("abcd1234") }).awaitAll()

        val posts = apLog.filter { it.startsWith("POST") }
        assertEquals(listOf("POST /approvals/abcd1234/approve"), posts)
        val postAt = apLog.indexOf(posts.first())
        assertTrue("list refreshed after approving", apLog.drop(postAt + 1).any { it == "GET /approvals" })
        assertTrue(c.state.value.pending.isEmpty())
        // The model is told to retry with the confirm id.
        assertTrue(chatBodies.any { it.contains("confirm:\\\"abcd1234\\\"") })
    }

    @Test
    fun denySendsExactlyOnePostAndRefreshesList() = runBlocking {
        val c = controller()
        c.pollApprovals()
        apLog.clear()
        c.deny("abcd1234")
        assertEquals(listOf("POST /approvals/abcd1234/deny"), apLog.filter { it.startsWith("POST") })
        assertTrue(apLog.last() == "GET /approvals")
        assertTrue(c.state.value.pending.isEmpty())
    }

    @Test
    fun unauthorizedShowsTokenStateNotCrash() = runBlocking {
        lmCode = 401
        apCode = 401
        val c = controller()
        c.send("hello")
        assertEquals(Link.BAD_TOKEN, c.state.value.lm)
        assertEquals(Link.BAD_TOKEN, c.state.value.approvals)
        assertEquals("note", c.state.value.conversation.items.last().role)
        c.approve("abcd1234") // must not throw either
        assertEquals(Link.BAD_TOKEN, c.state.value.approvals)
    }

    @Test
    fun approvalLineInReplySurfacesPendingBeforePoll() = runBlocking {
        apCode = 500 // the poll after the reply fails, so pending can only come from the reply
        chatReply = """{"output":[
            {"type":"tool_call","tool":"pc","arguments":{"c":"run"},"output":"APPROVAL 0badf00d: run del C:\\tmp\\x. Waiting for the user to approve it in RemoteAI; tell them. Once approved, call again with the same args plus confirm:\"0badf00d\"."},
            {"type":"message","content":"Please approve 0badf00d in RemoteAI."}],"response_id":"resp_1"}"""
        val c = controller()
        c.send("delete the temp file")
        assertEquals(listOf("0badf00d"), c.state.value.pending.map { it.id })
        assertEquals("run del C:\\tmp\\x", c.state.value.pending.first().what)
        assertEquals("resp_1", c.state.value.conversation.responseId)
    }

    @Test
    fun followUpTurnsContinueTheConversation() = runBlocking {
        val c = controller()
        c.send("one")
        c.send("two")
        assertTrue(chatBodies[1].contains("\"previous_response_id\":\"resp_2\""))
        assertTrue(chatBodies[0].contains("mcp/contextsaver"))
        assertEquals("qwen3.6-27b", c.state.value.model)
    }

    @Test
    fun droppedStreamKeepsPartialAndTellsModelNextTime() = runBlocking {
        sse = listOf(
            "event: tool_call.success",
            """data: {"type":"tool_call.success","tool":"pc","arguments":{"c":"run","command":"gradle build"},"output":"APPROVAL de7d0534: run gradle build. Waiting for the user to approve it in RemoteAI."}""",
            "",
        ).joinToString("\n") + "\n" // no chat.end: the connection broke off
        val c = controller()
        c.send("build my app")
        val items = c.state.value.conversation.items
        assertTrue(items.any { it.role == "tool" && it.args.contains("gradle build") })
        assertTrue(items.any { it.role == "note" && it.text.contains("Connection dropped") })
        assertEquals(Link.OFFLINE, c.state.value.lm)

        sse = null
        chatBodies.clear()
        c.send("go on")
        val body = chatBodies.single()
        assertTrue(body.contains("build my app"))
        assertTrue(body.contains("gradle build"))
        assertTrue(body.contains("go on"))
        chatBodies.clear()
        c.send("again")
        assertTrue("context is sent once", !chatBodies.single().contains("build my app"))
    }

    @Test
    fun approvalMessageCarriesOriginalArgs() = runBlocking {
        chatReply = """{"output":[{"type":"tool_call","tool":"pc","arguments":{"command":"del x"},"output":"APPROVAL abcd1234: run del x. Waiting for the user to approve it in RemoteAI."}],"response_id":"resp_a"}"""
        val c = controller()
        c.send("delete x")
        chatBodies.clear()
        c.approve("abcd1234")
        val body = chatBodies.single()
        assertTrue(body.contains("confirm:\\\"abcd1234\\\""))
        assertTrue(body.contains("del x"))
    }

    private val waitingPending = """{"pending":[{"id":"abcd1234","what":"pc run: gradle build","status":"pending","waiting":true}]}"""

    private suspend fun approveWhileReplyIsOpen(c: ChatController) = coroutineScope {
        val reply = async(Dispatchers.IO) { c.send("build it") }
        while (!c.state.value.busy) delay(10)
        c.pollApprovals()
        assertTrue(c.state.value.pending.single().waiting)
        c.approve("abcd1234")
        reply.await()
    }

    @Test
    fun waitingApprovalResumesInPlaceWithoutFollowUp() = runBlocking {
        pending = waitingPending
        chatDelayMs = 600
        chatReply = """{"output":[{"type":"tool_call","tool":"pc","arguments":{"command":"gradle build"},"output":"BUILD SUCCESSFUL"},{"type":"message","content":"Built."}],"response_id":"resp_w"}"""
        val c = controller()
        approveWhileReplyIsOpen(c)
        assertEquals("only the original turn went out", 1, chatBodies.size)
        assertTrue(apLog.contains("POST /approvals/abcd1234/approve"))
    }

    @Test
    fun waitThatFellBackStillGetsTheFollowUp() = runBlocking {
        pending = waitingPending
        chatDelayMs = 600
        chatReply = """{"output":[{"type":"tool_call","tool":"pc","arguments":{"command":"gradle build"},"output":"APPROVAL abcd1234: run gradle build. Waiting for the user to approve it in RemoteAI; tell them. Once approved, call again with the same args plus confirm:\"abcd1234\"."}],"response_id":"resp_f"}"""
        val c = controller()
        approveWhileReplyIsOpen(c)
        assertEquals(2, chatBodies.size)
        assertTrue(chatBodies[1].contains("confirm:\\\"abcd1234\\\""))
        assertTrue(chatBodies[1].contains("gradle build"))
    }

    @Test
    fun startLocalAIHidesOutputOnceTheModelIsUp() = runBlocking {
        val ts = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"result":{"content":[{"type":"text","text":"LM Studio server on; qwen loaded"}]}}"""))
            enqueue(MockResponse().setBody("""{"result":{"content":[{"type":"text","text":"LM Studio failed to start"}]}}"""))
            start()
        }
        val c = ChatController(
            FakeSettings(Settings(host = "127.0.0.1", lmPort = lm.port, approvalPort = ap.port, tokenSaverPort = ts.port,
                lmToken = "lmtok", approvalToken = "aptok", tokenSaverToken = "tstok")),
            MemoryConversationStore(),
        )
        c.startLocalAI()
        assertEquals("", c.state.value.startOutput)

        lmCode = 503 // still down after starting: the output stays, so it can be copied
        c.startLocalAI()
        assertEquals("LM Studio failed to start", c.state.value.startOutput)
        c.dismissStartOutput()
        assertEquals("", c.state.value.startOutput)

        ts.enqueue(MockResponse().setBody("""{"result":{"content":[{"type":"text","text":"still loading"}]}}"""))
        c.startLocalAI()
        lmCode = 200 // Retry once the PC is up clears the old output
        assertTrue(c.refreshModels())
        assertEquals("", c.state.value.startOutput)
        ts.shutdown()
    }
}
