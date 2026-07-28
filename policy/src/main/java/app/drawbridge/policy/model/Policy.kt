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
     * The one browser allowed to exist on a managed device. Any other package
     * that registers a browser intent filter is uninstalled or suspended.
     */
    @SerialName("allowed_browser_package")
    val allowedBrowserPackage: String = DEFAULT_BROWSER_PACKAGE,

    /**
     * Packages exempt from browser allowlisting and package blocking — an
     * escape valve for a device-specific app that would otherwise be removed.
     */
    @SerialName("exempt_packages")
    val exemptPackages: List<String> = emptyList(),

    val browser: BrowserPolicy = BrowserPolicy(),

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
) {
    companion object {
        const val DEFAULT_BROWSER_PACKAGE = "app.drawbridge.herald"
    }
}

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
     * are absent rather than merely unselected.
     */
    @SerialName("search_engines")
    val searchEngines: List<String> = listOf(
        "DuckDuckGo",
        "Google",
        "Bing",
        "Brave Search",
        "Qwant",
        "Ecosia",
        "Startpage",
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
