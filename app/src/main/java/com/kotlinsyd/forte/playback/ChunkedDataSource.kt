package com.kotlinsyd.forte.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.kotlinsyd.forte.data.ForteEngine
import java.io.InterruptedIOException

/**
 * Fetches a stream as a run of bounded ranges instead of one
 * open-ended read — the media half of the engine's method, and
 * the direct fix for the v1.0.7 "nothing plays" failure.
 *
 * googlevideo refuses open-ended reads of the VR/Android clients'
 * URLs outright (403), and paces a continuous response down to
 * roughly playback speed where it allows one at all. Bounded
 * ranges are served at line rate. So every read here is split
 * into windows no larger than the minting client's own range size
 * ([ForteEngine.MediaDress.rangeBytesFor]: 512 KiB for VR-class
 * URLs, 1 MiB for the rest), each dressed with that client's
 * headers ([ForteEngine.MediaDress.headersFor], read from the
 * URL's own c= stamp — never a factory user-agent, which the
 * HTTP datasource would append alongside and contradict).
 *
 * Transparent to everything above it: the player sees one
 * continuous stream of the length it asked for. The total size
 * comes from the URL's clen stamp, which every progressive
 * googlevideo URL carries; anything without one (a downloaded
 * file, a non-googlevideo URL) is forwarded untouched.
 *
 * A refusal (403/404/410) from a chunk open is reported to
 * [onRefused] before it propagates, so the engine can forget the
 * URL and bench its client — the recovery rotation then walks to
 * a different rung instead of re-serving the same dead door.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource.Factory,
    private val onRefused: (url: String, responseCode: Int) -> Unit =
        ForteEngine::onPlaybackRefused,
) : DataSource {

    class Factory(
        private val upstream: DataSource.Factory,
        private val onRefused: (url: String, responseCode: Int) -> Unit =
            ForteEngine::onPlaybackRefused,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ChunkedDataSource(upstream, onRefused)
    }

    private var delegate: DataSource? = null
    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false
    private var rangeBytes = Long.MAX_VALUE
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) {
        // Listeners live on the shared delegate instance created per
        // open; ExoPlayer adds its listener before open, so keep it
        // and re-apply to each delegate we spin up.
        pendingListener = transferListener
    }

    private var pendingListener: TransferListener? = null

    private fun newDelegate(): DataSource {
        val d = upstream.createDataSource()
        pendingListener?.let { d.addTransferListener(it) }
        return d
    }

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        position = dataSpec.position
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        if (total == null) {
            passthrough = true
            val d = newDelegate()
            delegate = d
            chunkOpen = true
            return d.open(dressed(dataSpec))
        }
        passthrough = false
        val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            total
        } else {
            minOf(total, position + dataSpec.length)
        }
        bytesRemaining = (end - position).coerceAtLeast(0L)
        rangeBytes = ForteEngine.MediaDress.rangeBytesFor(dataSpec.uri.toString())
        if (bytesRemaining > 0) openChunk()
        return bytesRemaining
    }

    /** The spec for one bounded window: the caller's position and
     * length narrowed to the window, plus the minting client's
     * headers. */
    private fun dressed(spec: DataSpec): DataSpec {
        val headers = ForteEngine.MediaDress.headersFor(spec.uri.toString())
        return spec.buildUpon()
            .setHttpRequestHeaders(headers)
            .build()
    }

    private fun openChunk() {
        val length = minOf(rangeBytes, bytesRemaining)
        val spec = requireNotNull(baseSpec).buildUpon()
            .setPosition(position)
            .setLength(length)
            .build()
        val d = newDelegate()
        try {
            d.open(dressed(spec))
        } catch (e: Exception) {
            if (e !is InterruptedIOException) report(spec, e)
            throw e
        }
        delegate = d
        chunkRemaining = length
        chunkOpen = true
    }

    /** Hand a refusal back to the engine that minted the URL. */
    private fun report(spec: DataSpec, e: Exception) {
        if (e is HttpDataSource.InvalidResponseCodeException) {
            onRefused(spec.uri.toString(), e.responseCode)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return delegate?.read(buffer, offset, length)
            ?: C.RESULT_END_OF_INPUT
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        var attempts = 0
        while (true) {
            if (!chunkOpen || chunkRemaining == 0L) {
                // The window ended (possibly early — a range that
                // ends early is re-opened for the part that didn't
                // arrive, which is also how the step to the next
                // window happens). The attempt cap stops a server
                // that has decided to send nothing from spinning
                // here forever; the player's buffering watchdog is
                // the outer net.
                if (++attempts > 8) throw java.io.IOException(
                    "Stream kept ending early at $position",
                )
                closeChunk()
                if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
                openChunk()
            }
            val toRead = minOf(length.toLong(), chunkRemaining)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val n = delegate?.read(buffer, offset, toRead)
                ?: C.RESULT_END_OF_INPUT
            if (n == C.RESULT_END_OF_INPUT) {
                // Upstream says this window is done, early or not.
                chunkRemaining = 0
                continue
            }
            position += n
            bytesRemaining -= n
            chunkRemaining -= n
            return n
        }
    }

    private fun closeChunk() {
        if (chunkOpen) {
            runCatching { delegate?.close() }
            delegate = null
            chunkOpen = false
        }
    }

    override fun getUri(): Uri? = delegate?.uri ?: baseSpec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        closeChunk()
        passthrough = false
        baseSpec = null
    }
}
