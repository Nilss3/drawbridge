package app.drawbridge.herald.filter

/**
 * Turns YouTube Shorts back into ordinary videos.
 *
 * **A Short and a normal video are the same video.** `youtube.com/shorts/<id>`
 * and `youtube.com/watch?v=<id>` play the same file; what differs is the player
 * around it — a full-screen vertical feed that advances by itself when the clip
 * ends. The clip is not the problem. The feed that never stops is.
 *
 * So this rewrites rather than blocks, and the difference matters in both
 * directions. A Short somebody sends you still opens and still plays, in the
 * ordinary player, where it ends and stops. And the Shorts *entry point* —
 * `/shorts` with nothing after it — goes to the site root instead, so there is no
 * way to open the feed on purpose either.
 *
 * **Why not `blocked_url_patterns`, which already exists.** Two reasons, both
 * decisive. [app.drawbridge.policy.ContentFilter.isUrlBlocked] returns false as
 * soon as an allowed domain matches, and allowing YouTube is precisely what puts
 * `youtube.com` in `allowed_domains` — so a path pattern would never be
 * consulted in the only state where it would matter. And a blocked URL is
 * checked *before* any rewrite, so blocking `/shorts/` would pre-empt this and
 * turn a shared link into a wall.
 *
 * **What this does not reach**, and it should not be oversold: the YouTube app,
 * which the same option restores, and the four other browsers the policy allows.
 * Shorts are untouched in all of them. This makes herald a nicer place to watch
 * YouTube; it does not make the phone a place without Shorts.
 *
 * **Nor does it reach a tap inside YouTube itself.** GeckoView reports
 * navigations, and YouTube is a single-page app: tapping a Short in the feed is
 * a `history.pushState`, so nothing arrives here at all. Found on the reference
 * phone on 2026-08-13, where this looked simply broken. That case is covered by
 * `assets/extensions/blocklist/shorts.js`, a content script that polls
 * `location` — the two halves are deliberately separate, because this one is
 * exact and that one runs inside the page. Read that file's header before
 * changing it: the obvious implementation cannot work from a content script,
 * and shipped in 0.1.11 doing nothing at all.
 *
 * Deliberately built on raw string handling rather than `android.net.Uri`, for
 * the same two reasons as [app.drawbridge.herald.search.SafeSearch]: it keeps
 * this plain JVM code the test source set can exercise without Robolectric, and
 * it never decodes anything. A video id is passed through byte for byte.
 */
object Shorts {

    /**
     * The URL this one should have been, or null to leave it alone.
     *
     * Returning null for anything already rewritten is what stops a loop: the
     * `/watch` URL this produces is not a `/shorts` URL, so it comes back through
     * here once and is left alone.
     */
    fun redirected(url: String): String? {
        val scheme = when {
            url.startsWith("https://", ignoreCase = true) -> "https://"
            url.startsWith("http://", ignoreCase = true) -> "http://"
            else -> return null
        }

        val rest = url.substring(scheme.length)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        if (!isYouTubeHost(host)) return null

        // Everything after the authority, which is where the path begins.
        val tail = rest.removePrefix(authority)
        val fragmentAt = tail.indexOf('#')
        val withoutFragment = if (fragmentAt >= 0) tail.substring(0, fragmentAt) else tail
        val fragment = if (fragmentAt >= 0) tail.substring(fragmentAt) else ""

        val queryAt = withoutFragment.indexOf('?')
        val path = if (queryAt >= 0) withoutFragment.substring(0, queryAt) else withoutFragment
        val query = if (queryAt >= 0) withoutFragment.substring(queryAt + 1) else ""

        if (!path.startsWith(SHORTS_PATH)) return null

        val id = path.removePrefix(SHORTS_PATH).trim('/')
        // The feed itself: /shorts, or /shorts/ with nothing after it. There is
        // no single video to send anybody to, so this goes home.
        if (id.isEmpty()) return "$scheme$authority/"
        // A path with more segments is not a Short; leave it rather than guess.
        if (id.contains('/')) return null

        // Everything the original carried except a v of its own, which would
        // otherwise arrive twice and let the page pick the wrong one.
        val kept = query.split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') != "v" }

        val rebuilt = (listOf("v=$id") + kept).joinToString("&")
        return "$scheme$authority/watch?$rebuilt$fragment"
    }

    /**
     * Shorts live on the main site and its mobile front end. `youtube-nocookie`
     * is the embed host and has no Shorts surface, so it is deliberately not
     * matched — rewriting an embed would break a page that merely quotes a video.
     */
    private fun isYouTubeHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com")

    private const val SHORTS_PATH = "/shorts"
}
