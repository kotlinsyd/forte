package com.kotlinsyd.forte.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The in-app update download (v1.0.5): the updater used to hand the
 * APK link to the browser. Now the APK is downloaded inside Forte
 * with visible progress and handed straight to the system installer
 * through a FileProvider URI — no browser anywhere in the path.
 * Android's own guard still applies: the first install asks the user
 * to allow installs from Forte (unknown-apps permission); when that
 * grant is missing we open its settings page and say so.
 */
object UpdateInstaller {

    sealed interface State {
        data object Idle : State
        data class Downloading(val fraction: Float) : State
        data object Ready : State
        data object NeedsPermission : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun apkFile(context: Context): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        return File(dir, "forte-update.apk")
    }

    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Open Android's "allow installs from Forte" page. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    suspend fun download(context: Context, info: UpdateInfo) = withContext(Dispatchers.IO) {
        state.value = State.Downloading(0f)
        runCatching {
            val req = Request.Builder()
                .url(info.apkUrl)
                .header("User-Agent", "Forte/${UpdateChecker.installedCode(context)}")
                .build()
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "Download failed (${resp.code})" }
                val body = resp.body ?: error("Empty download")
                val total = body.contentLength()
                val out = apkFile(context)
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                state.value = State.Downloading(
                                    (done.toFloat() / total).coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                }
                check(out.length() > 0) { "Empty file" }
            }
            state.value = State.Ready
        }.onFailure {
            state.value = State.Failed("Couldn't download the update. Check your connection.")
        }
    }

    /** Hand the downloaded APK to the system installer. Returns false
     * when the unknown-apps grant is missing (state explains). */
    fun install(context: Context): Boolean {
        if (!canInstall(context)) {
            state.value = State.NeedsPermission
            return false
        }
        val file = apkFile(context)
        if (!file.exists()) {
            state.value = State.Failed("The update file is missing — download again.")
            return false
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun reset() {
        state.value = State.Idle
    }
}
