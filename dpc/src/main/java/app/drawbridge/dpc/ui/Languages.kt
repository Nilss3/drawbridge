package app.drawbridge.dpc.ui

import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.drawbridge.dpc.R

/**
 * The languages drawbridge's own screens are offered in.
 *
 * This is a per-app locale, not a device one. Device Owner privilege does not
 * extend to changing the system language — there is no such API — so the picker
 * changes what the parent reads on this screen and nothing else. The phone's own
 * language, and herald's, are set where they always were.
 *
 * Adding a language means one line here, a `values-xx/strings.xml`, and an entry
 * in `res/xml/locales_config.xml`. Nothing in the layouts refers to a specific
 * one.
 */
object Languages {

    data class Choice(val tag: String, @param:StringRes val label: Int)

    val supported = listOf(
        Choice("en", R.string.language_english),
        Choice("nl", R.string.language_dutch),
        Choice("fr", R.string.language_french),
    )

    /**
     * The tag whose button should be selected.
     *
     * AppCompat reports an empty list until someone has chosen, meaning "follow
     * the phone", so the phone's own language is matched against what is on
     * offer and everything else falls back to English — which is what the
     * untranslated resources are written in.
     */
    fun current(): String {
        val chosen = AppCompatDelegate.getApplicationLocales()
        if (!chosen.isEmpty) {
            val tag = chosen[0]?.language
            if (supported.any { it.tag == tag }) return tag!!
        }

        val system = LocaleListCompat.getDefault()
        for (index in 0 until system.size()) {
            val tag = system[index]?.language
            if (supported.any { it.tag == tag }) return tag!!
        }
        return supported.first().tag
    }

    /**
     * Switches language. AppCompat persists the choice and recreates whatever is
     * on screen, so callers have nothing to do afterwards.
     */
    fun select(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }
}
