package com.qcenaman.remoteai

import com.qcenaman.remoteai.ui.MdBlock
import com.qcenaman.remoteai.ui.MdRun
import com.qcenaman.remoteai.ui.parseInline
import com.qcenaman.remoteai.ui.parseMarkdown
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownTest {
    @Test
    fun blocksFromATypicalReply() {
        val b = parseMarkdown(
            """
            Here is an overview:

            **Summary:**
            The folder has 21 files.

            ## Subfolders
            * **TokenSaver**: ~4.5 GB
              * data
            1. First
            > note
            ---
            ```python
            print("hi")
            ```
            | Name | Size |
            |---|---|
            | a | 1 |
            """.trimIndent()
        )
        assertEquals(
            listOf(
                MdBlock.Paragraph("Here is an overview:"),
                MdBlock.Paragraph("**Summary:**\nThe folder has 21 files."),
                MdBlock.Heading(2, "Subfolders"),
                MdBlock.ListItem(0, "•", "**TokenSaver**: ~4.5 GB"),
                MdBlock.ListItem(1, "•", "data"),
                MdBlock.ListItem(0, "1.", "First"),
                MdBlock.Quote("note"),
                MdBlock.Rule,
                MdBlock.Code("python", "print(\"hi\")"),
                MdBlock.Table(listOf("Name", "Size"), listOf(listOf("a", "1"))),
            ),
            b,
        )
    }

    @Test
    fun unclosedFenceWhileStreamingKeepsCode() {
        assertEquals(listOf(MdBlock.Code("", "x = 1")), parseMarkdown("```\nx = 1"))
    }

    @Test
    fun inlineStyles() {
        assertEquals(
            listOf(
                MdRun("a "),
                MdRun("bold", bold = true),
                MdRun(" "),
                MdRun("it", italic = true),
                MdRun(" "),
                MdRun("x_y", code = true),
                MdRun(" "),
                MdRun("site", url = "https://x.io"),
            ),
            parseInline("a **bold** *it* `x_y` [site](https://x.io)"),
        )
    }

    @Test
    fun unpairedMarkersAndSnakeCaseStayPlain() {
        assertEquals(listOf(MdRun("2 * 3 = 6 and my_file_name")), parseInline("2 * 3 = 6 and my_file_name"))
        assertEquals(listOf(MdRun("**half")), parseInline("**half"))
    }

    @Test
    fun nestedBoldItalic() {
        assertEquals(
            listOf(MdRun("bold ", bold = true), MdRun("it", bold = true, italic = true)),
            parseInline("**bold _it_**"),
        )
    }
}
