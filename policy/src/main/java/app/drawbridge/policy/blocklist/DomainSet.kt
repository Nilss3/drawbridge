package app.drawbridge.policy.blocklist

import java.io.Closeable
import java.nio.LongBuffer

/**
 * An immutable set of domains stored as a sorted array of [DomainHash] values.
 *
 * [matches] implements suffix matching: an entry for `example.com` covers
 * `www.example.com` and `cdn.a.example.com`, which is how every mainstream
 * domain blocklist expects to be interpreted.
 */
abstract class DomainSet {

    abstract val size: Int

    /** The hash at [index]; entries are sorted ascending. */
    protected abstract fun hashAt(index: Int): Long

    open fun containsHash(hash: Long): Boolean {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val value = hashAt(mid)
            when {
                value < hash -> low = mid + 1
                value > hash -> high = mid - 1
                else -> return true
            }
        }
        return false
    }

    /**
     * True if [domain] or any of its parent domains is in the set.
     *
     * The walk stops before the final label, so a stray single-label entry such
     * as `com` in a badly-built list cannot black-hole the entire TLD.
     */
    open fun matches(domain: String): Boolean {
        if (size == 0 || domain.isEmpty()) return false
        val normalised = normalise(domain)
        if (normalised.isEmpty()) return false

        var start = 0
        while (true) {
            if (containsHash(DomainHash.of(normalised, start))) return true
            val dot = normalised.indexOf('.', start)
            if (dot < 0) return false
            val next = dot + 1
            // Stop once only the public suffix label would remain.
            if (normalised.indexOf('.', next) < 0) return false
            start = next
        }
    }

    companion object {
        val EMPTY: DomainSet = ArrayDomainSet(LongArray(0))

        /** Lowercases, strips a trailing root dot and a leading wildcard label. */
        fun normalise(domain: String): String {
            var value = domain.trim().lowercase()
            if (value.startsWith("*.")) value = value.substring(2)
            while (value.endsWith(".")) value = value.dropLast(1)
            while (value.startsWith(".")) value = value.substring(1)
            return value
        }

        /** Builds a set from raw domain strings, sorting and de-duplicating. */
        fun of(domains: Collection<String>): DomainSet {
            if (domains.isEmpty()) return EMPTY
            val hashes = LongArray(domains.size)
            var n = 0
            for (domain in domains) {
                val normalised = normalise(domain)
                if (normalised.isEmpty()) continue
                hashes[n++] = DomainHash.of(normalised)
            }
            return ArrayDomainSet(sortedDistinct(hashes, n))
        }

        internal fun sortedDistinct(hashes: LongArray, count: Int): LongArray {
            if (count == 0) return LongArray(0)
            val sorted = hashes.copyOf(count)
            sorted.sort()
            var out = 1
            for (i in 1 until count) {
                if (sorted[i] != sorted[out - 1]) sorted[out++] = sorted[i]
            }
            return if (out == sorted.size) sorted else sorted.copyOf(out)
        }
    }
}

/** Heap-backed set, used for the handful of domains inlined in the policy JSON. */
class ArrayDomainSet(private val hashes: LongArray) : DomainSet() {
    override val size: Int get() = hashes.size
    override fun hashAt(index: Int): Long = hashes[index]
}

/**
 * Set backed by a memory-mapped [BlocklistFile]. The compiled list stays in the
 * page cache instead of the app heap, so a million-domain list costs the process
 * essentially nothing resident.
 */
class MappedDomainSet internal constructor(
    private val buffer: LongBuffer,
    private val closeable: Closeable?,
) : DomainSet(), Closeable {

    override val size: Int get() = buffer.limit()

    override fun hashAt(index: Int): Long = buffer.get(index)

    override fun close() {
        closeable?.close()
    }
}

/** Union of several sets; a domain is in the union if any member matches. */
class CompositeDomainSet(private val members: List<DomainSet>) : DomainSet(), Closeable {

    override val size: Int get() = members.sumOf { it.size }

    override fun hashAt(index: Int): Long =
        throw UnsupportedOperationException("CompositeDomainSet is not indexable")

    fun isEmpty(): Boolean = members.all { it.size == 0 }

    override fun containsHash(hash: Long): Boolean = members.any { it.containsHash(hash) }

    override fun matches(domain: String): Boolean = members.any { it.matches(domain) }

    override fun close() {
        members.filterIsInstance<Closeable>().forEach(Closeable::close)
    }
}
