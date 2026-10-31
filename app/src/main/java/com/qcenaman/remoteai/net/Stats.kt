package com.qcenaman.remoteai.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.util.Locale

/**
 * Numbers shown under a message.
 * On a reply: what LM Studio reported (or, when the stream broke off, what was counted here: estimated = true).
 * On a prompt: promptTokens is an estimate from its length; inputTokens is the context the model read for it.
 */
@Serializable
data class ReplyStats(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val firstTokenSeconds: Double = 0.0,
    val seconds: Double = 0.0,
    val contextLength: Int = 0,
    val promptTokens: Int = 0,
    val estimated: Boolean = false,
) {
    /** Tokens in the conversation's context once this reply is done. */
    val contextUsed: Int get() = inputTokens + outputTokens
}

private fun JsonObject.num(vararg keys: String): Double? =
    keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.doubleOrNull }

/** Reads the "stats" (or OpenAI-style "usage") object of a /api/v1/chat result. */
fun parseStats(root: JsonObject): ReplyStats? {
    val s = (root["stats"] as? JsonObject) ?: (root["usage"] as? JsonObject) ?: return null
    val input = s.num("input_tokens", "prompt_tokens")
    val output = s.num("total_output_tokens", "output_tokens", "completion_tokens")
    val tps = s.num("tokens_per_second")
    if (input == null && output == null && tps == null) return null
    return ReplyStats(
        inputTokens = input?.toInt() ?: 0,
        outputTokens = output?.toInt() ?: 0,
        reasoningTokens = s.num("reasoning_output_tokens", "reasoning_tokens")?.toInt() ?: 0,
        tokensPerSecond = tps ?: 0.0,
        firstTokenSeconds = s.num("time_to_first_token_seconds", "time_to_first_token") ?: 0.0,
    )
}

/** Rough token count of text the user typed (about 4 characters per token). */
fun estimateTokens(text: String): Int = if (text.isBlank()) 0 else (text.trim().length + 3) / 4

private fun count(n: Int): String = when {
    n >= 10_000 -> String.format(Locale.US, "%.0fk", n / 1000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fk", n / 1000.0)
    else -> n.toString()
}

private fun secs(s: Double): String =
    if (s >= 60) String.format(Locale.US, "%dm %02ds", (s / 60).toInt(), (s % 60).toInt())
    else String.format(Locale.US, "%.1f s", s)

private fun context(used: Int, length: Int): String? = when {
    used <= 0 -> null
    length <= 0 -> "context ${count(used)}"
    else -> "context ${count(used)} / ${count(length)} (${(used * 100L / length).coerceAtMost(999)}%)"
}

/** The line under a reply, e.g. "42.1 tok/s · 812 tokens (300 thinking) · 0.6 s to first · 19.3 s · context 9.8k / 32k (30%)". */
fun replyLine(s: ReplyStats): String {
    val approx = if (s.estimated) "≈" else ""
    val parts = mutableListOf<String>()
    if (s.tokensPerSecond > 0) parts += approx + String.format(Locale.US, "%.1f tok/s", s.tokensPerSecond)
    if (s.outputTokens > 0) {
        parts += approx + "${count(s.outputTokens)} tokens" +
            if (s.reasoningTokens > 0) " (${count(s.reasoningTokens)} thinking)" else ""
    }
    if (s.firstTokenSeconds > 0) parts += secs(s.firstTokenSeconds) + " to first"
    if (s.seconds > 0) parts += secs(s.seconds)
    context(s.contextUsed, s.contextLength)?.let { parts += it }
    return parts.joinToString(" · ")
}

/** The line under a prompt, e.g. "≈120 tokens · context 9.1k / 32k (28%)". */
fun promptLine(s: ReplyStats): String {
    val parts = mutableListOf<String>()
    if (s.promptTokens > 0) parts += "≈${count(s.promptTokens)} tokens"
    context(s.inputTokens, s.contextLength)?.let { parts += it }
    return parts.joinToString(" · ")
}

internal fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.doubleOrNull?.toInt()
