package app.drawbridge.herald.components

import android.content.Context
import android.os.Environment
import mozilla.components.feature.downloads.DefaultPackageNameProvider
import mozilla.components.feature.downloads.DefaultFileSizeFormatter
import mozilla.components.feature.downloads.DownloadEstimator
import mozilla.components.feature.downloads.FileSizeFormatter
import mozilla.components.feature.downloads.PackageNameProvider
import mozilla.components.feature.downloads.filewriter.DefaultDownloadFileWriter
import mozilla.components.feature.downloads.filewriter.DownloadFileWriter
import mozilla.components.support.utils.DefaultDateTimeProvider
import mozilla.components.support.utils.DefaultDownloadFileUtils
import mozilla.components.support.utils.DownloadFileUtils

/**
 * The pieces the downloads stack needs, in one place: the store middleware, the
 * fragment-side feature and the foreground service all have to agree on where
 * files go and how they are written.
 */
class Downloads(private val context: Context) {

    val location: () -> String = {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
    }

    val fileUtils: DownloadFileUtils by lazy {
        DefaultDownloadFileUtils(context = context, downloadLocation = location)
    }

    val fileWriter: DownloadFileWriter by lazy {
        DefaultDownloadFileWriter(context = context, downloadFileUtils = fileUtils)
    }

    val fileSizeFormatter: FileSizeFormatter by lazy { DefaultFileSizeFormatter(context) }

    val estimator: DownloadEstimator by lazy { DownloadEstimator(DefaultDateTimeProvider()) }

    val packageNameProvider: PackageNameProvider by lazy { DefaultPackageNameProvider(context) }
}
