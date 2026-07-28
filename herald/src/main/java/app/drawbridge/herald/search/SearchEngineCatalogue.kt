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
 * **This list is a filtering decision, not a preference.** Safe search is forced
 * at the DNS layer by rewriting the engine's hostname, and only Google, Bing and
 * DuckDuckGo publish a hostname to rewrite to (see `DnsFilter`). Every other
 * engine here serves image results from its own CDN, which no domain blocklist
 * covers. Yandex and Baidu are left out entirely rather than offered.
 */
object SearchEngineCatalogue {

    /**
     * Engines herald can add itself, for the ones Mozilla's catalogue either does
     * not ship (Brave, Startpage, Kagi) or only shows in some locales (Ecosia,
     * Qwant). Bundled engines are preferred when the locale already has them.
     */
    private val ADDABLE = listOf(
        Addable("herald-ddg", "DuckDuckGo", "https://duckduckgo.com/?q={searchTerms}"),
        Addable("herald-google", "Google", "https://www.google.com/search?q={searchTerms}"),
        Addable("herald-bing", "Bing", "https://www.bing.com/search?q={searchTerms}"),
        Addable("herald-brave", "Brave Search", "https://search.brave.com/search?q={searchTerms}"),
        Addable("herald-qwant", "Qwant", "https://www.qwant.com/?q={searchTerms}"),
        Addable("herald-ecosia", "Ecosia", "https://www.ecosia.org/search?q={searchTerms}"),
        Addable("herald-startpage", "Startpage", "https://www.startpage.com/sp/search?query={searchTerms}"),
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
