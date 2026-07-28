package app.drawbridge.policy.blocklist

import app.drawbridge.policy.model.BlocklistFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BlocklistFileTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `round-trips through the compiled format`() {
        val builder = BlocklistBuilder()
        builder.addAll(listOf("example.com", "ads.example.net", "tracker.test"))
        val file = temporaryFolder.newFile("blocklist.bin")
        builder.writeTo(file)

        BlocklistFile.open(file).use { set ->
            assertEquals(3, set.size)
            assertTrue(set.matches("www.example.com"))
            assertTrue(set.matches("tracker.test"))
            assertFalse(set.matches("example.org"))
        }
    }

    @Test
    fun `parses hosts-file syntax`() {
        val builder = BlocklistBuilder()
        builder.addSource(
            """
            # comment line
            0.0.0.0 blocked.example
            127.0.0.1	tabs.example
            0.0.0.0 localhost

            """.trimIndent().reader().buffered(),
            BlocklistFormat.HOSTS,
        )
        assertEquals(2, builder.acceptedLines)
        val set = ArrayDomainSet(builder.build())
        assertTrue(set.matches("blocked.example"))
        assertTrue(set.matches("tabs.example"))
        assertFalse(set.matches("localhost"))
    }

    @Test
    fun `parses adblock-style rules found in domain lists`() {
        val builder = BlocklistBuilder()
        builder.addSource(
            "||tracker.example^\n||ads.example^\$third-party\nplain.example\n".reader().buffered(),
            BlocklistFormat.DOMAINS,
        )
        val set = ArrayDomainSet(builder.build())
        assertTrue(set.matches("tracker.example"))
        assertTrue(set.matches("ads.example"))
        assertTrue(set.matches("plain.example"))
    }

    @Test
    fun `rejects lines that are not domains`() {
        val builder = BlocklistBuilder()
        builder.addSource(
            "not a domain\nno-dot\nhttp://example.com/path\nok.example\n".reader().buffered(),
            BlocklistFormat.DOMAINS,
        )
        assertEquals(1, builder.acceptedLines)
    }

    @Test(expected = java.io.IOException::class)
    fun `refuses a file with the wrong magic number`() {
        val file: File = temporaryFolder.newFile("bogus.bin")
        file.writeBytes(ByteArray(64))
        BlocklistFile.open(file).use { }
    }
}
