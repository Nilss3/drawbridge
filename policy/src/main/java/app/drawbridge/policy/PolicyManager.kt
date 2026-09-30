package app.drawbridge.policy

import android.content.Context
import android.util.Log
import app.drawbridge.policy.model.Policy
import app.drawbridge.policy.model.PolicyOption
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
     * The active policy, with the selected profile and the switched-on options
     * already applied. Emits a new value whenever a policy is installed, the
     * profile changes or an option is toggled, so every consumer — DNS filter,
     * app blocker, restrictions — sees the effective policy without knowing that
     * either profiles or options exist.
     */
    val policy: StateFlow<Policy> = _policy.asStateFlow()

    /** The policy as published, before any profile is applied. */
    @Volatile
    private var baseline: Policy = Policy(version = 0)

    /** The profiles the current policy offers, in document order. */
    val profiles: List<Profile> get() = baseline.profiles

    /** The profile in force, or null when the policy defines none. */
    val selectedProfile: Profile? get() = baseline.profileFor(selection().first)

    /** The options the current policy offers, in document order. */
    val options: List<PolicyOption> get() = baseline.options

    /** Which of [options] are switched on right now. */
    val enabledOptionIds: Set<String> get() = resolvedOptionIds()

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
    /**
     * @param userInitiated true when a person pressed a button, which asks
     *   intermediaries to skip their cache. The scheduled poll leaves it false:
     *   it runs every few hours, so a five-minute-stale document costs nothing,
     *   and defeating the cache on every device on every poll would not.
     */
    suspend fun refresh(userInitiated: Boolean = false): RefreshOutcome = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val downloader = Downloader(
                connectTimeoutMillis = config.connectTimeoutMillis,
                readTimeoutMillis = config.readTimeoutMillis,
                maxBytes = config.maxDownloadBytes,
            )
            val now = System.currentTimeMillis()
            val previous = store.readState()

            try {
                val envelopeJson = downloader.getText(config.policyUrl, noCache = userInitiated)
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

    /**
     * Switches one policy option on or off and re-applies the policy.
     *
     * Unlike a profile switch this changes no blocklist, so there is nothing to
     * download and nothing that has to happen before the new rules take effect.
     * It also cannot make the device stricter — an option only ever widens what
     * is allowed — so unlike [selectProfile] it needs no confirmation and no
     * app sweep. Returns false if the policy does not offer [optionId].
     */
    suspend fun setOptionEnabled(optionId: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (baseline.options.none { it.id == optionId }) return@withContext false

            refreshMutex.withLock {
                val state = store.readState()
                val current = resolvedOptionIds()
                val next = if (enabled) current + optionId else current - optionId
                // The seen set moves with the decision: an id the parent has
                // just switched is an id they have been offered, and writing it
                // here means the next refresh cannot read the switch-off as a
                // fresh arrival and undo it.
                store.writeState(
                    state.copy(
                        optionIds = next.toList(),
                        seenOptionIds = (state.seenOptionIds.orEmpty() + baseline.offeredOptionIds())
                            .distinct(),
                    ),
                )
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

    /**
     * Re-reads the selection and re-applies the policy without fetching
     * anything.
     *
     * For an app whose selection lives elsewhere: drawbridge changes a switch,
     * herald hears about it, and this is what turns that into a new filter. A
     * no-op before the first load, because there would be no baseline to apply
     * it to.
     */
    suspend fun refreshSelection() = withContext(Dispatchers.IO) {
        if (!loaded) return@withContext
        refreshMutex.withLock { applyPolicy(baseline) }
    }

    /**
     * The profile and options in force, from whichever app owns that decision.
     *
     * An unreadable external source falls back to this app's own stored state
     * rather than to nothing, so a browser that momentarily cannot reach
     * drawbridge keeps filtering on the document's defaults instead of losing
     * its rules.
     */
    private fun selection(): Pair<String?, List<String>?> {
        config.selectionSource?.read()?.let { return it.profileId to it.optionIds }
        val state = store.readState()
        return state.profileId to state.optionIds
    }

    /**
     * The enabled option set, from whichever source owns the decision.
     *
     * **The seen-set fallback applies only to a device that owns its own
     * selection.** When a [config.selectionSource] answers — herald reading
     * drawbridge's provider — the list it hands over is already *resolved* by
     * the app that owns the switches, and filling defaults into it again would
     * be the browser second-guessing the DPC. That is the divergence
     * `SelectionProvider` exists to prevent: an option the parent had switched
     * off would come back on in the browser and nowhere else.
     */
    private fun resolvedOptionIds(): Set<String> {
        config.selectionSource?.read()?.let { return baseline.enabledOptionIds(it.optionIds) }
        val state = store.readState()
        return baseline.enabledOptionIds(state.optionIds, state.seenOptionIds)
    }

    /**
     * The build a conditional fragment should be judged against.
     *
     * drawbridge's own, when drawbridge is asking. The *owner's*, published
     * through the selection, when herald is — because a fragment gated on
     * drawbridge 53 means nothing measured against herald's numbering, and the
     * two apps disagreeing about what is blocked is the failure `SelectionSource`
     * exists to prevent.
     */
    private fun conditionalVersionCode(): Int =
        config.selectionSource?.read()?.ownerVersionCode ?: config.ownVersionCode

    /**
     * Records that this document's options have now been offered to the device.
     *
     * Written after the selection has been resolved against the *previous* seen
     * set, never before: doing it first would mark an option as offered in the
     * same breath as deciding what it should default to, which is the one
     * ordering that makes the whole mechanism a no-op.
     *
     * Skipped when a [config.selectionSource] owns the selection, because then
     * this store is not the one the answer comes from.
     */
    private fun rememberOfferedOptions(published: Policy) {
        if (config.selectionSource != null) return
        val state = store.readState()
        val offered = state.seenOptionIds.orEmpty().toSet() + published.offeredOptionIds()
        if (offered.size == state.seenOptionIds?.size) return
        store.writeState(state.copy(seenOptionIds = offered.toList()))
    }

    private fun applyPolicy(published: Policy) {
        baseline = published
        val (profileId, _) = selection()
        val enabled = resolvedOptionIds()
        rememberOfferedOptions(published)
        val policy = published.effective(profileId, enabled, conditionalVersionCode())
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
