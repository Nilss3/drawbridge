package app.drawbridge.policy.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainSetTest {

    @Test
    fun `matches the exact domain`() {
        val set = DomainSet.of(listOf("example.com"))
        assertTrue(set.matches("example.com"))
    }

    @Test
    fun `matches subdomains of a listed domain`() {
        val set = DomainSet.of(listOf("example.com"))
        assertTrue(set.matches("www.example.com"))
        assertTrue(set.matches("cdn.assets.example.com"))
    }

    @Test
    fun `does not match a sibling or superstring domain`() {
        val set = DomainSet.of(listOf("example.com"))
        assertFalse(set.matches("example.org"))
        assertFalse(set.matches("notexample.com"))
        assertFalse(set.matches("example.com.evil.net"))
    }

    @Test
    fun `is case and trailing-dot insensitive`() {
        val set = DomainSet.of(listOf("Example.COM."))
        assertTrue(set.matches("WWW.example.com"))
        assertTrue(set.matches("example.com."))
    }

    @Test
    fun `strips a leading wildcard label`() {
        val set = DomainSet.of(listOf("*.ads.example.com"))
        assertTrue(set.matches("banner.ads.example.com"))
    }

    @Test
    fun `a single-label entry cannot black-hole a whole TLD`() {
        // A malformed upstream list containing a bare "com" must not take the
        // internet down; the suffix walk stops before the last label.
        val set = DomainSet.of(listOf("com"))
        assertFalse(set.matches("example.com"))
        assertFalse(set.matches("a.b.example.com"))
    }

    @Test
    fun `empty set matches nothing`() {
        assertFalse(DomainSet.EMPTY.matches("example.com"))
        assertEquals(0, DomainSet.EMPTY.size)
    }

    @Test
    fun `de-duplicates entries`() {
        val set = DomainSet.of(listOf("example.com", "EXAMPLE.com", "example.com."))
        assertEquals(1, set.size)
    }

    @Test
    fun `composite set matches if any member does`() {
        val composite = CompositeDomainSet(
            listOf(DomainSet.of(listOf("a.test")), DomainSet.of(listOf("b.test"))),
        )
        assertTrue(composite.matches("x.a.test"))
        assertTrue(composite.matches("b.test"))
        assertFalse(composite.matches("c.test"))
    }

    @Test
    fun `hashing a suffix in place equals hashing the substring`() {
        val domain = "sub.example.com"
        assertEquals(
            DomainHash.of("example.com"),
            DomainHash.of(domain, domain.indexOf("example")),
        )
    }
}
