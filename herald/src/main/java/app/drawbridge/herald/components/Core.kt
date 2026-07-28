package app.drawbridge.herald.components

import android.content.Context
import app.drawbridge.herald.downloads.DownloadService
import app.drawbridge.herald.filter.HeraldRequestInterceptor
import mozilla.components.browser.engine.gecko.autofill.GeckoAutocompleteStorageDelegate
import mozilla.components.browser.engine.gecko.permission.GeckoSitePermissionsStorage
import mozilla.components.browser.icons.BrowserIcons
import mozilla.components.browser.session.storage.SessionStorage
import mozilla.components.browser.state.engine.EngineMiddleware
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.browser.storage.sync.PlacesBookmarksStorage
import mozilla.components.browser.storage.sync.PlacesHistoryStorage
import mozilla.components.browser.thumbnails.ThumbnailsMiddleware
import mozilla.components.browser.thumbnails.storage.ThumbnailStorage
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import app.drawbridge.herald.ext.preferredColorScheme
import mozilla.components.concept.engine.EngineSession.TrackingProtectionPolicy
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.downloads.DownloadMiddleware
import mozilla.components.feature.logins.exceptions.LoginExceptionStorage
import mozilla.components.feature.media.MediaSessionFeature
import mozilla.components.feature.prompts.file.FileUploadsDirCleaner
import mozilla.components.feature.search.middleware.SearchMiddleware
import mozilla.components.feature.search.region.RegionMiddleware
import mozilla.components.feature.session.HistoryDelegate
import mozilla.components.feature.sitepermissions.OnDiskSitePermissionsStorage
import mozilla.components.lib.dataprotect.SecureAbove22Preferences
import mozilla.components.service.location.LocationService
import mozilla.components.service.sync.logins.DefaultLoginValidationDelegate
import mozilla.components.service.sync.logins.GeckoLoginStorageDelegate
import mozilla.components.service.sync.logins.SyncableLoginsStorage
import app.drawbridge.herald.media.MediaSessionService

/**
 * The browser's long-lived objects: engine, state store and the storage backing
 * history, bookmarks and saved logins.
 */
class Core(private val context: Context, private val downloads: Downloads) {

    val engine: Engine by lazy {
        EngineProvider.createEngine(
            context,
            DefaultSettings(
                requestInterceptor = HeraldRequestInterceptor(context),
                remoteDebuggingEnabled = false,
                trackingProtectionPolicy = TrackingProtectionPolicy.recommended(),
                historyTrackingDelegate = HistoryDelegate(lazyHistoryStorage),
                // Ad and tracker domains are handled by the shared blocklist, but
                // tracking protection also does cookie and fingerprinting work the
                // DNS layer cannot.
                globalPrivacyControlEnabled = true,
                // Hands the phone's day/night setting to `prefers-color-scheme`,
                // so sites that have a dark theme render in it — including the
                // block page. Kept in step with the chrome by
                // HeraldApplication.onConfigurationChanged.
                preferredColorScheme = context.preferredColorScheme,
            ),
        ).also { wireLoginStorage() }
    }

    val client: Client by lazy { EngineProvider.createClient(context) }

    val store: BrowserStore by lazy {
        BrowserStore(
            middleware = listOf(
                DownloadMiddleware(
                    applicationContext = context,
                    downloadServiceClass = DownloadService::class.java,
                    // Removing a download from the list is not a request to delete
                    // the file the user already has on disk.
                    deleteFileFromStorage = { false },
                    downloadFileUtils = downloads.fileUtils,
                ),
                ThumbnailsMiddleware(thumbnailStorage),
                RegionMiddleware(context, LocationService.default()),
                SearchMiddleware(context),
            ) + EngineMiddleware.create(engine),
        ).apply {
            icons.install(engine, this)
            MediaSessionFeature(context, MediaSessionService::class.java, this).start()
        }
    }

    val sessionStorage: SessionStorage by lazy { SessionStorage(context, engine) }

    val lazyHistoryStorage = lazy { PlacesHistoryStorage(context) }
    val historyStorage: PlacesHistoryStorage by lazy { lazyHistoryStorage.value }

    val bookmarksStorage: PlacesBookmarksStorage by lazy { PlacesBookmarksStorage(context) }

    private val lazySecurePrefs = lazy { SecureAbove22Preferences(context, SECURE_PREFS_NAME) }

    val lazyLoginsStorage = lazy { SyncableLoginsStorage(context, lazySecurePrefs) }
    val loginsStorage: SyncableLoginsStorage by lazy { lazyLoginsStorage.value }

    val loginExceptionStorage: LoginExceptionStorage by lazy { LoginExceptionStorage(context) }

    val loginValidationDelegate by lazy { DefaultLoginValidationDelegate(lazyLoginsStorage) }

    val thumbnailStorage: ThumbnailStorage by lazy { ThumbnailStorage(context) }

    val icons: BrowserIcons by lazy { BrowserIcons(context, client) }

    val geckoSitePermissionsStorage: GeckoSitePermissionsStorage by lazy {
        GeckoSitePermissionsStorage(
            EngineProvider.getOrCreateRuntime(context),
            OnDiskSitePermissionsStorage(context),
        )
    }

    val fileUploadsDirCleaner: FileUploadsDirCleaner by lazy {
        FileUploadsDirCleaner { context.cacheDir }
    }

    /**
     * Lets Gecko read and write saved logins. Credit cards and addresses are
     * deliberately not stored, so that half of the delegate is a no-op — the
     * runtime insists on being given both.
     */
    private fun wireLoginStorage() {
        EngineProvider.getOrCreateRuntime(context).autocompleteStorageDelegate =
            GeckoAutocompleteStorageDelegate(
                creditCardsAddressesStorageDelegate = NoCreditCardsOrAddresses,
                loginStorageDelegate = GeckoLoginStorageDelegate(
                    loginStorage = lazyLoginsStorage,
                    isLoginAutofillEnabled = { true },
                ),
            )
    }

    private companion object {
        const val SECURE_PREFS_NAME = "herald_secure_prefs"
    }
}
