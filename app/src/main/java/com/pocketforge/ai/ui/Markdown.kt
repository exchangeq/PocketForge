package com.pocketforge.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 极简 Markdown 渲染器：不引第三方库，覆盖 AI 回答里最常见的语法，
 * 让 `**粗体**`、列表、表格、代码块正常显示，而不是把星号直接甩给用户。
 */
sealed class MdBlock {
    data class Paragraph(val text: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullets(val items: List<String>, val ordered: Boolean) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
    data class Table(val rows: List<List<String>>) : MdBlock()
    data object Rule : MdBlock()
}

private val ORDERED_RE = Regex("^\\s*(\\d+)[.、)]\\s+(.*)$")
private val BULLET_RE = Regex("^\\s*[-*•]\\s+(.*)$")

fun parseMarkdown(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split("\n")
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    var i = 0

    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
            paragraph.setLength(0)
        }
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        when {
            trimmed.startsWith("```") -> {
                flushParagraph()
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    code.appendLine(lines[i])
                    i++
                }
                blocks.add(MdBlock.Code(lang, code.toString().trimEnd()))
            }

            trimmed.isEmpty() -> flushParagraph()

            trimmed.matches(Regex("^(---|___|\\*\\*\\*)$")) -> {
                flushParagraph()
                blocks.add(MdBlock.Rule)
            }

            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 4)
                blocks.add(MdBlock.Heading(level, trimmed.drop(level).trim()))
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = StringBuilder(trimmed.removePrefix(">").trim())
                i++
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quote.append(' ').append(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                blocks.add(MdBlock.Quote(quote.toString()))
                continue
            }

            trimmed.startsWith("|") && trimmed.contains("|") -> {
                flushParagraph()
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                    val isSeparator = cells.all { cell -> cell.isEmpty() || cell.all { c -> c == '-' || c == ':' } }
                    if (!isSeparator) rows.add(cells)
                    i++
                }
                if (rows.isNotEmpty()) blocks.add(MdBlock.Table(rows))
                continue
            }

            BULLET_RE.matches(line) || ORDERED_RE.matches(line) -> {
                flushParagraph()
                val ordered = ORDERED_RE.matches(line)
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val cur = lines[i]
                    val m = if (ordered) ORDERED_RE.find(cur) else BULLET_RE.find(cur)
                    if (m == null) break
                    items.add(if (ordered) m.groupValues[2] else m.groupValues[1])
                    i++
                }
                blocks.add(MdBlock.Bullets(items, ordered))
                continue
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(trimmed)
            }
        }
        i++
    }
    flushParagraph()
    return blocks
}

@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    baseSize: Int = 14,
    baseLine: Int = 23,
) {
    val blocks = parseMarkdown(source)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = inlineText(block.text),
                    fontSize = baseSize.sp,
                    lineHeight = baseLine.sp,
                )

                is MdBlock.Heading -> Text(
                    text = inlineText(block.text),
                    fontSize = when (block.level) {
                        1 -> 17.sp
                        2 -> 15.5.sp
                        else -> 14.5.sp
                    },
                    fontWeight = FontWeight.Bold,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                if (block.ordered) "${index + 1}." else "•",
                                fontSize = baseSize.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(20.dp),
                            )
                            Text(
                                text = inlineText(item),
                                fontSize = baseSize.sp,
                                lineHeight = baseLine.sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                is MdBlock.Quote -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = inlineText(block.text),
                            fontSize = baseSize.sp,
                            lineHeight = baseLine.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }

                is MdBlock.Code -> Column {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column {
                            if (block.lang.isNotEmpty()) {
                                Text(
                                    block.lang,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 10.dp, top = 6.dp),
                                )
                            }
                            Text(
                                text = block.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }

                is MdBlock.Table -> Column {
                    block.rows.forEachIndexed { rowIndex, row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { cell ->
                                Text(
                                    text = inlineText(cell),
                                    fontSize = 12.5.sp,
                                    lineHeight = 19.sp,
                                    fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(vertical = 5.dp, horizontal = 4.dp),
                                )
                            }
                        }
                        if (rowIndex == 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    }
                }

                MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** 行内样式：**粗体**、`代码`、*斜体*、~~删除线~~、[链接](url) */
@Composable
private fun inlineText(text: String): AnnotatedString {
    val accent = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurface

    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > 0) {
                        withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > 0) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = codeBg,
                                color = codeColor,
                                fontSize = 13.sp,
                            )
                        ) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i)
                    val openParen = if (closeBracket > 0) text.indexOf('(', closeBracket) else -1
                    val closeParen = if (openParen > 0) text.indexOf(')', openParen) else -1
                    if (closeBracket > 0 && openParen == closeBracket + 1 && closeParen > 0) {
                        withStyle(
                            SpanStyle(
                                color = accent,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                            )
                        ) { append(text.substring(i + 1, closeBracket)) }
                        withStyle(SpanStyle(color = accent, fontSize = 11.sp)) { append(" ↗") }
                        i = closeParen + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                else -> {
                    append(text[i]); i++
                }
            }
        }
    }
}

@Composable
fun MarkdownPlain(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text = inlineText(text), modifier = modifier, color = color)
}
