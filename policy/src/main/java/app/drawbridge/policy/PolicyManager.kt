package app.drawbridge.policy

import android.content.Context
import android.util.Log
import app.drawbridge.policy.model.Policy
import app.drawbridge.policy.model.Profile
import app.drawbridge.policy.net.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * The entry point both apps use. Holds the active policy and answers blocking
 * questions against the currently compiled blocklist.
 *
 * Blocking questions go through [isHostBlocked] / [isUrlBlocked] rather than
 * handing out the [ContentFilter] itself: the filter owns a memory-mapped file
 * that has to be closed when a new blocklist is compiled, and routing every
 * query through the lock here means a swap can never pull the mapping out from
 * under an in-flight lookup.
 */
class PolicyManager private constructor(
    context: Context,
    private val config: PolicyConfig,
) {

    private val appContext = context.applicationContext
    private val store = PolicyStore(appContext, config)
    private val refreshMutex = Mutex()

    private val filterLock = ReentrantReadWriteLock()
    private var filter: ContentFilter = ContentFilter.PERMISSIVE

    private val _policy = MutableStateFlow(Policy(version = 0))

    /**
     * The active policy, with the selected profile already applied. Emits a new
     * value whenever one is installed or the profile changes, so every consumer
     * — DNS filter, app blocker, restrictions — sees the effective policy
     * without knowing profiles exist.
     */
    val policy: StateFlow<Policy> = _policy.asStateFlow()

    /** The policy as published, before any profile is applied. */
    @Volatile
    private var baseline: Policy = Policy(version = 0)

    /** The profiles the current policy offers, in document order. */
    val profiles: List<Profile> get() = baseline.profiles

    /** The profile in force, or null when the policy defines none. */
    val selectedProfile: Profile? get() = baseline.profileFor(store.readState().profileId)

    private val _filterChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Emits whenever the active filter is replaced. Consumers that cache blocking
     * decisions — herald's web extension does — must drop those caches here,
     * otherwise a policy update would not take effect until the process restarts.
     */
    val filterChanges: SharedFlow<Unit> = _filterChanges.asSharedFlow()

    @Volatile
    private var loaded = false

    /**
     * Loads whatever is already on disk, falling back to the copy bundled in the
     * APK. Cheap and idempotent; safe to call from `Application.onCreate`.
     */
    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        refreshMutex.withLock {
            if (loaded) return@withLock
            val installed = store.loadInstalledPolicy()
            val active = installed ?: store.loadBundledPolicy()
            if (active == null) {
                Log.e(TAG, "No usable policy; running with defaults until the next poll")
            }
            if (installed == null && store.openBlocklist() == null) {
                store.compileBundledBlocklist()
            }
            applyPolicy(active ?: Policy(version = 0))
            loaded = true
        }
    }

    /**
     * Fetches the policy document, verifies it, syncs the blocklists it names
     * and swaps everything in. Safe to call concurrently; overlapping calls are
     * serialised.
     */
    suspend fun refresh(): RefreshOutcome = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val downloader = Downloader(
                connectTimeoutMillis = config.connectTimeoutMillis,
                readTimeoutMillis = config.readTimeoutMillis,
                maxBytes = config.maxDownloadBytes,
            )
            val now = System.currentTimeMillis()
            val previous = store.readState()

            try {
                val envelopeJson = downloader.getText(config.policyUrl)
                val currentVersion = _policy.value.version

                // Verify the signature first, then decide about the version, so
                // that "same version as we already have" reads as the normal
                // steady state rather than as a rollback attempt.
                val verified = store.verify(envelopeJson, minimumVersion = 0)
                if (verified.policy.version < currentVersion) {
                    throw app.drawbridge.policy.crypto.PolicyVerificationException(
                        "Served policy version ${verified.policy.version} is older than the " +
                            "installed version $currentVersion",
                    )
                }

                val policyUpdated = verified.policy.version > currentVersion
                if (policyUpdated) store.install(verified)

                val active = if (policyUpdated) verified.policy else _policy.value
                val blocklistChanged = store.syncBlocklists(active.blocklists, downloader)

                if (policyUpdated || blocklistChanged) {
                    applyPolicy(active)
                }

                store.writeState(
                    previous.copy(lastCheckMillis = now, lastSuccessMillis = now, lastError = null),
                )
                RefreshOutcome.Success(
                    policyUpdated = policyUpdated,
                    blocklistUpdated = blocklistChanged,
                    version = active.version,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Policy refresh failed", e)
                store.writeState(previous.copy(lastCheckMillis = now, lastError = e.message))
                RefreshOutcome.Failure(e)
            }
        }
    }

    /**
     * Switches to [profileId] and brings the device in line with it.
     *
     * The blocklists a profile names may not be on disk yet, so this syncs them
     * before swapping the filter over; a stricter profile that applied its app
     * rules while still filtering on the looser profile's lists would be worse
     * than useless. Returns false only if the id is not one this policy offers.
     */
    suspend fun selectProfile(profileId: String?): Boolean = withContext(Dispatchers.IO) {
        if (profileId != null && baseline.profiles.none { it.id == profileId }) {
            return@withContext false
        }

        refreshMutex.withLock {
            store.writeState(store.readState().copy(profileId = profileId))

            val effective = baseline.withProfile(profileId)
            runCatching {
                store.syncBlocklists(
                    effective.blocklists,
                    Downloader(
                        connectTimeoutMillis = config.connectTimeoutMillis,
                        readTimeoutMillis = config.readTimeoutMillis,
                        maxBytes = config.maxDownloadBytes,
                    ),
                )
            }.onFailure { Log.w(TAG, "Could not sync blocklists for profile $profileId", it) }

            applyPolicy(baseline)
        }
        true
    }

    fun isHostBlocked(host: String): Boolean = filterLock.read { filter.isHostBlocked(host) }

    fun isUrlBlocked(url: String): Boolean = filterLock.read { filter.isUrlBlocked(url) }

    /** Timestamps for the "last checked" line in each app's status screen. */
    fun state(): PolicyStore.StoredState = store.readState()

    /** Drops all policy state. Part of the sanctioned removal flow. */
    fun clear() {
        filterLock.write {
            filter.close()
            filter = ContentFilter.PERMISSIVE
        }
        store.clear()
        loaded = false
        _policy.value = Policy(version = 0)
    }

    private fun applyPolicy(published: Policy) {
        baseline = published
        val policy = published.withProfile(store.readState().profileId)
        val compiled = store.openBlocklist()
        val next = ContentFilter.create(
            compiledBlocklist = compiled,
            extraBlockedDomains = policy.blockedDomains,
            allowedDomains = policy.allowedDomains,
            browser = policy.browser,
        )
        filterLock.write {
            val previous = filter
            filter = next
            if (previous !== ContentFilter.PERMISSIVE) previous.close()
        }
        _policy.value = policy
        _filterChanges.tryEmit(Unit)
    }

    sealed interface RefreshOutcome {
        data class Success(
            val policyUpdated: Boolean,
            val blocklistUpdated: Boolean,
            val version: Int,
        ) : RefreshOutcome

        data class Failure(val cause: Throwable) : RefreshOutcome
    }

    companion object {
        private const val TAG = "PolicyManager"

        @Volatile
        private var instance: PolicyManager? = null

        /**
         * One instance per process. The DNS filter service, the policy worker and
         * the UI all have to see the same compiled blocklist.
         */
        fun getInstance(context: Context, config: PolicyConfig = PolicyConfig()): PolicyManager =
            instance ?: synchronized(this) {
                instance ?: PolicyManager(context, config).also { instance = it }
            }
    }
}
