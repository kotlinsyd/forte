package com.kotlinsyd.forte.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.kotlinsyd.forte.data.db.DownloadEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Fetches a track's audio stream into app storage so it plays offline.
 * One file per song under filesDir/downloads, registered in the library. */
class DownloadManager(
    private val context: Context,
    private val library: Library,
    private val settings: SettingsStore,
) {
    data class Progress(
        val fraction: Float? = null,
        val error: String? = null,
        val done: Boolean = false,
    )

    private val _states = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val states: StateFlow<Map<String, Progress>> = _states

    /** Tracks behind the in-flight downloads, so the Downloads screen can
     * show a proper row (title, artwork) before the file lands in the db. */
    private val _activeTracks = MutableStateFlow<Map<String, Track>>(emptyMap())
    val activeTracks: StateFlow<Map<String, Track>> = _activeTracks

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val calls = HashMap<String, Call>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun dir(): File = File(context.filesDir, "downloads").apply { mkdirs() }

    private fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun start(track: Track) {
        if (_states.value[track.id]?.done == false && _states.value.containsKey(track.id) &&
            _states.value[track.id]?.error == null
        ) return
        if (settings.settings.value.wifiOnlyDownloads && !onWifi()) {
            setState(track.id, Progress(error = "Waiting for Wi-Fi"))
            return
        }
        setState(track.id, Progress(fraction = 0f))
        _activeTracks.value = _activeTracks.value + (track.id to track)
        scope.launch {
            try {
                // Forte's own engine resolves the download too, and
                // the fetch wears the minting client's identity in
                // bounded ranges — the same dressing playback uses.
                // An open-ended GET of these URLs is refused (403)
                // for the VR/Android clients, and some clients'
                // URLs stop being honoured past 1 MiB in a single
                // continuous response, so the file is assembled
                // from 1 MiB windows (512 KiB for VR-class URLs).
                val resolved = ForteEngine.resolve(
                    track.id,
                    settings.settings.value.downloadQuality,
                )
                val ext = when {
                    resolved.format.contains("MP4", true) ||
                        resolved.format.contains("M4A", true) -> "m4a"
                    resolved.format.contains("OPUS", true) -> "webm"
                    resolved.format.contains("WebM", true) -> "webm"
                    else -> "audio"
                }
                val file = File(dir(), "${track.id}.$ext")
                val total = resolved.sizeBytes
                val window = ForteEngine.MediaDress.rangeBytesFor(resolved.url)
                val headers = ForteEngine.MediaDress.headersFor(resolved.url)
                file.outputStream().use { out ->
                    var doneBytes = 0L
                    var first = true
                    while (total <= 0 || doneBytes < total) {
                        val end = if (total > 0) {
                            minOf(doneBytes + window, total) - 1
                        } else {
                            doneBytes + window - 1
                        }
                        val req = Request.Builder().url(resolved.url)
                            .header("Range", "bytes=$doneBytes-$end")
                            .apply {
                                headers.forEach { (k, v) -> header(k, v) }
                            }
                            .build()
                        val call = http.newCall(req)
                        synchronized(calls) { calls[track.id] = call }
                        val chunkBytes = call.execute().use { resp ->
                            // A plain 200 to the first ranged request
                            // means the server ignored the range and
                            // sent the whole file — accept it whole.
                            if (first && resp.code == 200 && total <= 0) {
                                val body = resp.body
                                    ?: throw IllegalStateException("Empty response")
                                body.byteStream().use { input ->
                                    input.copyTo(out)
                                }
                                doneBytes = file.length()
                                -1L
                            } else {
                                if (!resp.isSuccessful) {
                                    throw IllegalStateException("HTTP ${resp.code}")
                                }
                                val body = resp.body
                                    ?: throw IllegalStateException("Empty response")
                                body.byteStream().use { input ->
                                    input.copyTo(out)
                                }
                            }
                        }
                        if (chunkBytes == -1L) break
                        // Recompute from the file itself: the chunk
                        // just written is exactly what arrived.
                        val now = file.length()
                        if (now <= doneBytes) {
                            throw IllegalStateException("Download stalled")
                        }
                        doneBytes = now
                        first = false
                        if (total > 0) {
                            setState(
                                track.id,
                                Progress(fraction = doneBytes.toFloat() / total),
                            )
                        }
                    }
                }
                library.registerDownload(
                    DownloadEntity(
                        videoId = track.id,
                        title = track.title,
                        artist = track.artist,
                        artistUrl = track.artistUrl,
                        durationSec = track.durationSec,
                        thumbUrl = track.thumbUrl,
                        filePath = file.absolutePath,
                        sizeBytes = file.length(),
                        bitrateKbps = resolved.bitrateKbps,
                        downloadedAt = System.currentTimeMillis(),
                    )
                )
                setState(track.id, Progress(fraction = 1f, done = true))
            } catch (e: Exception) {
                if (e.message?.contains("Canceled") == true || e is java.io.InterruptedIOException) {
                    clearState(track.id)
                } else {
                    setState(track.id, Progress(error = e.message ?: "Download failed"))
                }
            } finally {
                synchronized(calls) { calls.remove(track.id) }
                _activeTracks.value = _activeTracks.value - track.id
            }
        }
    }

    fun cancel(videoId: String) {
        synchronized(calls) { calls[videoId]?.cancel() }
        clearState(videoId)
    }

    private fun setState(id: String, p: Progress) {
        _states.value = _states.value + (id to p)
    }

    private fun clearState(id: String) {
        _states.value = _states.value - id
    }
}
