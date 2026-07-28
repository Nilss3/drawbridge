package app.drawbridge.policy

import android.content.Context
import android.util.Log
import app.drawbridge.policy.blocklist.BlocklistBuilder
import app.drawbridge.policy.blocklist.BlocklistFile
import app.drawbridge.policy.blocklist.DomainSet
import app.drawbridge.policy.crypto.PolicyVerifier
import app.drawbridge.policy.crypto.VerifiedPolicy
import app.drawbridge.policy.model.BlocklistFormat
import app.drawbridge.policy.model.BlocklistSource
import app.drawbridge.policy.model.Policy
import app.drawbridge.policy.net.Downloader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * Owns everything policy-related on disk: the signed envelope, the cached
 * blocklist sources, and the compiled blocklist the filters memory-map.
 *
 * All state lives under a single directory so the DPC's "remove parental
 * controls" path can wipe it in one call.
 */
class PolicyStore(context: Context, private val config: PolicyConfig) {

    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, DIRECTORY_NAME).apply { mkdirs() }
    private val listCacheDir = File(root, "lists").apply { mkdirs() }

    private val envelopeFile = File(root, "policy.signed.json")
    private val blocklistFile = File(root, "blocklist.bin")
    private val stateFile = File(root, "state.json")

    val compiledBlocklistFile: File get() = blocklistFile

    private val verifier: PolicyVerifier by lazy {
        PolicyVerifier.fromTrustedKeySet(readAsset(config.trustedKeysAsset))
    }

    /** The stored policy, or null when nothing valid has been installed yet. */
    fun loadInstalledPolicy(): Policy? {
        if (!envelopeFile.exists()) return null
        return try {
            // Re-verified on every load, not just at install time: a stored file is
            // only as trustworthy as the sandbox around it.
            verifier.verify(envelopeFile.readText(), minimumVersion = 0).policy
        } catch (e: Exception) {
            Log.e(TAG, "Stored policy failed verification, discarding it", e)
            envelopeFile.delete()
            null
        }
    }

    /** The policy shipped inside the APK, used until the first successful poll. */
    fun loadBundledPolicy(): Policy? = try {
        verifier.verify(readAsset(config.bundledPolicyAsset), minimumVersion = 0).policy
    } catch (e: Exception) {
        Log.e(TAG, "Bundled policy asset failed verification", e)
        null
    }

    /** Opens the compiled blocklist, or null if none has been built yet. */
    fun openBlocklist(): DomainSet? {
        if (!blocklistFile.exists()) return null
        return try {
            BlocklistFile.open(blocklistFile)
        } catch (e: IOException) {
            Log.e(TAG, "Compiled blocklist is unusable, rebuilding on next poll", e)
            blocklistFile.delete()
            null
        }
    }

    /**
     * Compiles the seed list bundled in the APK. Runs once on first launch so a
     * device is filtered before it has ever reached the network.
     */
    fun compileBundledBlocklist(): Boolean {
        val asset = config.bundledBlocklistAsset ?: return false
        return try {
            val builder = BlocklistBuilder()
            appContext.assets.open(asset).bufferedReader().use { reader ->
                builder.addSource(reader, BlocklistFormat.DOMAINS)
            }
            builder.writeTo(blocklistFile)
            Log.i(TAG, "Compiled bundled blocklist: ${builder.acceptedLines} domains")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not compile bundled blocklist", e)
            false
        }
    }

    /** Persists a verified envelope as the installed policy. */
    fun install(verified: VerifiedPolicy) {
        envelopeFile.writeText(verified.envelopeJson)
    }

    fun verify(envelopeJson: String, minimumVersion: Int): VerifiedPolicy =
        verifier.verify(envelopeJson, minimumVersion)

    /**
     * Makes the on-disk blocklist match [sources]: downloads anything missing or
     * stale, drops cache entries the policy no longer references, and recompiles
     * if anything changed.
     *
     * A source whose download fails or whose checksum is wrong is skipped rather
     * than failing the whole update — losing one list is better than losing the
     * filter.
     *
     * @return true if the compiled blocklist was rewritten.
     */
    fun syncBlocklists(sources: List<BlocklistSource>, downloader: Downloader): Boolean {
        val wanted = sources.associateBy { it.id }
        var changed = false

        listCacheDir.listFiles()?.forEach { cached ->
            if (cached.nameWithoutExtension !in wanted) {
                cached.delete()
                changed = true
            }
        }

        val usable = mutableListOf<Pair<BlocklistSource, File>>()
        for (source in sources) {
            val cached = File(listCacheDir, "${source.id}.list")
            val expected = source.sha256?.lowercase()

            if (cached.exists()) {
                val stale = if (expected != null) {
                    sha256OfFile(cached) != expected
                } else {
                    // Unpinned lists rotate upstream, so re-fetch them once a day
                    // instead of trusting the cache indefinitely.
                    System.currentTimeMillis() - cached.lastModified() > UNPINNED_LIST_MAX_AGE_MILLIS
                }
                if (!stale) {
                    usable += source to cached
                    continue
                }
            }

            try {
                val actual = downloader.getToFile(source.url, cached)
                if (expected != null && actual != expected) {
                    cached.delete()
                    Log.e(TAG, "Blocklist '${source.id}' checksum mismatch; expected $expected, got $actual")
                    continue
                }
                changed = true
                usable += source to cached
            } catch (e: Exception) {
                Log.e(TAG, "Could not download blocklist '${source.id}'", e)
                // Fall back to a stale copy rather than dropping the list entirely.
                if (cached.exists()) usable += source to cached
            }
        }

        if (!changed && blocklistFile.exists()) return false
        if (usable.isEmpty() && sources.isNotEmpty()) {
            Log.e(TAG, "No blocklist sources are usable; keeping the previous compiled list")
            return false
        }

        val builder = BlocklistBuilder()
        for ((source, file) in usable) {
            try {
                file.bufferedReader().use { builder.addSource(it, source.format) }
            } catch (e: IOException) {
                Log.e(TAG, "Could not read cached blocklist '${source.id}'", e)
            }
        }
        builder.writeTo(blocklistFile)
        Log.i(TAG, "Compiled ${usable.size} blocklists into ${builder.acceptedLines} domains")
        return true
    }

    fun readState(): StoredState = try {
        if (stateFile.exists()) JSON.decodeFromString(stateFile.readText()) else StoredState()
    } catch (e: Exception) {
        Log.e(TAG, "Could not read policy state", e)
        StoredState()
    }

    fun writeState(state: StoredState) {
        try {
            stateFile.writeText(JSON.encodeToString(state))
        } catch (e: IOException) {
            Log.e(TAG, "Could not write policy state", e)
        }
    }

    /** Removes every trace of policy state. Used by the sanctioned uninstall path. */
    fun clear() {
        root.deleteRecursively()
        root.mkdirs()
        listCacheDir.mkdirs()
    }

    private fun readAsset(path: String): String =
        appContext.assets.open(path).bufferedReader().use { it.readText() }

    private fun sha256OfFile(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Serializable
    data class StoredState(
        val lastCheckMillis: Long = 0,
        val lastSuccessMillis: Long = 0,
        val lastError: String? = null,
    )

    companion object {
        private const val TAG = "PolicyStore"
        const val DIRECTORY_NAME = "drawbridge-policy"

        private const val UNPINNED_LIST_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
