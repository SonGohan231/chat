package pl.somaskan.questgpt.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Self-update helper for sideloaded builds.
 * Android/Horizon OS does not let a normal app silently replace itself.
 * This class downloads an APK into app storage and launches the system package installer,
 * which still requires the user's confirmation unless the app has privileged/device-owner rights.
 */
class UpdateManager(private val context: Context) {
    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun buildUnknownSourcesIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}")
    )

    fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
