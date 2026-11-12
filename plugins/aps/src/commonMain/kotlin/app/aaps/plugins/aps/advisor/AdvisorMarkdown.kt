package app.aaps.plugins.aps.advisor

/** A piece of text with its look. A span has only one look. Bold inside italic is not handled. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false
)

/** A block of a text: a paragraph, a heading or one item of a list. */
sealed interface MdBlock {

    data class Paragraph(val spans: List<MdSpan>) : MdBlock

    data class Heading(val spans: List<MdSpan>) : MdBlock

    data class Bullet(val spans: List<MdSpan>) : MdBlock

    data class Numbered(val number: Int, val spans: List<MdSpan>) : MdBlock
}

/**
 * Reads the small part of Markdown that the AI uses: `**bold**`, `*italic*`, `code`, headings with `#`, and lists
 * with `-`, `*`, `+` or `1.`. Everything else stays as it is, also links, images and tables. A link is not made
 * clickable, so no link of an answer can be opened from the app by mistake.
 *
 * It never throws. A marker that has no end stays in the text.
 */
object AdvisorMarkdown {

    private val HEADING = Regex("^#{1,6}\\s+(.+)$")
    private val BULLET = Regex("^[-*+•]\\s+(.+)$")
    private val NUMBERED = Regex("^(\\d{1,3})[.)]\\s+(.+)$")

    fun parse(text: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()

        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(spans(paragraph.joinToString("\n"))))
                paragraph.clear()
            }
        }

        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                flush()
                continue
            }
            val heading = HEADING.matchEntire(line)
            val bullet = BULLET.matchEntire(line)
            val numbered = NUMBERED.matchEntire(line)
            when {
                heading != null  -> {
                    flush()
                    blocks.add(MdBlock.Heading(spans(heading.groupValues[1])))
                }

                bullet != null   -> {
                    flush()
                    blocks.add(MdBlock.Bullet(spans(bullet.groupValues[1])))
                }

                numbered != null -> {
                    flush()
                    blocks.add(MdBlock.Numbered(numbered.groupValues[1].toInt(), spans(numbered.groupValues[2])))
                }

                else             -> paragraph.add(line)
            }
        }
        flush()
        return blocks
    }

    /** The spans of one line or paragraph. */
    internal fun spans(text: String): List<MdSpan> {
        val result = mutableListOf<MdSpan>()
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                result.add(MdSpan(plain.toString()))
                plain.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    flushPlain()
                    result.add(MdSpan(text.substring(i + 1, end), code = true))
                    i = end + 1
                    continue
                }
            }
            if (text.startsWith("**", i)) {
                val end = text.indexOf("**", i + 2)
                // The text inside must not start or end with a space: "2 ** 3 ** 4" is not bold.
                if (end > i + 2 && !text[i + 2].isWhitespace() && !text[end - 1].isWhitespace()) {
                    flushPlain()
                    result.add(MdSpan(text.substring(i + 2, end), bold = true))
                    i = end + 2
                    continue
                }
            }
            if (text[i] == '*' && opensItalic(text, i)) {
                val end = italicEnd(text, i + 1)
                if (end != -1) {
                    flushPlain()
                    result.add(MdSpan(text.substring(i + 1, end), italic = true))
                    i = end + 1
                    continue
                }
            }
            plain.append(text[i])
            i++
        }
        flushPlain()
        return result
    }

    /** A star opens italic when a word or a space is before it and a letter or digit is right after it ("2*3*4" is not italic). */
    private fun opensItalic(text: String, index: Int): Boolean {
        if (index + 1 >= text.length) return false
        val next = text[index + 1]
        if (next == '*' || next.isWhitespace()) return false
        return index == 0 || !text[index - 1].isLetterOrDigit()
    }

    /** The index of the star that closes the italic text that starts at [from], or -1. */
    private fun italicEnd(text: String, from: Int): Int {
        var j = from
        while (j < text.length) {
            if (text[j] == '*' && j > from && !text[j - 1].isWhitespace() && (j + 1 >= text.length || text[j + 1] != '*')) return j
            j++
        }
        return -1
    }
}
