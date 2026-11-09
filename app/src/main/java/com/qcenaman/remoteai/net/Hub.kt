package com.qcenaman.remoteai.net

import com.qcenaman.remoteai.data.ChatItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A model of the desktop's ELAI hub (llama-server on port 8080): the ones with Cline and OpenCode shortcuts. */
data class HubPreset(val id: String, val label: String, val preset: String, val contextLength: Int, val loadMinutes: Int)

val HUB_PRESETS = listOf(
    HubPreset("coder", "Qwen3.8-27B Coder", "D:\\ELAI\\launchers\\coder.ini", 131072, 10),
    HubPreset("deepseek", "DeepSeek V4.1 Flash", "D:\\ELAI\\launchers\\deepseek-64k.ini", 65536, 60),
    HubPreset("ripple", "Ripple 65B Preview", "D:\\ELAI\\launchers\\ripple-64k.ini", 65536, 15),
)

const val HUB_STARTED = "Hub started"

/** Replaces whatever the hub is serving with this model's preset, the way its desktop shortcut does. */
fun hubStartScript(p: HubPreset): String = """
§ErrorActionPreference = 'SilentlyContinue'
Get-Process llama-server | Stop-Process -Force
Start-Sleep -Seconds 2
Get-Process cmd | Where-Object { §_.MainWindowTitle -like 'ELAI hub*' } | Stop-Process -Force
if (Get-Process llama-server) { 'Could not stop the running llama-server.'; exit 1 }
Start-Process cmd.exe -WorkingDirectory 'D:\ELAI\llama' -WindowStyle Minimized -ArgumentList '/c title ELAI hub (${p.label}) - close this window to stop the model & "D:\ELAI\llama\llama-server.exe" --models-preset "${p.preset}" --models-max 1 --host 0.0.0.0 --port 8080 --metrics || pause'
'$HUB_STARTED'
""".trimIndent().replace('§', '$')

fun hubStartCommand(p: HubPreset): String = encodedPowerShell(hubStartScript(p))

enum class HubState { LOADED, LOADING, UNLOADED, FAILED }

private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

/** GET /models of the hub: model id -> state. */
fun parseHubStates(body: String): Map<String, HubState> {
    val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyMap()
    val arr = root["data"] as? JsonArray ?: return emptyMap()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val status = o["status"] as? JsonObject
        val value = status?.get("value").text()
        val state = when {
            status?.get("failed").text() == "true" || value.contains("fail") || value.contains("error") -> HubState.FAILED
            value == "loaded" -> HubState.LOADED
            value == "loading" -> HubState.LOADING
            else -> HubState.UNLOADED
        }
        o["id"].text().takeIf { it.isNotBlank() }?.let { it to state }
    }.toMap()
}

/**
 * The hub keeps no conversation, so each request carries it: the newest turns that fit in 3/4 of the context.
 * Same-role neighbours are merged because many chat templates insist on alternating roles.
 */
fun hubMessages(history: List<ChatItem>, input: String, contextLength: Int): JsonArray {
    val budget = if (contextLength > 0) contextLength * 3 / 4 else Int.MAX_VALUE
    var used = estimateTokens(input)
    val kept = ArrayDeque<Pair<String, String>>()
    for (item in history.asReversed()) {
        if ((item.role != "user" && item.role != "assistant") || item.text.isBlank()) continue
        val cost = estimateTokens(item.text)
        if (used + cost > budget) break
        used += cost
        kept.addFirst(item.role to item.text)
    }
    while (kept.firstOrNull()?.first == "assistant") kept.removeFirst()
    kept.addLast("user" to input)
    val merged = mutableListOf<Pair<String, String>>()
    kept.forEach { turn ->
        val last = merged.lastOrNull()
        if (last?.first == turn.first) merged[merged.size - 1] = last.first to last.second + "\n\n" + turn.second
        else merged += turn
    }
    return buildJsonArray {
        merged.forEach { (role, content) ->
            add(buildJsonObject {
                put("role", role)
                put("content", content)
            })
        }
    }
}

/** Collects a streamed POST /v1/chat/completions; a reply cut off mid-way keeps what it had produced. */
class HubStream(
    private val clock: () -> Long = System::currentTimeMillis,
    private val onEvent: (type: String, content: String) -> Unit = { _, _ -> },
) {
    private val startedAt = clock()
    private var firstTokenAt = 0L
    private var deltas = 0
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private var inputTokens = 0
    private var outputTokens = 0
    private var tokensPerSecond = 0.0
    var started = false
        private set
    var done = false
        private set
    var error: String? = null
        private set

    fun feedLine(line: String) {
        if (!line.startsWith("data:")) return
        val raw = line.removePrefix("data:").trim()
        if (raw == "[DONE]") {
            done = true
            return
        }
        val o = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        started = true
        (o["error"] as? JsonObject)?.let { error = it["message"].text().ifBlank { raw } }
        (o["usage"] as? JsonObject)?.let { u ->
            inputTokens = u["prompt_tokens"].intOrNull() ?: inputTokens
            outputTokens = u["completion_tokens"].intOrNull() ?: outputTokens
        }
        ((o["timings"] as? JsonObject)?.get("predicted_per_second") as? JsonPrimitive)?.doubleOrNull?.let { tokensPerSecond = it }
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        val delta = choice["delta"] as? JsonObject ?: return
        delta["reasoning_content"].text().takeIf { it.isNotEmpty() }?.let { add("reasoning.delta", it, reasoning) }
        delta["content"].text().takeIf { it.isNotEmpty() }?.let { add("message.delta", it, text) }
    }

    private fun add(type: String, content: String, to: StringBuilder) {
        if (deltas++ == 0) firstTokenAt = clock()
        to.append(content)
        onEvent(type, content)
    }

    /** The reply so far; final once done is true. */
    fun reply(): ChatReply {
        val genSecs = if (firstTokenAt > 0) (clock() - firstTokenAt) / 1000.0 else 0.0
        return ChatReply(
            text = text.toString().trim(),
            reasoning = reasoning.toString().trim(),
            toolCalls = emptyList(),
            responseId = null,
            approvals = emptyList(),
            stats = ReplyStats(
                inputTokens = inputTokens,
                outputTokens = outputTokens.takeIf { it > 0 } ?: deltas,
                tokensPerSecond = tokensPerSecond.takeIf { it > 0 } ?: if (genSecs > 0.2) deltas / genSecs else 0.0,
                firstTokenSeconds = if (firstTokenAt > 0) (firstTokenAt - startedAt) / 1000.0 else 0.0,
                seconds = (clock() - startedAt) / 1000.0,
                estimated = outputTokens == 0,
            ).takeIf { deltas > 0 },
        )
    }
}

private val hubJson = "application/json".toMediaType()

/** The ELAI hub's llama-server router: OpenAI-style chat plus its model list. No token; it is reached over Tailscale. */
class HubClient(
    private val base: () -> String,
    // Big models paging from disk can take many minutes to reach the first token.
    http: OkHttpClient = defaultHttp(3600),
    private val quick: OkHttpClient = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build(),
) : HttpBase("Model hub", http) {

    /** Model id -> state; throws when the hub isn't running. Never retries, so a refresh stays quick. */
    suspend fun states(): Map<String, HubState> = withContext(Dispatchers.IO) {
        try {
            quick.newCall(Request.Builder().url(base() + "/models").get().build()).execute().use { r ->
                checkStatus(r)
                parseHubStates(r.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw ApiException.Network(server, e)
        } catch (e: IllegalArgumentException) {
            emptyMap()
        }
    }

    /** Frees the GPU; the hub keeps running. */
    suspend fun unload(id: String) {
        val body = buildJsonObject { put("model", id) }.toString()
        call(Request.Builder().url(base() + "/models/unload").post(body.toRequestBody(hubJson)).build())
    }

    @Volatile private var current: okhttp3.Call? = null
    @Volatile private var userStop = false

    fun cancel() {
        userStop = true
        current?.cancel()
    }

    suspend fun chat(model: String, messages: JsonArray, maxTokens: Int = 0, watchdog: Watchdog? = null): ChatReply {
        val body = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            if (maxTokens > 0) put("max_tokens", maxTokens)
        }.toString()
        val req = Request.Builder().url(base() + "/v1/chat/completions").post(body.toRequestBody(hubJson)).build()
        val stream = HubStream(onEvent = { type, content -> watchdog?.onEvent(type, content) })
        userStop = false
        return try {
            withConnectRetry {
                if (userStop) throw StopSignal(StopReason.USER)
                val c = http.newCall(req)
                current = c
                if (userStop) c.cancel()
                try {
                    c.execute().use { r ->
                        checkStatus(r)
                        val src = r.body?.source() ?: throw IOException("empty reply")
                        while (!stream.done) {
                            val line = src.readUtf8Line() ?: break
                            stream.feedLine(line)
                            stream.error?.let { throw ApiException.Http(server, 200, it) }
                            watchdog?.verdict()?.let { throw StopSignal(it) }
                        }
                        if (!stream.done) throw IOException("stream ended early")
                        stream.reply()
                    }
                } catch (e: IOException) {
                    if (userStop && e !is StopSignal) throw StopSignal(StopReason.USER)
                    throw e
                } finally {
                    current = null
                }
            }
        } catch (e: StopSignal) {
            throw ApiException.Stopped(e.reason, stream.reply())
        } catch (e: IOException) {
            throw ApiException.Network(server, e, stream.reply().takeIf { stream.started })
        }
    }
}
