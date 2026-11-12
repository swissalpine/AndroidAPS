package app.aaps.plugins.aps.advisor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdvisorMarkdownTest {

    private fun spans(text: String) = AdvisorMarkdown.spans(text)

    @Test
    fun `bold text between two stars is bold`() {
        assertEquals(
            listOf(MdSpan("The "), MdSpan("Afternoon basal", bold = true), MdSpan(" is lower.")),
            spans("The **Afternoon basal** is lower.")
        )
    }

    @Test
    fun `italic text and code are read`() {
        assertEquals(
            listOf(MdSpan("a "), MdSpan("soft", italic = true), MdSpan(" and "), MdSpan("0.55", code = true)),
            spans("a *soft* and `0.55`")
        )
    }

    @Test
    fun `a marker without an end stays in the text`() {
        assertEquals(listOf(MdSpan("a **b")), spans("a **b"))
        assertEquals(listOf(MdSpan("a *b")), spans("a *b"))
        assertEquals(listOf(MdSpan("a `b")), spans("a `b"))
    }

    @Test
    fun `stars in a calculation and in words with underscores are not changed`() {
        assertEquals(listOf(MdSpan("2 * 3 * 4")), spans("2 * 3 * 4"))
        assertEquals(listOf(MdSpan("2*3*4")), spans("2*3*4"))
        assertEquals(listOf(MdSpan("time_of_day and change_percent")), spans("time_of_day and change_percent"))
        assertEquals(listOf(MdSpan("2 ** 3 ** 4")), spans("2 ** 3 ** 4"))
    }

    @Test
    fun `a link stays as text`() {
        assertEquals(listOf(MdSpan("see [the page](https://example.com)")), spans("see [the page](https://example.com)"))
    }

    @Test
    fun `an empty text has no block`() {
        assertTrue(AdvisorMarkdown.parse("").isEmpty())
        assertTrue(AdvisorMarkdown.parse("  \n \n").isEmpty())
    }

    @Test
    fun `lines are one paragraph and a blank line starts a new one`() {
        val blocks = AdvisorMarkdown.parse("First line\nsecond line\n\nNew paragraph")

        assertEquals(
            listOf(
                MdBlock.Paragraph(listOf(MdSpan("First line\nsecond line"))),
                MdBlock.Paragraph(listOf(MdSpan("New paragraph")))
            ),
            blocks
        )
    }

    @Test
    fun `lists with a dash, a star, a plus and numbers are read`() {
        val blocks = AdvisorMarkdown.parse("- one\n* two\n+ three\n1. first\n2) second")

        assertEquals(
            listOf(
                MdBlock.Bullet(listOf(MdSpan("one"))),
                MdBlock.Bullet(listOf(MdSpan("two"))),
                MdBlock.Bullet(listOf(MdSpan("three"))),
                MdBlock.Numbered(1, listOf(MdSpan("first"))),
                MdBlock.Numbered(2, listOf(MdSpan("second")))
            ),
            blocks
        )
    }

    @Test
    fun `a minus sign before a number is not a list`() {
        assertEquals(listOf(MdBlock.Paragraph(listOf(MdSpan("-5 mg/dL per hour")))), AdvisorMarkdown.parse("-5 mg/dL per hour"))
    }

    @Test
    fun `a line that starts with bold is a paragraph and not a list`() {
        assertEquals(
            listOf(MdBlock.Paragraph(listOf(MdSpan("Basal", bold = true), MdSpan(" is low")))),
            AdvisorMarkdown.parse("**Basal** is low")
        )
    }

    @Test
    fun `bold inside a list item is read`() {
        assertEquals(
            listOf(MdBlock.Bullet(listOf(MdSpan("Night", bold = true), MdSpan(": falls")))),
            AdvisorMarkdown.parse("- **Night**: falls")
        )
    }

    @Test
    fun `a heading is read, and a list stops a paragraph`() {
        val blocks = AdvisorMarkdown.parse("## What I see\nSome text\n- point")

        assertEquals(
            listOf(
                MdBlock.Heading(listOf(MdSpan("What I see"))),
                MdBlock.Paragraph(listOf(MdSpan("Some text"))),
                MdBlock.Bullet(listOf(MdSpan("point")))
            ),
            blocks
        )
    }
}
