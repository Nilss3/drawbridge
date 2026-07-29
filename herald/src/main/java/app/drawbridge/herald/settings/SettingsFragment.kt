package app.drawbridge.herald.settings

import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import app.drawbridge.herald.HeraldPolicy
import app.drawbridge.herald.R
import app.drawbridge.herald.ext.requireComponents
import app.drawbridge.herald.search.SearchEngineSelection
import app.drawbridge.policy.PolicyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.browser.state.state.searchEngines
import mozilla.components.lib.state.ext.flow

/**
 * herald's settings.
 *
 * Note what is *not* here: there is no DNS-over-HTTPS / "secure DNS" toggle, and
 * no way to disable the content filter. Both are deliberate — the filter is the
 * product, and browser-level encrypted DNS would hide lookups from the
 * device-wide filter on a managed device.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var filterStatus: Preference
    private lateinit var searchEngine: ListPreference

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen: PreferenceScreen = preferenceManager.createPreferenceScreen(context)

        screen.addPreference(
            PreferenceCategory(context).apply { setTitle(R.string.settings_category_general) },
        )
        screen.addPreference(
            SwitchPreferenceCompat(context).apply {
                key = HeraldSettings.KEY_NEW_TAB_BOOKMARKS
                setTitle(R.string.settings_new_tab_bookmarks)
                setSummary(R.string.settings_new_tab_bookmarks_summary)
                setDefaultValue(HeraldSettings.NEW_TAB_BOOKMARKS_DEFAULT)
            },
        )

        screen.addPreference(
            PreferenceCategory(context).apply { setTitle(R.string.settings_category_search) },
        )
        screen.addPreference(buildSearchEnginePreference())

        screen.addPreference(
            PreferenceCategory(context).apply { setTitle(R.string.settings_category_filter) },
        )
        filterStatus = Preference(context).apply {
            setTitle(R.string.settings_filter_status)
            isSelectable = false
        }
        screen.addPreference(filterStatus)

        screen.addPreference(
            Preference(context).apply {
                setTitle(R.string.settings_check_for_updates)
                setSummary(R.string.settings_check_for_updates_summary)
                setOnPreferenceClickListener {
                    refreshPolicy()
                    true
                }
            },
        )

        screen.addPreference(
            PreferenceCategory(context).apply { setTitle(R.string.settings_category_privacy) },
        )
        screen.addPreference(
            Preference(context).apply {
                setTitle(R.string.settings_clear_browsing_data)
                setOnPreferenceClickListener {
                    clearBrowsingData()
                    true
                }
            },
        )

        screen.addPreference(
            PreferenceCategory(context).apply { setTitle(R.string.settings_category_about) },
        )
        screen.addPreference(
            Preference(context).apply {
                setTitle(R.string.settings_version)
                summary = app.drawbridge.herald.BuildConfig.VERSION_NAME
                isSelectable = false
            },
        )

        preferenceScreen = screen
        updateFilterStatus()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The engine catalogue is loaded asynchronously, so it is normally still
        // empty when the screen is built. Filling the list once in
        // onCreatePreferences left the preference permanently empty, with its
        // summary falling back to the title.
        val components = requireComponents
        viewLifecycleOwner.lifecycleScope.launch {
            components.core.store.flow(viewLifecycleOwner)
                .map { it.search }
                .distinctUntilChanged()
                .collect { search ->
                    val engines = search.searchEngines
                    if (engines.isEmpty()) return@collect

                    val selected = search.selectedOrDefaultSearchEngine
                    searchEngine.entries = engines.map { it.name }.toTypedArray()
                    searchEngine.entryValues = engines.map { it.id }.toTypedArray()
                    searchEngine.value = selected?.id
                    searchEngine.summary = selected?.name
                    searchEngine.isEnabled = true
                }
        }
    }

    private fun buildSearchEnginePreference(): Preference {
        val components = requireComponents

        return ListPreference(requireContext()).apply {
            key = "search_engine"
            setTitle(R.string.settings_search_engine)
            isPersistent = false
            summary = getString(R.string.settings_search_engine_loading)
            isEnabled = false

            setOnPreferenceChangeListener { preference, newValue ->
                val engine = SearchEngineSelection.availableEngines(components)
                    .firstOrNull { it.id == newValue }
                    ?: return@setOnPreferenceChangeListener false
                SearchEngineSelection.selectByUser(requireContext(), components, engine)
                preference.summary = engine.name
                true
            }
        }.also { searchEngine = it }
    }

    private fun updateFilterStatus() {
        val manager = HeraldPolicy.manager(requireContext())
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { manager.state() }
            val version = manager.policy.value.version
            val lastCheck = if (state.lastSuccessMillis > 0) {
                DateUtils.getRelativeTimeSpanString(state.lastSuccessMillis)
            } else {
                getString(R.string.settings_filter_never_checked)
            }
            filterStatus.summary = getString(R.string.settings_filter_status_summary, version, lastCheck)
        }
    }

    private fun refreshPolicy() {
        val manager = HeraldPolicy.manager(requireContext())
        lifecycleScope.launch {
            val outcome = manager.refresh()
            val message = when (outcome) {
                is PolicyManager.RefreshOutcome.Success ->
                    getString(R.string.settings_filter_updated, outcome.version)
                is PolicyManager.RefreshOutcome.Failure ->
                    getString(R.string.settings_filter_update_failed)
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            updateFilterStatus()
        }
    }

    private fun clearBrowsingData() {
        val components = requireComponents
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                components.core.historyStorage.deleteEverything()
            }
            components.useCases.tabsUseCases.removeAllTabs()
            components.core.engine.clearData(
                data = mozilla.components.concept.engine.Engine.BrowsingData.all(),
            )
            Toast.makeText(
                requireContext(),
                R.string.settings_browsing_data_cleared,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}
