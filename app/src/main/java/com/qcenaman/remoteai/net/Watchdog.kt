package com.qcenaman.remoteai.net

enum class StopReason { USER, NO_PROGRESS, REPEATING, TOKEN_LIMIT }

/**
 * Stops a reply that only thinks: no tool call and no answer text for [limitMs] (time inside a tool call never
 * counts), or the same thinking line [repeats] times in one generation. limitMs <= 0 turns it off.
 */
class Watchdog(
    private val limitMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val repeats: Int = 6,
    private val minLine: Int = 15,
) {
    private val enabled = limitMs > 0
    private var since = now()
    private var inTool = 0
    private var looped = false
    private val line = StringBuilder()
    private val seen = HashMap<String, Int>()

    fun onEvent(type: String, content: String) {
        if (!enabled) return
        when (type) {
            "tool_call.start" -> inTool++
            "tool_call.success", "tool_call.failure" -> {
                if (inTool > 0) inTool--
                newGeneration()
            }
            "message.delta" -> if (content.isNotBlank()) since = now()
            "reasoning.delta" -> thinking(content)
        }
    }

    private fun newGeneration() {
        since = now()
        seen.clear()
        line.clear()
    }

    private fun thinking(delta: String) {
        for (ch in delta) {
            if (ch == '\n') endLine() else line.append(ch)
        }
    }

    private fun endLine() {
        val key = line.toString().replace(Regex("\\s+"), " ").trim()
        line.clear()
        if (key.length < minLine) return
        val n = (seen[key] ?: 0) + 1
        seen[key] = n
        if (n >= repeats) looped = true
    }

    /** Why the reply should stop now, or null to keep going. */
    fun verdict(): StopReason? = when {
        !enabled -> null
        looped -> StopReason.REPEATING
        inTool == 0 && now() - since >= limitMs -> StopReason.NO_PROGRESS
        else -> null
    }
}
