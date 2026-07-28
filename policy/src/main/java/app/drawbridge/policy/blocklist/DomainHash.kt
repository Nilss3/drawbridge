package app.drawbridge.policy.blocklist

/**
 * Domains are stored as 64-bit hashes rather than strings.
 *
 * A merged adult + gambling + ad/tracker list runs to a few hundred thousand
 * entries. As strings in a [HashSet] that is tens of megabytes of heap, which
 * matters because the DNS filter runs as an always-on foreground service. As a
 * sorted array of hashes it is 8 bytes per domain, memory-mappable, and lookups
 * are a binary search.
 *
 * The trade-off is that the set cannot be enumerated and collisions cause false
 * positives. With 64-bit hashes and ~10^6 entries the chance that any given
 * lookup collides is around 10^-13 — far below the rate at which the upstream
 * blocklists themselves contain mistakes.
 */
object DomainHash {

    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 14695981039346656037
    private const val FNV_PRIME = 0x100000001b3L

    /**
     * FNV-1a over the UTF-8 bytes of `domain[startIndex..]`, ASCII-lowercased.
     *
     * Takes a start index so the suffix walk in [DomainSet.matches] can hash
     * `b.example.com` out of `a.b.example.com` without allocating substrings.
     */
    fun of(domain: CharSequence, startIndex: Int = 0): Long {
        var hash = FNV_OFFSET_BASIS
        var i = startIndex
        val end = domain.length
        while (i < end) {
            val c = domain[i]
            if (c.code < 0x80) {
                val b = if (c in 'A'..'Z') c.code + 32 else c.code
                hash = (hash xor b.toLong()) * FNV_PRIME
            } else {
                // Rare: DNS names on the wire are punycode, so this only shows up
                // for hand-written policy entries. Hash the UTF-8 encoding so the
                // compiler and the lookup agree.
                val codePoint = if (Character.isHighSurrogate(c) && i + 1 < end &&
                    Character.isLowSurrogate(domain[i + 1])
                ) {
                    Character.toCodePoint(c, domain[++i])
                } else {
                    c.code
                }
                for (b in utf8Bytes(Character.toLowerCase(codePoint))) {
                    hash = (hash xor (b.toLong() and 0xFF)) * FNV_PRIME
                }
            }
            i++
        }
        return hash
    }

    private fun utf8Bytes(codePoint: Int): ByteArray =
        String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
}
