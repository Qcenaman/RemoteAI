package com.qcenaman.remoteai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

// ---- Parsing (pure Kotlin, unit-tested) ----

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class ListItem(val depth: Int, val marker: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    data object Rule : MdBlock()
}

private val headingRe = Regex("""^(#{1,6})\s+(.*?)\s*#*\s*$""")
private val ruleRe = Regex("""^\s{0,3}([-*_])(\s*\1){2,}\s*$""")
private val bulletRe = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val numberedRe = Regex("""^(\s*)(\d{1,9})[.)]\s+(.*)$""")
private val quoteRe = Regex("""^\s{0,3}>\s?(.*)$""")
private val fenceRe = Regex("""^\s{0,3}(```+|~~~+)\s*([\w+#.-]*).*$""")
private val tableSepRe = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

private fun tableCells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split("\n")
    val out = mutableListOf<MdBlock>()
    val para = mutableListOf<String>()
    val quote = mutableListOf<String>()
    fun flush() {
        if (para.isNotEmpty()) { out += MdBlock.Paragraph(para.joinToString("\n")); para.clear() }
        if (quote.isNotEmpty()) { out += MdBlock.Quote(quote.joinToString("\n")); quote.clear() }
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val fence = fenceRe.matchEntire(line)
        if (fence != null) {
            flush()
            val mark = fence.groupValues[1]
            val body = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(mark)) { body += lines[i]; i++ }
            out += MdBlock.Code(fence.groupValues[2], body.joinToString("\n"))
            i++ // closing fence (or end of a still-streaming reply)
            continue
        }
        if (line.isBlank()) { flush(); i++; continue }
        if (line.trimStart().startsWith("|") && i + 1 < lines.size && tableSepRe.matches(lines[i + 1])) {
            flush()
            val header = tableCells(line)
            val rows = mutableListOf<List<String>>()
            i += 2
            while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += tableCells(lines[i]); i++ }
            out += MdBlock.Table(header, rows)
            continue
        }
        val h = headingRe.matchEntire(line)
        val q = quoteRe.matchEntire(line)
        val b = bulletRe.matchEntire(line)
        val n = numberedRe.matchEntire(line)
        when {
            h != null -> { flush(); out += MdBlock.Heading(h.groupValues[1].length, h.groupValues[2]) }
            ruleRe.matches(line) -> { flush(); out += MdBlock.Rule }
            q != null -> { if (para.isNotEmpty()) flush(); quote += q.groupValues[1] }
            b != null -> { flush(); out += MdBlock.ListItem(depth(b.groupValues[1]), "•", b.groupValues[2]) }
            n != null -> { flush(); out += MdBlock.ListItem(depth(n.groupValues[1]), n.groupValues[2] + ".", n.groupValues[3]) }
            quote.isNotEmpty() -> quote += line.trim() // lazy continuation of a quote
            out.lastOrNull() is MdBlock.ListItem && para.isEmpty() && line.startsWith(" ") -> {
                val last = out.removeAt(out.size - 1) as MdBlock.ListItem
                out += last.copy(text = last.text + "\n" + line.trim())
            }
            else -> para += line
        }
        i++
    }
    flush()
    return out
}

private fun depth(indent: String): Int = indent.replace("\t", "    ").length / 2

/** A run of inline text with its styles. */
data class MdRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

private enum class Kind { CODE, BOLD, STRIKE, ITALIC, LINK }

private val inlinePatterns = listOf(
    Kind.CODE to Regex("""`([^`\n]+)`"""),
    Kind.LINK to Regex("""\[([^\]\n]+)]\((\S+?)\)"""),
    Kind.BOLD to Regex("""\*\*(?=\S)(.+?)(?<=\S)\*\*"""),
    Kind.BOLD to Regex("""(?<![\w])__(?=\S)(.+?)(?<=\S)__(?![\w])"""),
    Kind.STRIKE to Regex("""~~(?=\S)(.+?)(?<=\S)~~"""),
    Kind.ITALIC to Regex("""(?<![*\w])\*(?=[^\s*])(.+?)(?<=[^\s*])\*(?![*\w])"""),
    Kind.ITALIC to Regex("""(?<![\w_])_(?=[^\s_])(.+?)(?<=[^\s_])_(?![\w_])"""),
)

/** Splits inline markdown into styled runs; markers without a partner stay as plain text. */
fun parseInline(text: String, base: MdRun = MdRun("")): List<MdRun> {
    val out = mutableListOf<MdRun>()
    var pos = 0
    while (pos < text.length) {
        var best: Pair<Kind, MatchResult>? = null
        for ((kind, re) in inlinePatterns) {
            val m = re.find(text, pos) ?: continue
            if (best == null || m.range.first < best.second.range.first) best = kind to m
        }
        if (best == null) break
        val (kind, m) = best
        if (m.range.first > pos) out += base.copy(text = text.substring(pos, m.range.first))
        val inner = m.groupValues[1]
        when (kind) {
            Kind.CODE -> out += base.copy(text = inner, code = true)
            Kind.LINK -> out += parseInline(inner, base.copy(url = m.groupValues[2]))
            Kind.BOLD -> out += parseInline(inner, base.copy(bold = true))
            Kind.STRIKE -> out += parseInline(inner, base.copy(strike = true))
            Kind.ITALIC -> out += parseInline(inner, base.copy(italic = true))
        }
        pos = m.range.last + 1
    }
    if (pos < text.length) out += base.copy(text = text.substring(pos))
    return out.filter { it.text.isNotEmpty() }
}

// ---- Rendering ----

@Composable
private fun inline(text: String): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(text, codeBg, linkColor) {
        buildAnnotatedString {
            for (r in parseInline(text)) {
                val style = SpanStyle(
                    fontWeight = if (r.bold) FontWeight.Bold else null,
                    fontStyle = if (r.italic) FontStyle.Italic else null,
                    textDecoration = if (r.strike) TextDecoration.LineThrough else null,
                    fontFamily = if (r.code) FontFamily.Monospace else null,
                    background = if (r.code) codeBg else Color.Unspecified,
                )
                if (r.url != null) {
                    val link = LinkAnnotation.Url(
                        r.url,
                        TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                    )
                    withLink(link) { withStyle(style) { append(r.text) } }
                } else {
                    withStyle(style) { append(r.text) }
                }
            }
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val t = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (b in blocks) when (b) {
            is MdBlock.Heading -> Text(
                inline(b.text),
                style = when (b.level) {
                    1 -> t.titleLarge
                    2 -> t.titleMedium
                    else -> t.titleSmall
                }.copy(fontWeight = FontWeight.Bold),
            )
            is MdBlock.Paragraph -> Text(inline(b.text), style = t.bodyLarge)
            is MdBlock.ListItem -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                val marker = if (b.marker == "•" && b.depth > 0) "◦" else b.marker
                Text(marker, style = t.bodyLarge, modifier = Modifier.width(if (marker.length > 2) 32.dp else 22.dp))
                Text(inline(b.text), style = t.bodyLarge)
            }
            is MdBlock.Quote -> {
                val bar = MaterialTheme.colorScheme.outline
                Text(
                    inline(b.text),
                    style = t.bodyLarge.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .drawBehind { drawRect(bar, size = Size(3.dp.toPx(), size.height)) }
                        .padding(start = 12.dp),
                )
            }
            is MdBlock.Code -> Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) {
                    Text(b.code, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = t.bodySmall.fontSize))
                }
            }
            is MdBlock.Table -> TableView(b)
            MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun TableView(b: MdBlock.Table) {
    val cols = maxOf(b.header.size, b.rows.maxOfOrNull { it.size } ?: 0)
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
            listOf(b.header).plus(b.rows).forEachIndexed { r, row ->
                Row {
                    for (c in 0 until cols) {
                        Text(
                            inline(row.getOrElse(c) { "" }),
                            style = MaterialTheme.typography.bodyMedium
                                .copy(fontWeight = if (r == 0) FontWeight.Bold else null),
                            modifier = Modifier.width(140.dp).padding(horizontal = 4.dp, vertical = 3.dp),
                        )
                    }
                }
                if (r == 0) HorizontalDivider()
            }
        }
    }
}
