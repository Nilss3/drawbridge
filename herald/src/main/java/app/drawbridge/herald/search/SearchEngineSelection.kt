package app.drawbridge.herald.search

import android.content.Context
import androidx.preference.PreferenceManager
import app.drawbridge.herald.components.Components
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.state.searchEngines

/**
 * Applies the policy's default search engine.
 *
 * The policy only ever sets the *default*: once someone picks an engine in
 * settings that choice sticks, because silently changing it back on every poll
 * would be indistinguishable from a bug.
 */
object SearchEngineSelection {

    private const val PREF_USER_CHOSE_ENGINE = "user_chose_search_engine"

    fun availableEngines(components: Components): List<SearchEngine> =
        components.core.store.state.search.searchEngines

    fun applyPolicyDefault(context: Context, components: Components, requestedId: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (prefs.getBoolean(PREF_USER_CHOSE_ENGINE, false)) return

        val engine = findEngine(components, requestedId) ?: return
        components.useCases.searchUseCases.selectSearchEngine(engine)
    }

    fun selectByUser(context: Context, components: Components, engine: SearchEngine) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(PREF_USER_CHOSE_ENGINE, true)
            .apply()
        components.useCases.searchUseCases.selectSearchEngine(engine)
    }

    /**
     * Policy names engines loosely ("duckduckgo"), while the engine catalogue uses
     * ids like `ddg` and region-suffixed ids like `google-b-m`, so match on either
     * the id or the display name.
     */
    private fun findEngine(components: Components, requestedId: String): SearchEngine? {
        val wanted = requestedId.trim().lowercase().replace(" ", "")
        if (wanted.isEmpty()) return null

        val engines = availableEngines(components)
        return engines.firstOrNull { it.id.lowercase() == wanted }
            ?: engines.firstOrNull { it.name.lowercase().replace(" ", "") == wanted }
            ?: engines.firstOrNull { it.id.lowercase().startsWith(wanted) }
            ?: engines.firstOrNull { it.name.lowercase().replace(" ", "").contains(wanted) }
    }
}
