package app.drawbridge.herald.extensions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.drawbridge.herald.ext.requireComponents
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.concept.engine.EngineView

/**
 * Hosts a web extension's browser-action popup — in practice, uBlock Origin's:
 * the power button that turns blocking off for the site you are on, the
 * per-site switches and the element picker.
 *
 * Android Components creates the popup's [mozilla.components.concept.engine.EngineSession]
 * and parks it on `WebExtensionState.popupSession`, but renders it nowhere; an
 * app that does not pick it up gets a toolbar button that appears to do nothing.
 * This is that missing half, as a sheet rather than a tab so the popup does not
 * end up in the tab list and the back stack.
 */
class ExtensionPopupFragment : BottomSheetDialogFragment() {

    private var engineView: EngineView? = null

    private val extensionId: String? get() = arguments?.getString(ARG_EXTENSION_ID)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // Built by hand rather than inflated: the engine view is substituted at
        // inflation time by BrowserActivity's onCreateView factory, which a
        // dialog's own inflater does not go through.
        val view = requireComponents.core.engine.createView(requireContext())
        engineView = view

        return FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                sheetHeight(),
            )
            addView(
                view.asView(),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    override fun onStart() {
        super.onStart()

        val session = extensionId?.let {
            requireComponents.core.store.state.extensions[it]?.popupSession
        }

        if (session == null) {
            // The popup was closed, or the extension went away while the sheet
            // was opening. Nothing to show.
            dismissAllowingStateLoss()
            return
        }

        engineView?.render(session)
    }

    override fun onDestroyView() {
        engineView?.release()
        engineView = null
        super.onDestroyView()
    }

    /**
     * Clearing the popup session is what tells Android Components the popup is
     * gone; without it the next tap on the toolbar button finds a session that
     * is already open and does nothing.
     */
    override fun onDestroy() {
        val id = extensionId
        val store = requireComponents.core.store
        val session = id?.let { store.state.extensions[it]?.popupSession }
        if (id != null && session != null) {
            session.close()
            store.dispatch(WebExtensionAction.UpdatePopupSessionAction(id, popupSession = null))
        }
        super.onDestroy()
    }

    /** Tall enough for uBO's popup, short enough to still read as a sheet. */
    private fun sheetHeight(): Int =
        (resources.displayMetrics.heightPixels * SHEET_HEIGHT_FRACTION).toInt()

    companion object {
        private const val ARG_EXTENSION_ID = "extension_id"
        private const val SHEET_HEIGHT_FRACTION = 0.75f

        const val TAG = "extension-popup"

        fun create(extensionId: String): ExtensionPopupFragment = ExtensionPopupFragment().apply {
            arguments = Bundle().apply { putString(ARG_EXTENSION_ID, extensionId) }
        }
    }
}
