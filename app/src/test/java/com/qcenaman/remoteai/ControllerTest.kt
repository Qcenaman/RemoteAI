package com.qcenaman.remoteai

import com.qcenaman.remoteai.data.MemoryConversationStore
import com.qcenaman.remoteai.data.Settings
import com.qcenaman.remoteai.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
                            MockResponse().setBody(chatReply)
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

    private fun controller(lmToken: String = "lmtok", apToken: String = "aptok") = ChatController(
        FakeSettings(Settings(host = "127.0.0.1", lmPort = lm.port, approvalPort = ap.port, lmToken = lmToken, approvalToken = apToken)),
        MemoryConversationStore(),
    )

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
}
