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

/** Plain-text body written to the exported file. */
fun thinkingFileText(title: String, text: String): String =
    (if (title.isBlank()) "" else "$title\n\n") + text.trim() + "\n"
