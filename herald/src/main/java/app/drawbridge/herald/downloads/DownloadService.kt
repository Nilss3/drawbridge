package app.drawbridge.herald.downloads

import app.drawbridge.herald.ext.components
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.downloads.PackageNameProvider
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter
import mozilla.components.support.base.android.NotificationsDelegate
import mozilla.components.support.utils.DownloadFileUtils

class DownloadService : AbstractFetchDownloadService() {
    override val httpClient: Client by lazy { components.core.client }
    override val store: BrowserStore by lazy { components.core.store }
    override val notificationsDelegate: NotificationsDelegate by lazy {
        components.notificationsDelegate
    }
    override val fileSizeFormatter: FileSizeFormatter by lazy { components.downloads.fileSizeFormatter }
    override val downloadEstimator: DownloadEstimator by lazy { components.downloads.estimator }
    override val downloadFileUtils: DownloadFileUtils by lazy { components.downloads.fileUtils }
    override val downloadFileWriter: DownloadFileWriter by lazy { components.downloads.fileWriter }
    override val packageNameProvider: PackageNameProvider by lazy {
        components.downloads.packageNameProvider
    }
}
