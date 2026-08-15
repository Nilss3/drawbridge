package app.drawbridge.herald.search

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import app.drawbridge.herald.components.Components
import mozilla.components.browser.state.action.SearchAction
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.state.searchEngines

/**
 * The engines herald offers, and the ones it refuses to.
 *
 * The policy names which engines exist; whoever uses the phone picks among them.
 * That split is deliberate — an in-app "add a search engine" button would be a
 * way to reach an unfiltered one, for the same reason there is no `about:config`.
 *
 * **This list is a filtering decision, not a preference.** An engine is offered
 * only if its safe search can actually be forced, because herald is the only
 * browser on the phone and an engine that cannot be forced is a hole straight
 * through everything else the policy does.
 *
 * Two ways that holds, in descending order of strength:
 *
 *  - **Google, Bing and DuckDuckGo** publish a safe hostname, and drawbridge
 *    rewrites DNS to it (see `DnsFilter`). Nothing typed in the browser can undo
 *    that. [SafeSearch] adds their parameters as well, for the standalone herald
 *    that has no filter behind it.
 *  - **Kagi** filters explicit results when logged out and offers no setting to
 *    stop it; changing that needs a paid account to sign into.
 *
 * **There was a third way, and Ecosia was the only engine resting on it — which
 * is why it went on 2026-08-15.** Ecosia honours `safesearch=2`, and
 * [SafeSearch] put that parameter back on every load, so inside herald it was
 * genuinely forced. But herald is not the only browser the policy allows:
 * Chrome, Firefox Focus and Vivaldi are on the phone too, and in any of them
 * ecosia.org is unfiltered search with nothing to rewrite it. *Safe in the
 * browser that enforces it* is not a property of the phone. The two ways above
 * survive that test precisely because they do not depend on which browser is
 * being used — a DNS rewrite reaches every one of them, and Kagi needs no help
 * at all. `ecosia.org` is on `dist/lists/search.txt` as of policy 55, so the
 * engine is no longer reachable by typing its name either.
 *
 * **Three engines were removed on 2026-08-10 rather than shipped unforced.**
 * Brave Search can only be forced with a `safesearch` cookie — the vendor's own
 * answer for filters rewrites the Cookie header behind TLS interception, which
 * this project will not do. Startpage searches by POST deliberately, so there is
 * no parameter to set. Qwant documents one that reference implementations record
 * as not actually heeded. Yandex and Baidu were never offered.
 *
 * Removing an engine here is not enough on its own: the policy's
 * `browser.search_engines` names what a device may show, and a name it still
 * carries that this catalogue no longer knows how to add simply goes missing
 * rather than appearing unforced. Both lists were changed together.
 */
object SearchEngineCatalogue {

    /**
     * Engines herald can add itself, for the ones Mozilla's catalogue does not
     * ship — Kagi. Bundled engines are preferred when the locale already has
     * them.
     *
     * The safe-search parameters are here as well as in [SafeSearch]. They are
     * not redundant: this is what the *first* request carries, and [SafeSearch]
     * is what puts them back when someone removes them.
     */
    private val ADDABLE = listOf(
        Addable("herald-ddg", "DuckDuckGo", "https://duckduckgo.com/?q={searchTerms}&kp=1"),
        Addable(
            "herald-google",
            "Google",
            "https://www.google.com/search?q={searchTerms}&safe=active",
        ),
        Addable("herald-bing", "Bing", "https://www.bing.com/search?q={searchTerms}&adlt=strict"),
        // Nothing to add: logged out, Kagi filters and offers no way not to.
        Addable("herald-kagi", "Kagi", "https://kagi.com/search?q={searchTerms}"),
    )

    private data class Addable(val id: String, val name: String, val url: String)

    /**
     * Brings the engine list in line with [allowed], which comes from the policy.
     *
     * Anything the policy does not name is hidden — including whatever the
     * phone's locale brought in — and anything it names that the catalogue lacks
     * is added, if herald knows a URL for it.
     */
    fun apply(components: Components, allowed: List<String>) {
        val wanted = allowed.map { it.normalised() }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return

        val store = components.core.store
        val present = store.state.search.searchEngines

        ADDABLE.filter { addable ->
            addable.name.normalised() in wanted &&
                present.none { it.name.normalised() == addable.name.normalised() }
        }.forEach { addable ->
            components.useCases.searchUseCases.addSearchEngine(
                SearchEngine(
                    id = addable.id,
                    name = addable.name,
                    icon = letterTile(addable.name),
                    type = SearchEngine.Type.CUSTOM,
                    resultUrls = listOf(addable.url),
                    isGeneral = true,
                ),
            )
        }

        present.filterNot { engine ->
            engine.name.normalised() in wanted || engine.id.normalised() in wanted
        }.forEach { engine ->
            store.dispatch(SearchAction.HideSearchEngineAction(engine.id))
        }
    }

    /**
     * Policy names engines loosely ("duckduckgo", "Brave Search"), and the
     * catalogue's own ids are region-suffixed (`google-b-m`), so comparisons drop
     * case and spacing on both sides.
     */
    private fun String.normalised(): String = trim().lowercase().replace(" ", "")

    /**
     * A stand-in icon. Custom engines must carry a bitmap, and herald's only
     * engine UI is a text list, so a letter tile is enough to satisfy the API
     * without shipping eight logos.
     */
    private fun letterTile(name: String): Bitmap {
        val size = 48
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)

        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF2F3B6E") }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 12f, 12f, background)

        val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        val baseline = size / 2f - (letter.descent() + letter.ascent()) / 2f
        canvas.drawText(name.take(1).uppercase(), size / 2f, baseline, letter)

        return bitmap
    }
}
