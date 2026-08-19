package app.drawbridge.policy.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The remotely-updatable policy document, shared by the herald browser and the
 * drawbridge DPC. It is distributed as a signed envelope (see
 * [app.drawbridge.policy.crypto.SignedEnvelope]) from a static HTTPS URL, so
 * updating policy is "edit the JSON, sign it, push it" with no backend and no
 * account.
 *
 * Unknown fields are ignored when parsing, so newer policy documents stay
 * readable by older installs.
 */
@Serializable
data class Policy(
    /**
     * Monotonically increasing. An install refuses to apply a policy whose
     * version is lower than the one it already holds, which is what makes
     * rollback attacks (replaying an old, permissive policy) fail.
     */
    val version: Int,

    @SerialName("issued_at")
    val issuedAt: String? = null,

    /** Human-readable note; purely informational. */
    val comment: String? = null,

    val dns: DnsPolicy = DnsPolicy(),

    /** Downloadable domain lists, compiled into the on-device blocklist. */
    val blocklists: List<BlocklistSource> = emptyList(),

    /** Extra domains blocked on top of [blocklists]. */
    @SerialName("blocked_domains")
    val blockedDomains: List<String> = emptyList(),

    /** Domains that always resolve, even if a blocklist covers them. */
    @SerialName("allowed_domains")
    val allowedDomains: List<String> = emptyList(),

    /** Package names the DPC removes on sight. */
    @SerialName("blocked_packages")
    val blockedPackages: List<String> = emptyList(),

    /**
     * The browser tapped links are handed to, and the one drawbridge installs
     * first. Also the fallback member of [browserPackages] when that is empty.
     */
    @SerialName("allowed_browser_package")
    val allowedBrowserPackage: String = DEFAULT_BROWSER_PACKAGE,

    /**
     * Every browser allowed to exist on a managed device. Any other package that
     * registers a browser intent filter is uninstalled or suspended.
     *
     * This started as a single package, and the difference matters: `required_
     * apps` and the app blocker have to agree, or drawbridge installs a browser
     * and then removes it again as a rogue one, on a loop. Shipping both herald
     * editions means naming both here *and* in `required_apps`, and leaving one
     * out of either list is the failure that loop comes from.
     *
     * Empty means "just [allowedBrowserPackage]", which is what every policy
     * written before this field existed says.
     */
    @SerialName("allowed_browser_packages")
    val allowedBrowserPackages: List<String> = emptyList(),

    /**
     * When set, app control flips from "remove what is listed" to "remove what
     * is not listed": any *user-installed* package outside this list is removed.
     *
     * Preinstalled and system apps are deliberately left alone — the phone still
     * needs its camera, dialer and keyboard, and there is no reliable way to tell
     * which of an OEM's preinstalls are load-bearing. Those are governed by
     * [blockedPackages] as before, which hides rather than uninstalls them.
     *
     * `null` keeps the ordinary blocklist behaviour.
     */
    @SerialName("allowed_packages")
    val allowedPackages: List<String>? = null,

    /**
     * Packages exempt from browser allowlisting and package blocking — an
     * escape valve for a device-specific app that would otherwise be removed.
     */
    @SerialName("exempt_packages")
    val exemptPackages: List<String> = emptyList(),

    /**
     * Named variants of this policy, chosen on the device behind the parent's
     * PIN. Each one overrides the fields it sets and inherits the rest.
     *
     * Switching is not symmetric: a stricter profile removes apps it does not
     * allow, and switching back does not bring them back. See [Profile].
     */
    val profiles: List<Profile> = emptyList(),

    /** Which of [profiles] applies until someone chooses otherwise. */
    @SerialName("default_profile")
    val defaultProfile: String? = null,

    /**
     * Individual relaxations the parent can switch on, on top of whichever
     * profile is running. See [PolicyOption].
     */
    val options: List<PolicyOption> = emptyList(),

    val browser: BrowserPolicy = BrowserPolicy(),

    /**
     * A nightly window with no internet at all. Null means none, which is the
     * shipped state — see [Curfew], which is drafted but not yet enforced.
     */
    val curfew: Curfew? = null,

    @SerialName("app_update")
    val appUpdate: AppUpdate? = null,

    /**
     * Apps drawbridge installs itself if they are missing — herald above all.
     *
     * Without this a QR-provisioned device is a dead end: drawbridge removes or
     * hides every browser the moment it takes over, so there is nothing left to
     * download herald *with*. Being Device Owner, it can install silently, which
     * turns provisioning into a single scan.
     */
    @SerialName("required_apps")
    val requiredApps: List<AppUpdate> = emptyList(),

    /**
     * Admitting apps by the store's own rating and category, rather than only by
     * a hand-written list of package names. Null on a document that predates it,
     * which disables the rule rather than defaulting it — see [AppRatings].
     */
    @SerialName("app_ratings")
    val appRatings: AppRatings? = null,
) {
    /**
     * Every browser this policy permits, with [allowedBrowserPackage] always a
     * member. Ask this rather than either field: the app blocker removing a
     * browser the installer is about to put back is the whole hazard here.
     */
    val browserPackages: Set<String>
        get() = buildSet {
            add(allowedBrowserPackage)
            addAll(allowedBrowserPackages)
        }

    companion object {
        const val DEFAULT_BROWSER_PACKAGE = "app.drawbridge.herald"
    }

    /** Which profile is in force, given a device's selection. */
    fun profileFor(selectedId: String?): Profile? {
        val wanted = selectedId ?: defaultProfile ?: return null
        return profiles.firstOrNull { it.id == wanted }
    }

    /**
     * This policy with [selectedId]'s overrides applied. Unknown or absent
     * selections fall back to [defaultProfile], and then to the policy as
     * written, so a device holding a selection the policy has since dropped
     * degrades to the base rather than to nothing.
     */
    fun withProfile(selectedId: String?): Policy {
        val profile = profileFor(selectedId) ?: return this
        return copy(
            dns = profile.dns ?: dns,
            blocklists = profile.blocklists ?: blocklists,
            blockedDomains = profile.blockedDomains ?: blockedDomains,
            allowedDomains = profile.allowedDomains ?: allowedDomains,
            blockedPackages = profile.blockedPackages ?: blockedPackages,
            allowedPackages = profile.allowedPackages ?: allowedPackages,
            exemptPackages = profile.exemptPackages ?: exemptPackages,
            curfew = profile.curfew ?: curfew,
        )
    }

    /**
     * Which options are on, given what the device has stored.
     *
     * `null` means nobody has chosen yet, so the policy's own defaults apply.
     * A stored id the policy no longer offers is dropped, which is what stops a
     * relaxation outliving the option that justified it.
     */
    fun enabledOptionIds(stored: List<String>?): Set<String> {
        val known = options.map { it.id }.toSet()
        return stored?.filterTo(mutableSetOf()) { it in known }
            ?: options.filter { it.defaultEnabled }.mapTo(mutableSetOf()) { it.id }
    }

    /**
     * This policy with [enabledIds] applied on top.
     *
     * Options only ever *add* permission, never take it away, so they can be
     * merged into whatever the profile left behind without having to reason
     * about the order the two were applied in. Anything an option would have to
     * un-block belongs in a second profile instead.
     */
    fun withOptions(enabledIds: Set<String>): Policy {
        val enabled = options.filter { it.id in enabledIds }
        if (enabled.isEmpty()) return this
        return copy(
            exemptPackages = (exemptPackages + enabled.flatMap { it.exemptPackages }).distinct(),
            allowedDomains = (allowedDomains + enabled.flatMap { it.allowedDomains }).distinct(),
            // Only in allowlist mode: adding names to a list that is null would
            // switch allowlisting *on*, and an option that quietly started
            // uninstalling everything unlisted would be the opposite of a
            // relaxation.
            allowedPackages = allowedPackages?.let { base ->
                (base + enabled.flatMap { it.allowedPackages }).distinct()
            },
        )
    }

    /** The policy as it actually applies on a device: profile first, then options. */
    fun effective(selectedProfileId: String?, enabledOptionIds: Set<String>): Policy =
        withProfile(selectedProfileId).withOptions(enabledOptionIds)
}

/**
 * One thing the parent can allow, on top of whichever profile is running.
 *
 * Options exist because the choice a parent actually wants to make is rarely
 * "strict or relaxed" but "everything as it is, except this one app my child's
 * class group runs on". Expressing that as a second profile means maintaining
 * two near-identical copies of the policy that drift; expressing it as an option
 * keeps one policy and a switch.
 *
 * An option can only widen what is permitted — it exempts packages from removal
 * and names domains that must resolve. It cannot block anything, which is what
 * makes the order of application irrelevant and the effect of turning one off
 * easy to state: the base policy, unchanged.
 */
@Serializable
data class PolicyOption(
    val id: String,
    /** Shown as the switch's label, e.g. "Allow WhatsApp". */
    val name: String,
    val description: String = "",

    /** See [Profile.nameByLanguage]. */
    @SerialName("name_i18n")
    val nameByLanguage: Map<String, String> = emptyMap(),

    @SerialName("description_i18n")
    val descriptionByLanguage: Map<String, String> = emptyMap(),

    /**
     * The age this is usually reckoned suitable from, shown next to the name as
     * "14+". Advice, not enforcement: nothing on the device knows how old its
     * owner is, and the parent switching this on is the one who does.
     */
    @SerialName("recommended_age")
    val recommendedAge: Int? = null,

    /**
     * Marks an option that carries no single age because its content is not
     * sorted by one. The screen shows *various ages* on its shield instead of a
     * number.
     *
     * The streaming catalogue is the case it exists for: one switch covers fifty
     * services, and a service carries children's films and adult drama through
     * the same app. Any number printed on that would be a number somebody made
     * up.
     *
     * **The wording is deliberate and was changed on the way in.** The first
     * draft said *parental advisory*, which is a phrase the RIAA has a
     * registered mark on and a look people recognise; borrowing either the
     * words or the black-label styling invites a confusion nobody here wants,
     * and "various ages" says the true thing more plainly anyway.
     *
     * Ignored when [recommendedAge] is set, since an age is the more specific
     * statement of the two.
     */
    @SerialName("various_ages")
    val variousAges: Boolean = false,

    /** Whether this is on before anyone has been asked. */
    @SerialName("default_enabled")
    val defaultEnabled: Boolean = false,

    /**
     * Packages this option spares from the app blocker, including from
     * `blocked_packages`. This is the field that does the work for an option
     * that allows an app: the base policy blocks it, and the exemption wins.
     */
    @SerialName("exempt_packages")
    val exemptPackages: List<String> = emptyList(),

    /**
     * Packages added to the allowed set, and only when the running profile is in
     * allowlist mode. [exemptPackages] is what an option normally needs; this is
     * for the profile that names every app it permits.
     */
    @SerialName("allowed_packages")
    val allowedPackages: List<String> = emptyList(),

    /**
     * Domains that must resolve while this is on. An allowed app whose servers
     * are on a blocklist is an app that opens and then does nothing, so the
     * hosts belong here alongside the package.
     */
    @SerialName("allowed_domains")
    val allowedDomains: List<String> = emptyList(),
) {
    fun displayName(language: String): String = pick(name, nameByLanguage, language)

    fun displayDescription(language: String): String =
        pick(description, descriptionByLanguage, language)
}

/**
 * A named variant of the policy: "which apps may be installed, and which DNS is
 * used", plus the lists behind both.
 *
 * Every field is nullable and `null` means "inherit". Only the parts a variant
 * actually changes need naming, so a profile reads as a diff rather than as a
 * second copy of the policy that can drift.
 *
 * **Switching is one-way for apps.** A profile that no longer allows an app
 * causes the app blocker to remove it; choosing the looser profile again does
 * not reinstall it. That is a property of removal, not of profiles, and the UI
 * says so before applying.
 */
@Serializable
data class Profile(
    val id: String,

    /** The title in the picker, e.g. "Guarded". */
    val name: String,

    /**
     * One line under the title, read before the description is. Where [name]
     * says which profile this is, this says who it is for.
     */
    val subtitle: String = "",

    /** The paragraph under both, spelling out what the profile actually does. */
    val description: String = "",

    /**
     * The age this profile is usually reckoned suitable from, shown on a shield
     * beside its name — the same shield an option carries, for the same reason.
     *
     * It used to be written into [subtitle] as "(+14)", in three languages, which
     * meant the one number a parent compares profiles by was buried in the middle
     * of a sentence and spelled differently in each of them. Advice rather than
     * enforcement, exactly as [PolicyOption.recommendedAge] is.
     */
    @SerialName("recommended_age")
    val recommendedAge: Int? = null,

    /**
     * Translations of [name], keyed by two-letter language code.
     *
     * drawbridge's own screens are translated in the APK, but half of what the
     * profile picker shows comes from this document rather than from a string
     * resource — so without these, switching the app to Dutch left the profile
     * and its description in English, which is worse than not offering the
     * switch. The untranslated field stays the fallback, so an older install
     * reading a newer policy sees English rather than nothing.
     */
    @SerialName("name_i18n")
    val nameByLanguage: Map<String, String> = emptyMap(),

    @SerialName("subtitle_i18n")
    val subtitleByLanguage: Map<String, String> = emptyMap(),

    @SerialName("description_i18n")
    val descriptionByLanguage: Map<String, String> = emptyMap(),

    val dns: DnsPolicy? = null,
    val blocklists: List<BlocklistSource>? = null,

    @SerialName("blocked_domains")
    val blockedDomains: List<String>? = null,

    @SerialName("allowed_domains")
    val allowedDomains: List<String>? = null,

    @SerialName("blocked_packages")
    val blockedPackages: List<String>? = null,

    /** Non-null switches this profile to allowlist mode; see [Policy.allowedPackages]. */
    @SerialName("allowed_packages")
    val allowedPackages: List<String>? = null,

    @SerialName("exempt_packages")
    val exemptPackages: List<String>? = null,

    /**
     * Overrides the base [Policy.curfew]. Null inherits it, which means a
     * profile cannot currently *remove* a curfew the base policy sets — the same
     * shape as every other override here, where null is "inherit" rather than
     * "none".
     */
    val curfew: Curfew? = null,
) {
    fun displayName(language: String): String = pick(name, nameByLanguage, language)

    fun displaySubtitle(language: String): String = pick(subtitle, subtitleByLanguage, language)

    fun displayDescription(language: String): String =
        pick(description, descriptionByLanguage, language)
}

/**
 * The translation for [language], or the untranslated original.
 *
 * A missing translation falls back rather than blanking, which is the behaviour
 * that lets a policy add a language without every profile having to be
 * retranslated in the same edit.
 */
internal fun pick(base: String, variants: Map<String, String>, language: String): String =
    variants[language]?.takeIf { it.isNotBlank() } ?: base

@Serializable
data class DnsPolicy(
    /**
     * Plain-DNS resolvers queries are forwarded to once they pass the local
     * blocklist.
     *
     * Still required even when [dohUrl] is set: these bootstrap the DoH
     * endpoint's own hostname and take over if DoH is unreachable. Pick a
     * *filtering* resolver, so that losing DoH degrades to a narrower filter
     * rather than to an open one.
     */
    val upstreams: List<String> = listOf("94.140.14.15", "94.140.15.16"),

    /**
     * Optional DNS-over-TLS upstream, as `tls://host[:port]`. When set, queries
     * go here instead of to [upstreams] and the hop between device and resolver
     * is encrypted, so the local network can neither read the lookups nor forge
     * answers to unblock something.
     *
     * DoT rather than DoH: the resolvers worth using here serve DoH over HTTP/2
     * only, and Android's built-in HTTP client cannot speak it.
     *
     * Note this is only about *our own* upstream. Encrypted DNS attempted by any
     * other app on the device is still blackholed; see [blockEncryptedDns].
     */
    @SerialName("encrypted_upstream")
    val encryptedUpstream: String? = null,

    /** Rewrite Google/Bing/YouTube lookups to their safe-search hostnames. */
    @SerialName("enforce_safe_search")
    val enforceSafeSearch: Boolean = true,

    /**
     * Strip HTTPS/SVCB records from responses. These carry ECH keys; removing
     * them keeps TLS metadata inspectable if SNI-level filtering is ever added.
     */
    @SerialName("strip_https_records")
    val stripHttpsRecords: Boolean = true,

    /**
     * Blackhole known DoH/DoQ endpoints so no app can run its own encrypted
     * resolver and route around this filter.
     */
    @SerialName("block_encrypted_dns")
    val blockEncryptedDns: Boolean = true,

    /** TTL, in seconds, applied to synthesised NXDOMAIN/blocked answers. */
    @SerialName("blocked_response_ttl_seconds")
    val blockedResponseTtlSeconds: Int = 60,

    /**
     * Packages left outside the tunnel entirely, because the tunnel stops them
     * working at all.
     *
     * **Every name here is a hole in the filter**, and a deliberate one: an
     * excluded app resolves through the system resolver, so nothing it looks up
     * is checked against the blocklist. That is the whole cost, and it is why
     * this list is short, is spelled out in `docs/policy.md`, and should never
     * grow to hold a browser or anything that renders arbitrary web content.
     *
     * The default is Android Auto, which refuses to start wirelessly while a VPN
     * is present and says so — *"error 21, are you using a VPN?"* — on a car's
     * screen, every time the phone comes into range. It reaches nothing a child
     * can steer: it is a projection surface for other apps, which keep their own
     * network and stay filtered.
     *
     * **It is policy rather than a constant because drawbridge cannot update
     * itself** (see the handoff on Play Protect). An app that turns out to be
     * incompatible with an always-on VPN can be added to a signed policy and
     * reach a locked phone at its next poll; the same fix built into the DPC
     * needs a cable and somebody holding the key.
     */
    @SerialName("excluded_packages")
    val excludedPackages: List<String> = listOf("com.google.android.projection.gearhead"),
)

@Serializable
data class BrowserPolicy(
    /**
     * The engine selected until someone picks another in herald's settings.
     * Matched loosely against engine ids and names, so "duckduckgo" finds `ddg`.
     */
    @SerialName("default_search_engine")
    val defaultSearchEngine: String = "duckduckgo",

    /**
     * The engines herald offers at all. Everything else — including whatever the
     * phone's locale would otherwise bring in — is hidden.
     *
     * This is a filtering decision: safe search is forced by rewriting the
     * engine's hostname at the DNS layer, and only Google, Bing and DuckDuckGo
     * publish a hostname to rewrite to. The rest serve image results from their
     * own CDN, which no domain blocklist covers, which is why Yandex and Baidu
     * are absent rather than merely unselected. Kagi is here because it filters
     * when logged out and offers nothing to turn that off.
     *
     * **This default had gone stale, and it fails in the dangerous direction.**
     * It still named Brave Search, Qwant and Startpage — dropped on 2026-08-10
     * for safe search that cannot be forced — and Ecosia, dropped on 2026-08-15
     * because its parameter only ever worked inside herald. `SearchEngineCatalogue`
     * *hides* every engine this list does not name, so a stale name here is not
     * inert: on a phone whose policy omits the field, a locale that bundles one
     * of those four would have kept it, unforced. The four were removed from
     * every other list at the time and this one was missed both times.
     */
    @SerialName("search_engines")
    val searchEngines: List<String> = listOf(
        "DuckDuckGo",
        "Google",
        "Bing",
        "Kagi",
    ),

    /**
     * The heading on herald's block page, and the title of the tab showing it.
     *
     * Policy rather than a string resource so the wording can be changed without
     * shipping a new browser, the same as [blockedPageMessage]. Blank falls back
     * to herald's own string.
     */
    @SerialName("blocked_page_title")
    val blockedPageTitle: String = "Drawbridge opened",

    /** The line under the heading on herald's block page. */
    @SerialName("blocked_page_message")
    val blockedPageMessage: String =
        "This website was denied access to your device, life and soul.",

    /**
     * Finer-grained than DNS can be: regular expressions matched against the
     * full URL. Used for path-level rules such as a specific subreddit.
     */
    @SerialName("blocked_url_patterns")
    val blockedUrlPatterns: List<String> = emptyList(),

    /**
     * Hosts herald refuses to load even if DNS lets them through, expressed
     * separately from the shared domain blocklist so the standalone browser
     * build can ship browser-only rules.
     */
    @SerialName("blocked_hosts")
    val blockedHosts: List<String> = emptyList(),
)

@Serializable
data class BlocklistSource(
    val id: String,
    val url: String,
    /**
     * Lowercase hex SHA-256 of the file at [url]; downloads that don't match are
     * discarded.
     *
     * Optional, because the large upstream lists (HaGeZi, Block List Project)
     * are rebuilt daily and pinning them would mean re-signing the policy every
     * day. Pin the curated lists you control; leave the rotating ones unpinned
     * and rely on HTTPS plus the signed policy that names them.
     */
    val sha256: String? = null,
    val format: BlocklistFormat = BlocklistFormat.DOMAINS,
    /** Informational grouping: "adult", "gambling", "social", "ads", ... */
    val category: String? = null,
)

@Serializable
enum class BlocklistFormat {
    /** One domain per line, `#` comments. */
    @SerialName("domains")
    DOMAINS,

    /** `0.0.0.0 example.com` hosts-file syntax. */
    @SerialName("hosts")
    HOSTS,
}

@Serializable
data class AppUpdate(
    @SerialName("package_name")
    val packageName: String,
    @SerialName("version_code")
    val versionCode: Int,
    val url: String,
    val sha256: String,

    /**
     * ABI this build is for, e.g. `arm64-v8a`. Entries naming an ABI the device
     * does not support are skipped, which is how one policy can serve the
     * per-ABI splits GeckoView forces on herald. Null means "any device".
     */
    val abi: String? = null,
)
