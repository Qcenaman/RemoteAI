package com.qcenaman.remoteai

import com.qcenaman.remoteai.net.StopReason
import com.qcenaman.remoteai.net.Watchdog
import com.qcenaman.remoteai.net.chatBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogTest {
    private var t = 0L
    private val min = 60_000L
    private fun dog() = Watchdog(3 * min, { t })

    @Test
    fun thinkingOnlyStopsAfterTheLimit() {
        val d = dog()
        repeat(17) { i -> t += 10_000; d.onEvent("reasoning.delta", "step $i of a long plan here\n"); assertNull(d.verdict()) }
        t = 3 * min
        assertEquals(StopReason.NO_PROGRESS, d.verdict())
    }

    @Test
    fun toolCallResetsAndTimeInsideItDoesNotCount() {
        val d = dog()
        t = 2 * min
        d.onEvent("tool_call.start", "")
        t = 20 * min // a long approval wait
        assertNull(d.verdict())
        d.onEvent("tool_call.success", "")
        t += 2 * min
        assertNull(d.verdict())
        t += min
        assertEquals(StopReason.NO_PROGRESS, d.verdict())
    }

    @Test
    fun answerTextResetsTheTimer() {
        val d = dog()
        t = 2 * min
        d.onEvent("message.delta", "Here is")
        t = 4 * min
        assertNull(d.verdict())
    }

    @Test
    fun sixRepeatedLinesStopFiveDoNot() {
        val d = dog()
        repeat(5) { d.onEvent("reasoning.delta", "  Search query:   \"AdbSaver\"\n") }
        assertNull(d.verdict())
        d.onEvent("reasoning.delta", "Search query: \"AdbSaver\"\n")
        assertEquals(StopReason.REPEATING, d.verdict())
    }

    @Test
    fun shortLinesAndNewGenerationsDoNotCount() {
        val d = dog()
        repeat(10) { d.onEvent("reasoning.delta", "Ok.\n") }
        repeat(5) { d.onEvent("reasoning.delta", "If nothing found, ask user.\n") }
        d.onEvent("tool_call.start", ""); d.onEvent("tool_call.success", "")
        repeat(5) { d.onEvent("reasoning.delta", "If nothing found, ask user.\n") }
        assertNull(d.verdict())
    }

    @Test
    fun offNeverStops() {
        val d = Watchdog(0, { t })
        repeat(20) { d.onEvent("reasoning.delta", "Search query: \"AdbSaver\"\n") }
        t = 60 * min
        assertNull(d.verdict())
    }

    @Test
    fun requestBodyCarriesMaxOutputTokensAndZeroOmitsIt() {
        assertTrue(chatBody("m", "hi", null, 8192).contains("\"max_output_tokens\":8192"))
        assertTrue(!chatBody("m", "hi", null, 0).contains("max_output_tokens"))
    }
}
