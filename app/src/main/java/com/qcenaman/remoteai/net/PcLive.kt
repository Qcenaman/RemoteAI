package com.qcenaman.remoteai.net

import com.qcenaman.remoteai.data.ChatItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** The PCs whose OpenCode window the phone can follow: each runs its Qwen model with OpenCode. */
enum class Pc(val key: String, val label: String, val model: String) {
    DESKTOP("desktop", "Desktop", "Qwen3.8-27B Coder"),
    LAPTOP("laptop", "Laptop", "Qwen3.6-35B-A3B"),
    ;

    companion object {
        fun of(key: String): Pc? = entries.firstOrNull { it.key == key }
    }
}

/** The desktop's Qwen model in the ELAI hub. */
const val DESKTOP_QWEN = "coder"

/**
 * Starts the laptop's model (if it isn't up) and its OpenCode server in the last project folder, the same way the
 * laptop's "ELAI OpenCode" shortcut does, then prints the server's password. Never set to start at boot.
 */
fun laptopStartCommand(gpu: String = "ask"): String =
    "\"\$HOME/ELAI/bin/elai-remote\" start $gpu 2>&1; echo \"OCPW:\$(cat \"\$HOME/ELAI/remote/password\")\""

const val LAPTOP_PASSWORD_COMMAND = "echo \"OCPW:\$(cat \"\$HOME/ELAI/remote/password\")\""

/** Stops OpenCode's server and the model on the laptop (what its shortcut does at exit). */
const val LAPTOP_STOP_COMMAND = "\"\$HOME/ELAI/bin/elai-remote\" stop 2>&1"

data class OcSession(val id: String, val title: String, val directory: String, val updated: Long, val busy: Boolean)

private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonElement?.num(): Long = (this as? JsonPrimitive)?.longOrNull ?: 0L

private fun dataOf(body: String): JsonElement? =
    (json.parseToJsonElement(body) as? JsonObject)?.get("data")

/** GET /api/session: newest first. A session is busy when its last busy time is after its last idle time. */
fun parseOpenCodeSessions(body: String): List<OcSession> =
    (dataOf(body) as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o["id"].str().ifBlank { return@mapNotNull null }
        val t = o["time"] as? JsonObject
        val busy = t?.get("busy").num()
        val idle = t?.get("idle").num()
        val updated = maxOf(t?.get("updated").num(), busy, idle, t?.get("viewed").num())
        val dir = (o["location"] as? JsonObject)?.get("directory").str().ifBlank { o["directory"].str() }
        OcSession(id, o["title"].str(), dir, updated, busy > idle)
    }

fun parseOpenCodeActive(body: String): Set<String> = (dataOf(body) as? JsonObject)?.keys.orEmpty()

/** The chat the PC's window is on: one that is working now, else the one used last. */
fun pickWindowSession(sessions: List<OcSession>, active: Set<String>): OcSession? =
    sessions.filter { it.id in active || it.busy }.maxByOrNull { it.updated }
        ?: sessions.maxByOrNull { it.updated }

private fun toolText(state: JsonObject?): String {
    val parts = when (val c = state?.get("content")) {
        is JsonArray -> c.toList()
        null -> emptyList()
        else -> listOf(c)
    }
    val text = parts.joinToString("\n") { (it as? JsonObject)?.get("text").str() }.trim()
    return when (val status = state?.get("status").str()) {
        "completed", "" -> text
        "running", "pending" -> text.ifBlank { "($status…)" }
        else -> {
            val error = state?.get("error").let { if (it is JsonObject) it["message"].str() else it.str() }
            "Failed: " + error.ifBlank { text.ifBlank { status } }
        }
    }
}

/** GET .../message (newest first): the whole chat as the PC's window shows it, oldest first. */
fun parseOpenCodeTranscript(body: String): List<ChatItem> {
    val out = mutableListOf<ChatItem>()
    (dataOf(body) as? JsonArray).orEmpty().asReversed().forEach { el ->
        val m = el as? JsonObject ?: return@forEach
        when (m["type"].str()) {
            "user" -> {
                val text = m["text"].str().ifBlank {
                    (m["content"] as? JsonArray).orEmpty().joinToString("\n") { (it as? JsonObject)?.get("text").str() }
                }
                if (text.isNotBlank()) out += ChatItem("user", text.trim())
            }
            "assistant" -> {
                val text = StringBuilder()
                val reasoning = StringBuilder()
                fun flush() {
                    if (text.isNotBlank() || reasoning.isNotBlank()) {
                        out += ChatItem("assistant", text.toString().trim(), reasoning = reasoning.toString().trim())
                    }
                    text.clear(); reasoning.clear()
                }
                (m["content"] as? JsonArray).orEmpty().forEach { c ->
                    val o = c as? JsonObject ?: return@forEach
                    when (o["type"].str()) {
                        "text" -> text.addPart(o["text"].str())
                        "reasoning" -> reasoning.addPart(o["text"].str())
                        "tool" -> {
                            flush() // keep the window's order: what it said, then the tool it ran
                            val state = o["state"] as? JsonObject
                            out += ChatItem("tool", toolText(state), tool = o["name"].str(), args = state?.get("input")?.toString().orEmpty())
                        }
                    }
                }
                flush()
            }
        }
    }
    return out
}

private fun StringBuilder.addPart(s: String) {
    if (s.isBlank()) return
    if (isNotEmpty()) append("\n\n")
    append(s)
}
