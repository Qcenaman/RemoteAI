package com.qcenaman.remoteai.net

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

val OPENCODE_PASSWORD = Regex("""OCPW:(\S+)""")

/**
 * Makes the PC's OpenCode background service reachable on the tailnet only, starting it in the user's own
 * session if needed, and prints its password. Re-run because the service can change port when it restarts.
 */
fun openCodeLinkScript(port: Int): String = """
§ErrorActionPreference = 'SilentlyContinue'
§f = 'C:\Users\Afonsinho\.local\state\opencode\service.json'
function Get-Service-State { §s = Get-Content §f -Raw | ConvertFrom-Json; if (§s -and §s.pid -and (Get-Process -Id §s.pid)) { §s } }
§s = Get-Service-State
if (-not §s) {
  schtasks /Create /F /TN RemoteAI-OpenCode /SC ONCE /ST 00:00 /RU Afonsinho /IT /TR '"D:\ELAI\opencode\opencode.exe" service start' 2>&1 | Out-Null
  schtasks /Run /TN RemoteAI-OpenCode 2>&1 | Out-Null
  foreach (§i in 1..20) { Start-Sleep -Seconds 1; §s = Get-Service-State; if (§s) { break } }
  schtasks /Delete /F /TN RemoteAI-OpenCode 2>&1 | Out-Null
}
if (-not §s) { 'OPENCODE_DOWN'; exit 1 }
tailscale serve --bg --tcp $port ('tcp://127.0.0.1:' + ([uri]§s.url).Port) 2>&1 | Out-Null
'OCPW:' + §s.password
""".trimIndent().replace('§', '$')

fun openCodeLinkCommand(port: Int): String = encodedPowerShell(openCodeLinkScript(port))

private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun dataOf(body: String): JsonElement? =
    (json.parseToJsonElement(body) as? JsonObject)?.get("data")

fun parseOpenCodeRunning(body: String, sessionId: String): Boolean =
    (dataOf(body) as? JsonObject)?.containsKey(sessionId) == true

fun parseOpenCodeMessageIds(body: String): Set<String> =
    (dataOf(body) as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("id").text().ifBlank { null } }.toSet()

/** GET .../permission: what OpenCode is waiting on the user for. */
fun parseOpenCodePermissions(body: String): List<Approval> =
    (dataOf(body) as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o["id"].text().ifBlank { return@mapNotNull null }
        val resources = (o["resources"] as? JsonArray).orEmpty().joinToString(", ") { it.text() }
        val what = o["message"].text().ifBlank { listOf(o["action"].text(), resources).filter { it.isNotBlank() }.joinToString(": ") }
        Approval(id, what, waiting = true)
    }

private fun toolOutput(state: JsonObject?): String {
    val parts = when (val c = state?.get("content")) {
        is JsonArray -> c.toList()
        null -> emptyList()
        else -> listOf(c)
    }
    val text = parts.joinToString("\n") { (it as? JsonObject)?.get("text").text() }
    if (state?.get("status").text() == "completed") return text
    val error = state?.get("error").let { if (it is JsonObject) it["message"].text() else it.text() }
    return "Failed: " + error.ifBlank { text.ifBlank { state?.get("status").text() } }
}

/** What the agent did since the messages in known: GET .../message is newest first. */
fun parseOpenCodeTurn(body: String, known: Set<String>): ChatReply {
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val calls = mutableListOf<ToolCall>()
    (dataOf(body) as? JsonArray).orEmpty().asReversed().forEach { el ->
        val m = el as? JsonObject ?: return@forEach
        if (m["type"].text() != "assistant" || m["id"].text() in known) return@forEach
        (m["content"] as? JsonArray).orEmpty().forEach { c ->
            val o = c as? JsonObject ?: return@forEach
            when (o["type"].text()) {
                "text" -> text.addPart(o["text"].text())
                "reasoning" -> reasoning.addPart(o["text"].text())
                "tool" -> {
                    val state = o["state"] as? JsonObject
                    calls += ToolCall(o["name"].text(), state?.get("input")?.toString().orEmpty(), toolOutput(state))
                }
            }
        }
    }
    return ChatReply(text.toString().trim(), reasoning.toString().trim(), calls, null, emptyList())
}

private fun StringBuilder.addPart(s: String) {
    if (s.isBlank()) return
    if (isNotEmpty()) append("\n\n")
    append(s)
}

private val ocJson = "application/json".toMediaType()

/** The PC's OpenCode service: it runs the model and its tools; the phone only sends prompts and reads what happened. */
class OpenCodeClient(
    private val base: () -> String,
    private val password: () -> String,
    http: OkHttpClient = defaultHttp(60),
    private val pollMs: Long = 2_000,
    /** How long a prompt may take to show up as running before it counts as already finished. */
    private val graceMs: Long = 30_000,
    /** How long the phone may lose the PC during a run before giving up on following it. */
    private val maxOutageMs: Long = 15 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : HttpBase("OpenCode", http) {
    private fun req(path: String) = Request.Builder().url(base() + path)
        .header("Authorization", Credentials.basic("opencode", password()))

    private suspend fun post(path: String, body: String = "{}") = call(req(path).post(body.toRequestBody(ocJson)).build())

    suspend fun createSession(agent: String, modelId: String, directory: String): String {
        val body = buildJsonObject {
            put("agent", agent)
            putJsonObject("model") {
                put("id", modelId)
                put("providerID", "elai")
            }
            putJsonObject("location") { put("directory", directory) }
        }.toString()
        val id = (dataOf(post("/api/session", body)) as? JsonObject)?.get("id").text()
        if (id.isBlank()) throw ApiException.Http(server, 200, "no session id")
        return id
    }

    suspend fun switchModel(session: String, agent: String, modelId: String) {
        post("/api/session/$session/agent", buildJsonObject { put("agent", agent) }.toString())
        post("/api/session/$session/model", buildJsonObject {
            putJsonObject("model") {
                put("id", modelId)
                put("providerID", "elai")
            }
        }.toString())
    }

    suspend fun reply(session: String, requestId: String, allow: Boolean) {
        post("/api/session/$session/permission/$requestId/reply",
            buildJsonObject { put("decision", if (allow) "once" else "reject") }.toString())
    }

    private suspend fun messages(session: String) = call(req("/api/session/$session/message?limit=200").get().build())

    /** Newest first. */
    suspend fun sessions(limit: Int = 15): List<OcSession> = parseOpenCodeSessions(call(req("/api/session?limit=$limit").get().build()))

    suspend fun active(): Set<String> = parseOpenCodeActive(call(req("/api/session/active").get().build()))

    /** The whole chat, oldest first, as the PC's window shows it. */
    suspend fun transcript(session: String) = parseOpenCodeTranscript(messages(session))

    suspend fun permissions(session: String): List<Approval> =
        parseOpenCodePermissions(call(req("/api/session/$session/permission").get().build()))

    /** Like typing it in the PC's window: if the model is busy, OpenCode queues it for the next turn. */
    suspend fun prompt(session: String, text: String) {
        post("/api/session/$session/prompt", buildJsonObject { put("text", text) }.toString())
    }

    suspend fun interrupt(session: String) {
        post("/api/session/$session/interrupt")
    }

    @Volatile private var userStop = false

    fun cancel() {
        userStop = true
    }

    /** Polls instead of streaming, so losing the phone's connection mid-run loses nothing: the run lives on the PC. */
    suspend fun send(session: String, text: String, onApprovals: (List<Approval>) -> Unit): ChatReply {
        userStop = false
        val known = parseOpenCodeMessageIds(messages(session))
        post("/api/session/$session/prompt", buildJsonObject { put("text", text) }.toString())
        val started = clock()
        var lastOk = started
        var seen = false
        while (true) {
            if (userStop) {
                try { post("/api/session/$session/interrupt") } catch (e: ApiException) { /* it may have just ended */ }
                val partial = try {
                    parseOpenCodeTurn(messages(session), known)
                } catch (e: ApiException) {
                    ChatReply("", "", emptyList(), null, emptyList())
                }
                throw ApiException.Stopped(StopReason.USER, partial)
            }
            try {
                val running = parseOpenCodeRunning(call(req("/api/session/active").get().build()), session)
                onApprovals(parseOpenCodePermissions(call(req("/api/session/$session/permission").get().build())))
                lastOk = clock()
                if (running) seen = true
                else if (seen || clock() - started > graceMs) break
            } catch (e: ApiException.Network) {
                if (clock() - lastOk > maxOutageMs) throw e
            }
            delay(pollMs)
        }
        onApprovals(emptyList())
        return parseOpenCodeTurn(messages(session), known).copy(stats = ReplyStats(seconds = (clock() - started) / 1000.0))
    }
}
