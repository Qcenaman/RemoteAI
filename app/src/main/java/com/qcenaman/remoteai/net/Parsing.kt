package com.qcenaman.remoteai.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

data class ToolCall(val tool: String, val arguments: String, val output: String)

data class ChatReply(
    val text: String,
    val reasoning: String,
    val toolCalls: List<ToolCall>,
    val responseId: String?,
    val approvals: List<Approval>,
)

/** waiting: a pc call is blocked on this approval and runs in place once approved (ContextSaver approval = "wait"). */
data class Approval(val id: String, val what: String, val expires: String = "", val waiting: Boolean = false)

private val approvalRe = Regex("""APPROVAL ([0-9a-fA-F]{8}): (.*?)\. Waiting for the user""", RegexOption.DOT_MATCHES_ALL)

/** Pending approvals that ContextSaver announced in a tool output or a reply. */
fun findApprovals(text: String): List<Approval> =
    approvalRe.findAll(text).map { Approval(it.groupValues[1].lowercase(), it.groupValues[2].trim()) }.toList()

private fun JsonElement?.str(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> contentOrNull ?: ""
    is JsonArray -> joinToString("") { part ->
        if (part is JsonObject) (part["text"] ?: part["content"]).str() else part.str()
    }
    is JsonObject -> (this["text"] ?: this["content"])?.str() ?: toString()
}

private fun JsonElement?.pretty(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> contentOrNull ?: ""
    else -> toString()
}

/** Parses a POST /api/v1/chat response. */
fun parseChat(body: String): ChatReply {
    val root = json.parseToJsonElement(body).jsonObject
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val calls = mutableListOf<ToolCall>()
    (root["output"] as? JsonArray)?.forEach { el ->
        val o = el as? JsonObject ?: return@forEach
        when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
            "message" -> text.appendSep(o["content"].str())
            "reasoning" -> reasoning.appendSep(o["content"].str())
            "tool_call" -> calls += ToolCall(
                tool = o["tool"].str(),
                arguments = o["arguments"].pretty(),
                output = o["output"].str(),
            )
        }
    }
    val approvals = (calls.flatMap { findApprovals(it.output) } + findApprovals(text.toString()))
        .distinctBy { it.id }
    return ChatReply(
        text = text.toString().trim(),
        reasoning = reasoning.toString().trim(),
        toolCalls = calls,
        responseId = (root["response_id"] as? JsonPrimitive)?.contentOrNull,
        approvals = approvals,
    )
}

private fun StringBuilder.appendSep(s: String) {
    if (s.isBlank()) return
    if (isNotEmpty()) append("\n\n")
    append(s)
}

/** Parses GET /approvals: {"pending":[...]} or {"pending":null}. */
fun parseApprovals(body: String): List<Approval> {
    val root = json.parseToJsonElement(body).jsonObject
    val arr = root["pending"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val status = o["status"].str()
        if (status.isNotEmpty() && status != "pending") return@mapNotNull null
        Approval(o["id"].str(), o["what"].str(), o["expires"].str(), o["waiting"].str() == "true")
    }
}

data class ModelInfo(val id: String, val loaded: Boolean)

/** Parses GET /api/v0/models, keeping chat models (llm/vlm). */
fun parseModels(body: String): List<ModelInfo> {
    val root = json.parseToJsonElement(body).jsonObject
    val arr = root["data"]?.jsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val type = o["type"].str()
        if (type.isNotEmpty() && type != "llm" && type != "vlm") return@mapNotNull null
        ModelInfo(o["id"].str(), o["state"].str() == "loaded")
    }
}

/** Text of a TokenSaver MCP tools/call result. */
fun parseMcpText(body: String): Pair<String, Boolean> {
    val root = json.parseToJsonElement(body).jsonObject
    (root["error"] as? JsonObject)?.let { return (it["message"].str()) to true }
    val res = root["result"] as? JsonObject ?: return body to true
    val text = (res["content"] as? JsonArray)?.joinToString("\n") { (it as? JsonObject)?.get("text").str() } ?: ""
    val isError = (res["isError"] as? JsonPrimitive)?.contentOrNull == "true"
    return text to isError
}

/** Collects a streamed POST /api/v1/chat (SSE), so a reply cut off mid-way still keeps what it had produced. */
class ChatStream(private val onEvent: (type: String, content: String) -> Unit = { _, _ -> }) {
    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val calls = mutableListOf<ToolCall>()
    private var event = ""
    private val data = StringBuilder()
    var started = false
        private set
    var result: ChatReply? = null
        private set
    var error: String? = null
        private set

    fun feedLine(line: String) {
        when {
            line.isEmpty() -> finish()
            line.startsWith(":") -> Unit
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").removePrefix(" "))
            }
        }
    }

    /** Dispatches the event collected so far (also called at the end of the stream). */
    fun finish() {
        if (data.isEmpty()) return
        val raw = data.toString()
        data.clear()
        val name = event
        event = ""
        val o = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        started = true
        val type = (o["type"] as? JsonPrimitive)?.contentOrNull ?: name
        onEvent(type, o["content"].str())
        when (type) {
            "message.start" -> if (text.isNotEmpty()) text.append("\n\n")
            "message.delta" -> text.append(o["content"].str())
            "reasoning.delta" -> reasoning.append(o["content"].str())
            "tool_call.success" -> calls += ToolCall(o["tool"].str(), o["arguments"].pretty(), o["output"].str())
            "tool_call.failure" -> {
                val meta = o["metadata"] as? JsonObject
                calls += ToolCall(meta?.get("tool_name").str(), meta?.get("arguments").pretty(), "Failed: " + o["reason"].str())
            }
            "error" -> error = (o["error"] as? JsonObject)?.get("message").str().ifBlank { raw }
            "chat.end" -> result = parseChat((o["result"] as? JsonObject)?.toString() ?: "{}")
        }
    }

    /** What arrived before the stream broke off. */
    fun partial(): ChatReply {
        val t = text.toString().trim()
        return ChatReply(
            text = t,
            reasoning = reasoning.toString().trim(),
            toolCalls = calls.toList(),
            responseId = null,
            approvals = (calls.flatMap { findApprovals(it.output) } + findApprovals(t)).distinctBy { it.id },
        )
    }
}
