package app.drawbridge.herald

import android.app.Application
import android.content.res.Configuration
import app.drawbridge.herald.components.Components
import app.drawbridge.herald.ext.preferredColorScheme
import app.drawbridge.policy.PolicyManager
import app.drawbridge.policy.work.PolicyWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.support.AppServicesInitializer
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.sink.AndroidLogSink
import mozilla.components.support.ktx.android.content.isMainProcess
import mozilla.components.support.ktx.android.content.runOnlyInMainProcess
import mozilla.components.support.rusthttp.RustHttpConfig
import java.util.concurrent.TimeUnit

class HeraldApplication : Application() {

    val components by lazy { Components(this) }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        Log.addSink(AndroidLogSink())

        // GeckoView child processes and the crash handler run in their own
        // processes; none of the setup below belongs there, and creating a second
        // GeckoRuntime from a child process would be fatal.
        if (!isMainProcess()) return

        AppServicesInitializer.init(AppServicesInitializer.Config(crashReporting = null))
        RustHttpConfig.setClient(lazy { components.core.client })

        // Load the stored (or bundled) policy before anything can navigate, then
        // let the scheduled poll pick up changes.
        val policy = PolicyManager.getInstance(this, HeraldPolicy.config)
        applicationScope.launch { policy.ensureLoaded() }
        applicationScope.launch {
            policy.filterChanges.collect { components.filter.invalidateCache() }
        }
        PolicyWorker.schedule(this)

        components.core.engine.warmUp()
        components.filter.install(components.core.engine)

        restoreBrowserState()
    }

    /**
     * Follows the phone into and out of dark mode.
     *
     * The activity is recreated on its own and picks up the night colours with
     * it, but the engine is process-wide and outlives that, so page content —
     * including the block page — would keep the scheme it started with.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runOnlyInMainProcess {
            components.core.engine.settings.preferredColorScheme = preferredColorScheme
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runOnlyInMainProcess {
            components.core.store.dispatch(SystemAction.LowMemoryAction(level))
            components.core.icons.onTrimMemory(level)
        }
    }

    private fun restoreBrowserState() = applicationScope.launch {
        val sessionStorage = components.core.sessionStorage
        components.useCases.tabsUseCases.restore(sessionStorage)

        sessionStorage
            .autoSave(components.core.store)
            .periodicallyInForeground(interval = 30, unit = TimeUnit.SECONDS)
            .whenGoingToBackground()
            .whenSessionsChange()
    }
}
