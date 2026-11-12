package app.aaps.plugins.aps.advisor.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.plugins.aps.advisor.AdvisorMarkdown
import app.aaps.plugins.aps.advisor.MdBlock
import app.aaps.plugins.aps.advisor.MdSpan

private val LIST_MARK_WIDTH = 24.dp

/**
 * Shows a text of the AI with the small part of Markdown that [AdvisorMarkdown] reads: bold, italic, code,
 * headings and lists. The text of the person is not shown with this, it is shown as it was written.
 */
@Composable
internal fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified
) {
    val blocks = remember(text) { AdvisorMarkdown.parse(text) }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(text = annotated(block.spans, codeBackground), style = style, color = color)
                is MdBlock.Heading   -> Text(text = annotated(block.spans, codeBackground), style = style, fontWeight = FontWeight.Bold, color = color)
                is MdBlock.Bullet    -> ListItem(mark = "•", spans = block.spans, codeBackground = codeBackground, style = style, color = color)
                is MdBlock.Numbered  -> ListItem(mark = "${block.number}.", spans = block.spans, codeBackground = codeBackground, style = style, color = color)
            }
        }
    }
}

/** The mark of a list item is a number or a dot, not a text that is translated. */
@Composable
private fun ListItem(mark: String, spans: List<MdSpan>, codeBackground: Color, style: TextStyle, color: Color) {
    Row {
        Text(text = mark, style = style, color = color, modifier = Modifier.width(LIST_MARK_WIDTH))
        Text(text = annotated(spans, codeBackground), style = style, color = color, modifier = Modifier.weight(1f))
    }
}

private fun annotated(spans: List<MdSpan>, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        val look = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) codeBackground else Color.Unspecified
        )
        withStyle(look) { append(span.text) }
    }
}
