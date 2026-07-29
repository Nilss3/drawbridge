package app.drawbridge.herald.bookmarks

/**
 * The Netscape bookmark file — `bookmarks.html`, the format Firefox, Chrome,
 * Safari and Edge all read and write.
 *
 * It is chosen over anything herald-shaped so that a phone can be seeded from a
 * parent's own browser export, and so an export is readable somewhere other than
 * here. The format is nominally HTML but has never been valid HTML: folders open
 * a `<DL>` that is frequently never closed, `<DT>` is never closed, and Chrome
 * emits a stray `<p>` after every list. Parsing it therefore means being
 * deliberately forgiving rather than correct, which is why this is a hand-rolled
 * scanner over the tags that matter and not an HTML parser.
 *
 * Pure Kotlin with no Android imports, so the awkward parts are unit-testable.
 */
object BookmarkHtml {

    /** A node in an exported or imported tree. */
    sealed interface Node {
        val title: String

        data class Folder(
            override val title: String,
            val children: List<Node>,
            val addedAtSeconds: Long? = null,
        ) : Node

        data class Item(
            override val title: String,
            val url: String,
            val addedAtSeconds: Long? = null,
        ) : Node
    }

    /**
     * Caps on what [parse] will accept. The input is a file the user picked, so
     * it is untrusted: a deeply nested or enormous file must fail as a truncated
     * import rather than as an out-of-memory kill.
     */
    const val MAX_INPUT_CHARS = 8 * 1024 * 1024
    private const val MAX_NODES = 50_000
    private const val MAX_DEPTH = 32

    // ---------------------------------------------------------------- writing

    fun write(nodes: List<Node>): String = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        append("<!-- This is an automatically generated file.\n")
        append("     It will be read and overwritten.\n")
        append("     DO NOT EDIT! -->\n")
        append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        append("<TITLE>Bookmarks</TITLE>\n")
        append("<H1>Bookmarks</H1>\n")
        append("<DL><p>\n")
        nodes.forEach { writeNode(it, depth = 1) }
        append("</DL><p>\n")
    }

    private fun StringBuilder.writeNode(node: Node, depth: Int) {
        val indent = "    ".repeat(depth)
        when (node) {
            is Node.Folder -> {
                append(indent).append("<DT><H3").appendDate(node.addedAtSeconds).append('>')
                append(node.title.escape()).append("</H3>\n")
                append(indent).append("<DL><p>\n")
                node.children.forEach { writeNode(it, depth + 1) }
                append(indent).append("</DL><p>\n")
            }

            is Node.Item -> {
                append(indent).append("<DT><A HREF=\"").append(node.url.escape()).append('"')
                appendDate(node.addedAtSeconds)
                append('>').append(node.title.escape()).append("</A>\n")
            }
        }
    }

    private fun StringBuilder.appendDate(seconds: Long?): StringBuilder =
        if (seconds == null) this else append(" ADD_DATE=\"").append(seconds).append('"')

    private fun String.escape(): String = buildString(length) {
        this@escape.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    // ---------------------------------------------------------------- parsing

    /**
     * What a parse produced, and whether anything had to be dropped to stay
     * inside the caps — the import screen says so rather than silently losing
     * half a file.
     */
    data class Parsed(val nodes: List<Node>, val truncated: Boolean)

    /**
     * Reads a bookmark file. Never throws on malformed input: whatever could be
     * recognised is returned and the rest is ignored.
     *
     * Only `http` and `https` bookmarks are kept. A bookmark file can carry
     * `javascript:` bookmarklets, `file:` and `data:` URLs; none of them are
     * things a filtering browser should be talked into storing as a one-tap
     * entry point by handing it a file.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    fun parse(html: String): Parsed {
        if (html.length > MAX_INPUT_CHARS) {
            return parse(html.take(MAX_INPUT_CHARS)).copy(truncated = true)
        }

        val root = Frame(title = null)
        val stack = ArrayDeque<Frame>().apply { addLast(root) }
        var pendingFolderTitle: String? = null
        var pendingFolderDate: Long? = null
        var nodeCount = 0
        var truncated = false

        var i = 0
        while (i < html.length) {
            val open = html.indexOf('<', i)
            if (open < 0) break
            val close = html.indexOf('>', open)
            if (close < 0) break

            val tag = html.substring(open + 1, close)
            // The closing slash is read separately rather than being treated as
            // part of the name: folding it in once cost `</DL>` its name
            // entirely, so folders never closed and everything after one was
            // swallowed into it.
            val closing = tag.trimStart().startsWith('/')
            val name = tag.trimStart()
                .removePrefix("/")
                .takeWhile { !it.isWhitespace() && it != '>' && it != '/' }
                .lowercase()
            i = close + 1

            when (if (closing) "/$name" else name) {
                "h3" -> {
                    // A blank heading opens no folder: its list is treated as
                    // anonymous and its contents belong to the folder above.
                    pendingFolderTitle = textUntilClose(html, i, "h3")
                        .also { i = it.end }
                        .text
                        .ifBlank { null }
                    pendingFolderDate = tag.attribute("add_date")?.toLongOrNull()
                }

                "dl" -> {
                    // The outermost <DL> is the root list, which is already on
                    // the stack. Any other one opens whatever <H3> preceded it;
                    // with no <H3> it is an anonymous list, and its contents
                    // belong to the enclosing folder.
                    if (stack.size == 1 && root.children.isEmpty() && pendingFolderTitle == null) {
                        // Already represented by `root`.
                    } else if (stack.size >= MAX_DEPTH) {
                        truncated = true
                    } else {
                        stack.addLast(Frame(pendingFolderTitle, pendingFolderDate))
                    }
                    pendingFolderTitle = null
                    pendingFolderDate = null
                }

                "/dl" -> {
                    if (stack.size > 1) {
                        val done = stack.removeLast()
                        val parent = stack.last()
                        if (done.title == null) {
                            parent.children += done.children
                        } else {
                            parent.children += Node.Folder(done.title, done.children, done.addedAt)
                        }
                    }
                }

                "a" -> {
                    val text = textUntilClose(html, i, "a").also { i = it.end }
                    val url = tag.attribute("href")?.decodeEntities()
                    if (url != null && isImportable(url)) {
                        if (nodeCount >= MAX_NODES) {
                            truncated = true
                        } else {
                            nodeCount++
                            stack.last().children += Node.Item(
                                title = text.text.ifBlank { url },
                                url = url,
                                addedAtSeconds = tag.attribute("add_date")?.toLongOrNull(),
                            )
                        }
                    }
                }
            }
        }

        // Unclosed folders — common in the wild. Fold each into its parent so
        // the structure survives even when the file's tags do not.
        while (stack.size > 1) {
            val done = stack.removeLast()
            val parent = stack.last()
            if (done.title == null) {
                parent.children += done.children
            } else {
                parent.children += Node.Folder(done.title, done.children, done.addedAt)
            }
        }

        return Parsed(root.children.toList(), truncated)
    }

    private class Frame(val title: String?, val addedAt: Long? = null) {
        val children = mutableListOf<Node>()
    }

    private class Text(val text: String, val end: Int)

    /**
     * The text between here and `</name>`, entity-decoded. A missing close tag
     * stops at the next tag instead, so an unterminated `<A>` costs its own
     * label rather than the rest of the file.
     */
    private fun textUntilClose(html: String, from: Int, name: String): Text {
        val closeTag = html.indexOf("</$name", from, ignoreCase = true)
        val nextTag = html.indexOf('<', from)
        val stop = when {
            closeTag >= 0 && (nextTag < 0 || closeTag <= nextTag) -> closeTag
            nextTag >= 0 -> nextTag
            else -> html.length
        }
        val text = html.substring(from, stop).decodeEntities().trim()
        val resume = if (stop == closeTag) {
            val gt = html.indexOf('>', stop)
            if (gt < 0) html.length else gt + 1
        } else {
            stop
        }
        return Text(text, resume)
    }

    /** Reads `name="value"` or `name=value` out of a raw tag body, case-insensitively. */
    private fun String.attribute(name: String): String? {
        var search = 0
        while (true) {
            val at = indexOf(name, search, ignoreCase = true)
            if (at < 0) return null
            search = at + name.length
            // Must be a whole attribute name, and must be followed by '='.
            val before = getOrNull(at - 1)
            if (before != null && !before.isWhitespace()) continue
            val eq = search + substring(search).takeWhile { it.isWhitespace() }.length
            if (getOrNull(eq) != '=') continue

            var v = eq + 1
            while (v < length && this[v].isWhitespace()) v++
            val quote = getOrNull(v)
            return if (quote == '"' || quote == '\'') {
                val end = indexOf(quote, v + 1)
                if (end < 0) substring(v + 1) else substring(v + 1, end)
            } else {
                substring(v).takeWhile { !it.isWhitespace() }
            }
        }
    }

    private fun isImportable(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)

    private fun String.decodeEntities(): String {
        if (!contains('&')) return this
        return buildString(length) {
            var i = 0
            while (i < this@decodeEntities.length) {
                val c = this@decodeEntities[i]
                if (c != '&') {
                    append(c)
                    i++
                    continue
                }
                val semi = this@decodeEntities.indexOf(';', i + 1)
                // An unterminated or absurdly long "&…" is a literal ampersand.
                if (semi < 0 || semi - i > MAX_ENTITY_LENGTH) {
                    append(c)
                    i++
                    continue
                }
                val body = this@decodeEntities.substring(i + 1, semi)
                val decoded = decodeEntity(body)
                if (decoded == null) {
                    append(c)
                    i++
                } else {
                    append(decoded)
                    i = semi + 1
                }
            }
        }
    }

    private fun decodeEntity(body: String): String? = when {
        body.equals("amp", ignoreCase = true) -> "&"
        body.equals("lt", ignoreCase = true) -> "<"
        body.equals("gt", ignoreCase = true) -> ">"
        body.equals("quot", ignoreCase = true) -> "\""
        body.equals("apos", ignoreCase = true) -> "'"
        body.startsWith("#x", ignoreCase = true) ->
            body.drop(2).toIntOrNull(radix = 16)?.let { codePoint(it) }

        body.startsWith("#") -> body.drop(1).toIntOrNull()?.let { codePoint(it) }

        // Named entities are case-sensitive: &Eacute; and &eacute; are
        // different letters, so this cannot fold into the cases above.
        else -> NAMED_ENTITIES[body]
    }

    private fun codePoint(value: Int): String? =
        if (value in 1..0x10FFFF) String(Character.toChars(value)) else null

    /**
     * HTML 4.01's Latin-1 block, whose entity names run in code-point order from
     * 160, plus the punctuation that turns up in article and site titles.
     *
     * Firefox's own exporter escapes only `& < > "` and writes everything else
     * as UTF-8, so a file straight out of Firefox needs none of these. Files
     * that have been through a feed reader, a hand edit or someone's export
     * script routinely do — and `Caf&eacute;` sitting in the bookmark list
     * looks exactly like an import that did not work.
     */
    private val NAMED_ENTITIES: Map<String, String> = buildMap {
        val latin1 = (
            "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg " +
                "macr deg plusmn sup2 sup3 acute micro para middot cedil sup1 ordm raquo " +
                "frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig " +
                "Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve " +
                "Oacute Ocirc Otilde Ouml times Oslash Ugrave Uacute Ucirc Uuml Yacute " +
                "THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave " +
                "eacute ecirc euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc " +
                "otilde ouml divide oslash ugrave uacute ucirc uuml yacute thorn yuml"
            ).split(" ")

        latin1.forEachIndexed { index, name ->
            put(name, String(Character.toChars(LATIN1_FIRST + index)))
        }

        putAll(
            mapOf(
                "ndash" to "–", "mdash" to "—", "lsquo" to "‘",
                "rsquo" to "’", "sbquo" to "‚", "ldquo" to "“",
                "rdquo" to "”", "bdquo" to "„", "dagger" to "†",
                "Dagger" to "‡", "bull" to "•", "hellip" to "…",
                "permil" to "‰", "prime" to "′", "Prime" to "″",
                "lsaquo" to "‹", "rsaquo" to "›", "euro" to "€",
                "trade" to "™", "minus" to "−",
            ),
        )
    }

    /** `&nbsp;`, the first name in the Latin-1 block. */
    private const val LATIN1_FIRST = 160

    private const val MAX_ENTITY_LENGTH = 10
}
