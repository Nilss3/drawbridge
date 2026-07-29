package app.drawbridge.herald.bookmarks

import app.drawbridge.herald.bookmarks.BookmarkHtml.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bookmark file format is not valid HTML and never has been, so most of
 * these defend the parser against what real exports actually contain rather
 * than against what the format nominally says.
 */
class BookmarkHtmlTest {

    private val tree = listOf(
        Node.Item("Wikipedia", "https://en.wikipedia.org/"),
        Node.Folder(
            title = "School",
            children = listOf(
                Node.Item("Smartschool", "https://smartschool.be/"),
                Node.Folder(
                    title = "Maths",
                    children = listOf(Node.Item("Khan Academy", "https://khanacademy.org/")),
                ),
            ),
        ),
    )

    @Test
    fun `a written tree parses back to itself`() {
        val parsed = BookmarkHtml.parse(BookmarkHtml.write(tree))

        assertFalse(parsed.truncated)
        assertEquals(tree, parsed.nodes)
    }

    @Test
    fun `titles and urls survive the characters that need escaping`() {
        val awkward = listOf(
            Node.Item("Tom & Jerry <b>\"quoted\"</b>", "https://example.com/?a=1&b=2"),
        )

        val parsed = BookmarkHtml.parse(BookmarkHtml.write(awkward))

        assertEquals(awkward, parsed.nodes)
    }

    @Test
    fun `entities in a foreign export are decoded`() {
        val document = """
            <DL><p>
                <DT><A HREF="https://example.com/?a=1&amp;b=2">Caf&#233; &amp; Bar</A>
            </DL><p>
        """.trimIndent()

        val parsed = BookmarkHtml.parse(document)

        assertEquals(
            listOf(Node.Item("Café & Bar", "https://example.com/?a=1&b=2")),
            parsed.nodes,
        )
    }

    /**
     * Firefox writes non-ASCII as UTF-8 and never emits these, but files that
     * have been through another tool routinely do. A literal `Caf&eacute;` in
     * the bookmark list reads as a broken import.
     */
    @Test
    fun `named entities beyond the xml five are decoded`() {
        val document = """
            <DL><p>
                <DT><A HREF="https://example.com/a">Caf&eacute; &mdash; M&uuml;nchen</A>
                <DT><A HREF="https://example.com/b">&pound;5 &hellip; 50&deg;</A>
                <DT><A HREF="https://example.com/c">&Eacute;cole vs &eacute;cole</A>
            </DL><p>
        """.trimIndent()

        assertEquals(
            listOf(
                Node.Item("Café — München", "https://example.com/a"),
                Node.Item("£5 … 50°", "https://example.com/b"),
                Node.Item("École vs école", "https://example.com/c"),
            ),
            BookmarkHtml.parse(document).nodes,
        )
    }

    @Test
    fun `an unknown entity is left alone rather than swallowed`() {
        val document = """<DL><p><DT><A HREF="https://example.com/">a &notanentity; b</A></DL>"""

        assertEquals(
            listOf(Node.Item("a &notanentity; b", "https://example.com/")),
            BookmarkHtml.parse(document).nodes,
        )
    }

    @Test
    fun `add dates round-trip as epoch seconds`() {
        val dated = listOf(Node.Item("Dated", "https://example.com/", addedAtSeconds = 1_753_000_000))

        assertEquals(dated, BookmarkHtml.parse(BookmarkHtml.write(dated)).nodes)
    }

    /**
     * Chrome closes neither `<DT>` nor, in places, `<DL>`, and puts a bare `<p>`
     * after every list. A file like this is the norm, not an edge case.
     */
    @Test
    fun `unclosed tags still yield the tree`() {
        val chromeish = """
            <!DOCTYPE NETSCAPE-Bookmark-file-1>
            <DL><p>
                <DT><H3 ADD_DATE="1600000000">Bookmarks bar</H3>
                <DL><p>
                    <DT><A HREF="https://one.example/" ADD_DATE="1600000001">One</A>
                    <DT><A HREF="https://two.example/">Two</A>
        """.trimIndent()

        val parsed = BookmarkHtml.parse(chromeish)

        assertEquals(
            listOf(
                Node.Folder(
                    title = "Bookmarks bar",
                    children = listOf(
                        Node.Item("One", "https://one.example/", 1_600_000_001),
                        Node.Item("Two", "https://two.example/"),
                    ),
                    addedAtSeconds = 1_600_000_000,
                ),
            ),
            parsed.nodes,
        )
    }

    /**
     * The case the other tests missed: a bookmark that comes *after* a folder
     * has been closed. Everything else here either closes nothing at all or
     * ends at the close, so a `</DL>` that did nothing still produced the right
     * tree — and this one silently filed the last bookmark inside the folder
     * above it.
     */
    @Test
    fun `a bookmark after a closed folder stays outside it`() {
        val document = """
            <DL><p>
                <DT><H3>Toolbar</H3>
                <DL><p>
                    <DT><H3>Nieuws</H3>
                    <DL><p>
                        <DT><A HREF="https://one.example/">Inner</A>
                    </DL><p>
                    <DT><A HREF="https://two.example/">After inner</A>
                </DL><p>
                <DT><A HREF="https://three.example/">After outer</A>
            </DL><p>
        """.trimIndent()

        assertEquals(
            listOf(
                Node.Folder(
                    title = "Toolbar",
                    children = listOf(
                        Node.Folder("Nieuws", listOf(Node.Item("Inner", "https://one.example/"))),
                        Node.Item("After inner", "https://two.example/"),
                    ),
                ),
                Node.Item("After outer", "https://three.example/"),
            ),
            BookmarkHtml.parse(document).nodes,
        )
    }

    @Test
    fun `only http and https bookmarks are imported`() {
        val document = """
            <DL><p>
                <DT><A HREF="javascript:alert(1)">Bookmarklet</A>
                <DT><A HREF="file:///etc/passwd">Local file</A>
                <DT><A HREF="data:text/html,hello">Inline</A>
                <DT><A HREF="HTTPS://ok.example/">Fine</A>
            </DL><p>
        """.trimIndent()

        val parsed = BookmarkHtml.parse(document)

        assertEquals(listOf(Node.Item("Fine", "HTTPS://ok.example/")), parsed.nodes)
    }

    @Test
    fun `a folder with no name flattens into its parent`() {
        val document = """
            <DL><p>
                <DT><H3></H3>
                <DL><p>
                    <DT><A HREF="https://one.example/">One</A>
                </DL><p>
            </DL><p>
        """.trimIndent()

        assertEquals(
            listOf(Node.Item("One", "https://one.example/")),
            BookmarkHtml.parse(document).nodes,
        )
    }

    @Test
    fun `a bookmark with no title falls back to its url`() {
        val document = """<DL><p><DT><A HREF="https://one.example/"></A></DL>"""

        assertEquals(
            listOf(Node.Item("https://one.example/", "https://one.example/")),
            BookmarkHtml.parse(document).nodes,
        )
    }

    @Test
    fun `an oversized file is truncated rather than read whole`() {
        val entry = """<DT><A HREF="https://example.com/">x</A>"""
        val document = "<DL><p>" + entry.repeat(BookmarkHtml.MAX_INPUT_CHARS / entry.length + 10)

        val parsed = BookmarkHtml.parse(document)

        assertTrue(parsed.truncated)
        assertTrue(parsed.nodes.isNotEmpty())
    }

    @Test
    fun `garbage parses to nothing rather than throwing`() {
        listOf("", "not html at all", "<<<>>>", "<A HREF=", "<DL><DL><DL>", "&#;&amp")
            .forEach { assertEquals(emptyList<Node>(), BookmarkHtml.parse(it).nodes) }
    }
}
