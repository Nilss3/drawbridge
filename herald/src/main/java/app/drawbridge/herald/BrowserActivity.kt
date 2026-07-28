package app.drawbridge.herald

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import app.drawbridge.herald.browser.BrowserFragment
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.intent.processing.TabIntentProcessor
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.UserInteractionHandler
import app.drawbridge.herald.ext.components

/** Hosts the browser. herald is a single-activity app. */
class BrowserActivity : AppCompatActivity() {

    private val tabIntentProcessor by lazy {
        TabIntentProcessor(
            components.useCases.tabsUseCases,
            components.useCases.searchUseCases.newTabSearch,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browser)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, BrowserFragment.create(sessionId = null))
                .commit()
        }

        intent?.let { handleIntent(it) }

        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW || intent.action == Intent.ACTION_SEND) {
            tabIntentProcessor.process(intent)
        }
    }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val handled = supportFragmentManager.fragments
                .filterIsInstance<UserInteractionHandler>()
                .any { it.onBackPressed() }

            if (!handled) {
                // Nothing in the browser wanted the press: leave herald running in
                // the background rather than tearing down the engine, which is
                // expensive to warm up again.
                moveTaskToBack(true)
            }
        }
    }

    override fun onUserLeaveHint() {
        supportFragmentManager.fragments
            .filterIsInstance<UserInteractionHandler>()
            .any { it.onHomePressed() }
        super.onUserLeaveHint()
    }

    @Deprecated("Forwarded to features that still use the old result API")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        supportFragmentManager.fragments
            .filterIsInstance<ActivityResultHandler>()
            .any { it.onActivityResult(requestCode, data, resultCode) }
    }

    /**
     * Gecko needs the [EngineView] to create child views inside the activity's
     * window; without this the engine cannot instantiate its surface.
     */
    override fun onCreateView(
        parent: android.view.View?,
        name: String,
        context: android.content.Context,
        attrs: android.util.AttributeSet,
    ): android.view.View? = when (name) {
        EngineView::class.java.name -> components.core.engine.createView(context, attrs).asView()
        else -> super.onCreateView(parent, name, context, attrs)
    }

    /** True when there is a tab to go back to; used by the tabs tray. */
    fun hasSelectedTab(): Boolean = components.core.store.state.selectedTab != null

    fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }
}
