package com.qcenaman.remoteai

import com.qcenaman.remoteai.ui.thinkingFileName
import com.qcenaman.remoteai.ui.thinkingFileText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ExportTest {
    private val date = Calendar.getInstance().apply { set(2026, 10, 1, 9, 30) }.time

    @Test
    fun fileNameStripsIllegalCharacters() {
        assertEquals("Thinking - Fix C a b - 2026-11-01 0930.txt", thinkingFileName("Fix C:\\a/b?", date))
    }

    @Test
    fun fileNameWithoutTitle() {
        assertEquals("Thinking - 2026-11-01 0930.txt", thinkingFileName("  ", date))
    }

    @Test
    fun fileTextHasTitleAndTrimmedThinking() {
        assertEquals("Chat\n\nstep one\nstep two\n", thinkingFileText("Chat", "\n step one\nstep two \n"))
    }

    @Test
    fun wholeChatCopiesAsText() {
        val items = listOf(
            com.qcenaman.remoteai.data.ChatItem("user", "hi", stats = com.qcenaman.remoteai.net.ReplyStats(promptTokens = 1)),
            com.qcenaman.remoteai.data.ChatItem("tool", "ok", tool = "pc", args = "{}"),
            com.qcenaman.remoteai.data.ChatItem("assistant", "Hello", reasoning = "think"),
            com.qcenaman.remoteai.data.ChatItem("note", "Stopped"),
        )
        org.junit.Assert.assertEquals(
            "Chat\n\nYou: hi\n(≈1 tokens)\n\nTool pc {}\nok\n\nAI: [Thinking]\nthink\n[/Thinking]\nHello\n\nNote: Stopped\n",
            conversationText("Chat", items),
        )
    }
}
