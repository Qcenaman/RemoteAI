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
}
