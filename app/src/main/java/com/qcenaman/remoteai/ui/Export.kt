package com.qcenaman.remoteai.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Safe .txt file name for exported thinking, e.g. "Thinking - My chat - 2026-11-01 0930.txt". */
fun thinkingFileName(title: String, now: Date = Date()): String {
    val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
        .replace(Regex("\\s+"), " ").trim().take(60).trim()
    val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(now)
    return if (clean.isEmpty()) "Thinking - $stamp.txt" else "Thinking - $clean - $stamp.txt"
}

/** The whole chat as plain text, for the copy-chat button. */
fun conversationText(title: String, items: List<com.qcenaman.remoteai.data.ChatItem>): String = buildString {
    if (title.isNotBlank()) append(title).append("\n\n")
    items.forEach { i ->
        when (i.role) {
            "user" -> append("You: ").append(i.text.trim())
            "assistant" -> {
                append("AI: ")
                if (i.reasoning.isNotBlank()) append("[Thinking]\n").append(i.reasoning.trim()).append("\n[/Thinking]\n")
                append(i.text.trim())
            }
            "tool" -> append("Tool ").append(i.tool).append(" ").append(i.args).append("\n").append(i.text.trim())
            else -> append("Note: ").append(i.text.trim())
        }
        i.stats?.let { s ->
            val line = if (i.role == "user") com.qcenaman.remoteai.net.promptLine(s) else com.qcenaman.remoteai.net.replyLine(s)
            if (line.isNotBlank()) append("\n(").append(line).append(")")
        }
        append("\n\n")
    }
}.trim() + "\n"

/** Plain-text body written to the exported file. */
fun thinkingFileText(title: String, text: String): String =
    (if (title.isBlank()) "" else "$title\n\n") + text.trim() + "\n"
