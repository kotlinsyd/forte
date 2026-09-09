package com.kotlinsyd.forte.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kotlinsyd.forte.data.ForteEngine
import com.kotlinsyd.forte.data.Library
import com.kotlinsyd.forte.data.SettingsStore
import com.kotlinsyd.forte.data.Track
import com.kotlinsyd.forte.data.YtAccount
import com.kotlinsyd.forte.data.withArtworkFloor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

enum class RepeatMode { OFF, ALL, ONE }

data class PlaybackState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val error: String? = null,
    /** True while the queue is a radio station (endless similar songs). */
    val radio: Boolean = false,
    /** Where this queue came from, in words the player can show. */
    val origin: String? = null,
    /** The audio output the listener picked, when they picked one. */
    val outputName: String? = null,
    /** Playback speed multiplier (1f = normal). */
    val speed: Float = 1f,
    /** What is actually streaming (codec · bitrate) or "Saved file"
     * for downloads — shown on the player's info line. */
    val streamInfo: String? = null,
    /** Sleep timer deadline (epoch ms) when a timed timer is armed. */
    val sleepEndsAtMs: Long = 0,
    /** Sleep timer set to stop at the end of the current track. */
    val sleepAtTrackEnd: Boolean = false,
) {
    val current: Track? get() = queue.getOrNull(index)
    val sleepArmed: Boolean get() = sleepEndsAtMs > 0 || sleepAtTrackEnd
}

/**
 * Owns the ExoPlayer and the queue. The foreground service is just a shell
 * around this — notification, media session — while every playback decision
 * (what plays next, shuffle, repeat, re-resolving an expired URL) lives here.
 */
/** One real audio output Android reports for this phone. */
data class AudioOutput(
    val id: Int,
    val name: String,
    val kind: String,
)

class PlayerController(
    private val context: Context,
    private val library: Library,
    private val settings: SettingsStore,
) {
    val state = MutableStateFlow(PlaybackState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var resolveJob: Job? = null

    // Recovery rotation (v1.0.8): the ladder rungs already tried
    // for the current track. A dead OR hung load re-resolves
    // through the NEXT rung — refreshed visitorData, position
    // preserved — once per rung, and only surfaces an error when
    // every rung is spent. (The v1.0.7 build retried the same
    // door once and called that recovery.)
    private val attemptedClients = mutableSetOf<String>()
    private var servingClientId: String? = null

    // Buffering watchdog markers: the buffered position and when
    // it last moved. A load that hangs (the v1.0.7 failure) never
    // errors — it just never advances — so the watchdog treats
    // 20 s without buffered progress as a failure and rotates.
    private var lastBufferedPosMs = -1L
    private var lastAdvanceAtMs = 0L

    // Crossfade (fade-through): a single player can't overlap two
    // streams, so the outgoing song ramps down over the last seconds
    // and the incoming one ramps up — the standard single-player
    // crossfade. [fadingOutFor] is the media id the ramp-out belongs
    // to, so a stale ramp never touches a newer track.
    private var fadeJob: Job? = null
    private var fadingOutFor: String? = null
    private var hadPreviousTrack = false

    // Sleep timer.
    private var sleepJob: Job? = null

    // Session memory: where the listener left off, restored paused.
    private var pendingSeekMs: Long = 0
    private var pollTicks = 0

    // Radio bookkeeping. [radioGeneration] invalidates in-flight radio
    // fetches whenever the queue is replaced, so a stale station can
    // never append tracks to a queue it no longer owns.
    private var radioGeneration = 0
    private var radioSeedId: String? = null
    private var radioSeedArtist: String = ""
    private var radioContinuation: String? = null
    private var radioJob: Job? = null
    private var radioRefillActive = false

    /** Set by the app: starts the foreground service when playback begins. */
    var ensureService: () -> Unit = {}

    /** Set by the service: called when the queue is exhausted or cleared. */
    var onPlaybackStopped: () -> Unit = {}

    val player: ExoPlayer

    init {
        val httpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        // Media fetches go out dressed as the client that minted
        // each URL, in bounded ranges (ChunkedDataSource) — an
        // open-ended read with a default user-agent is the exact
        // shape googlevideo refused for every song in v1.0.7.
        // DefaultDataSource dispatches by scheme so downloaded
        // files still play from disk untouched.
        val dsFactory = androidx.media3.datasource.DefaultDataSource.Factory(
            context,
            ChunkedDataSource.Factory(OkHttpDataSource.Factory(httpClient)),
        )
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(dsFactory))
            .setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.value = state.value.copy(playing = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> state.value = state.value.copy(buffering = true)
                    Player.STATE_READY -> {
                        lastAdvanceAtMs = System.currentTimeMillis()
                        lastBufferedPosMs = player.bufferedPosition
                        state.value = state.value.copy(
                            buffering = false,
                            durationMs = player.duration.coerceAtLeast(0),
                        )
                    }
                    Player.STATE_ENDED -> onTrackEnded()
                    Player.STATE_IDLE -> state.value = state.value.copy(buffering = false)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // A playback failure (403/410/network) rotates the
                // track to the next ladder rung — see
                // [recoverFromFailure]. Nothing is retried through
                // the door that just failed.
                recoverFromFailure()
            }
        })
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                AudioEffects.apply(audioSessionId, settings.settings.value)
            }
        })
        scope.launch {
            while (true) {
                if (player.isPlaying) {
                    // Publish a consistent pair: while a stream is still
                    // preparing, ExoPlayer can report a position past a
                    // not-yet-known duration — the UI must never see
                    // position > duration.
                    val dur = player.duration.coerceAtLeast(0)
                    val pos = player.currentPosition.coerceAtLeast(0)
                    state.value = state.value.copy(
                        positionMs = if (dur > 0) pos.coerceAtMost(dur) else pos,
                        durationMs = dur,
                    )
                    maybeStartFadeOut(dur, pos)
                }
                // Session position checkpoint every ~10 s of polling.
                if (++pollTicks % 20 == 0 && state.value.current != null) {
                    saveSession()
                }
                // Buffering watchdog: a load that HANGS never calls
                // onPlayerError — it sits in STATE_BUFFERING while
                // the buffered position never moves. Twenty seconds
                // of no buffered progress is a failure: rotate to
                // the next rung (resolve attempts are exempt — the
                // resolve job is the thing running then).
                val bs = state.value
                if (bs.buffering && bs.current != null) {
                    val bp = player.bufferedPosition
                    if (bp != lastBufferedPosMs) {
                        lastBufferedPosMs = bp
                        lastAdvanceAtMs = System.currentTimeMillis()
                    } else if (
                        resolveJob?.isActive != true &&
                        System.currentTimeMillis() - lastAdvanceAtMs > 20_000
                    ) {
                        recoverFromFailure()
                    }
                } else {
                    lastBufferedPosMs = player.bufferedPosition
                    lastAdvanceAtMs = System.currentTimeMillis()
                }
                delay(500)
            }
        }
    }

    // ------------------------------------------------------------------

    fun playQueue(tracks: List<Track>, startIndex: Int, origin: String? = null) {
        if (tracks.isEmpty()) return
        ensureService()
        // A normal pick always ends any radio station.
        endRadio()
        // Artwork floor at the queue door: no entry may sit in the
        // queue pictureless while its video frame exists; entries
        // that arrived blank are upgraded in the background.
        val floored = tracks.map { it.withArtworkFloor() }
        state.value = state.value.copy(
            queue = floored,
            index = startIndex.coerceIn(0, floored.lastIndex),
            error = null,
            radio = false,
            origin = origin ?: if (tracks.size == 1) "Single song" else "Queue",
        )
        resetAttempts()
        loadCurrent()
        upgradeBlankArtwork(tracks)
    }

    /** The outputs Android actually reports, in a stable order. */
    fun audioOutputs(): List<AudioOutput> {
        val manager = context.getSystemService(AudioManager::class.java)
            ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { device ->
                val kind = when (device.type) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
                    AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
                    else -> "Audio output"
                }
                val rawName = device.productName?.toString()?.trim().orEmpty()
                AudioOutput(
                    id = device.id,
                    name = rawName.ifBlank { kind },
                    kind = kind,
                )
            }
            .distinctBy { it.id }
    }

    /** Route playback to one reported output. Returns false when
     * Android no longer reports that device. */
    fun selectAudioOutput(id: Int): Boolean {
        val manager = context.getSystemService(AudioManager::class.java)
            ?: return false
        val device = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.id == id } ?: return false
        player.setPreferredAudioDevice(device)
        val output = audioOutputs().firstOrNull { it.id == id }
        state.value = state.value.copy(outputName = output?.name)
        saveSession()
        return true
    }

    /** Return routing to Android's own automatic choice. */
    fun clearAudioOutput() {
        player.setPreferredAudioDevice(null)
        state.value = state.value.copy(outputName = null)
        saveSession()
    }

    fun play(track: Track) = playQueue(listOf(track), 0)

    /**
     * Background upgrade for queue entries that arrived with NO
     * artwork: the frame floor is already under them (see
     * [playQueue]); here the best art the service offers for the
     * video replaces the floor — patched into the live queue so
     * the player repaints, and persisted into every stored row
     * (history, favorites, playlist tracks, downloads) so the fix
     * survives restarts. Fully fenced: a failure leaves the floor.
     */
    private fun upgradeBlankArtwork(originals: List<Track>) {
        val blanks = originals.filter { it.thumbUrl.isNullOrBlank() }
        if (blanks.isEmpty()) return
        scope.launch {
            for (t in blanks) {
                val url = runCatching { YtAccount.trackArtwork(t.id) }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: continue
                val s = state.value
                if (s.queue.any { it.id == t.id && it.thumbUrl != url }) {
                    state.value = s.copy(
                        queue = s.queue.map {
                            if (it.id == t.id) it.copy(thumbUrl = url) else it
                        },
                    )
                }
                runCatching { library.persistArtwork(t.id, url) }
            }
        }
    }

    private fun endRadio() {
        radioGeneration++
        radioSeedId = null
        radioSeedArtist = ""
        radioContinuation = null
        radioJob?.cancel()
        radioJob = null
        radioRefillActive = false
    }

    /**
     * Start radio: play [seed] now and grow the queue behind it with
     * YouTube's radio station for that song (the RD automix). The queue
     * refills from the station's continuation as it nears the end, so
     * playback is effectively non-stop until the user picks something
     * else — any normal play ends the station.
     */
    fun startRadio(seed: Track) {
        ensureService()
        endRadio()
        val gen = radioGeneration
        radioSeedId = seed.id
        radioSeedArtist = seed.artist
        val flooredSeed = seed.withArtworkFloor()
        state.value = state.value.copy(
            queue = listOf(flooredSeed),
            index = 0,
            error = null,
            radio = true,
            origin = "Radio · ${seed.title}",
        )
        resetAttempts()
        loadCurrent()
        upgradeBlankArtwork(listOf(seed))
        radioJob = scope.launch {
            val page = runCatching { YtAccount.radioStart(seed.id) }.getOrNull()
            if (gen != radioGeneration) return@launch
            // Backup lane if the radio endpoint ever refuses: the seed's
            // own watch-next relatives — still real YouTube data.
            val tracks = page?.tracks?.takeIf { it.isNotEmpty() }
                ?: runCatching { ForteEngine.related(seed.id) }.getOrDefault(emptyList())
            radioContinuation = page?.continuation
            val fresh = radioFilter(listOf(seed), tracks, seed.artist)
                .filter { it.id != seed.id }
                .map { it.withArtworkFloor() }
            val s = state.value
            if (s.radio && fresh.isNotEmpty()) {
                state.value = s.copy(queue = s.queue + fresh)
            }
            maybeRefillRadio()
        }
    }

    /**
     * Radio queue hygiene: dedupe by normalised
     * title+artist — not just video id, so the same song from a
     * different upload can't repeat — and cap how much of one
     * artist a station may hold (the seed artist gets more room).
     */
    private fun radioFilter(
        existing: List<Track>,
        fresh: List<Track>,
        seedArtist: String,
    ): List<Track> {
        fun normKey(t: Track): String =
            t.title.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim() +
                "|" + t.artist.lowercase().trim()
        val seen = existing.map { normKey(it) }.toHashSet()
        val artistCounts = existing
            .groupingBy { it.artist.lowercase().trim() }.eachCount().toMutableMap()
        val seedKey = seedArtist.lowercase().trim()
        val out = mutableListOf<Track>()
        for (t in fresh) {
            val key = normKey(t)
            if (key in seen) continue
            val artist = t.artist.lowercase().trim()
            val cap = if (artist == seedKey) 4 else 2
            if ((artistCounts[artist] ?: 0) >= cap) continue
            seen += key
            artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
            out += t
        }
        return out
    }

    /** Refill the station when the listener gets near the queue's end. */
    private fun maybeRefillRadio() {
        val s = state.value
        if (!s.radio || radioRefillActive) return
        val token = radioContinuation ?: return
        val seedId = radioSeedId ?: return
        if (s.queue.size - s.index > RADIO_REFILL_AHEAD) return
        val gen = radioGeneration
        radioRefillActive = true
        scope.launch {
            try {
                var pages = 0
                while (gen == radioGeneration && pages < 3) {
                    val cur = radioContinuation ?: break
                    val page = runCatching { YtAccount.radioMore(seedId, cur) }
                        .getOrNull() ?: break
                    radioContinuation = page.continuation
                    val st = state.value
                    if (!st.radio) break
                    val known = st.queue.map { it.id }.toHashSet()
                    val fresh = radioFilter(st.queue, page.tracks, radioSeedArtist)
                        .filter { it.id !in known }
                        .map { it.withArtworkFloor() }
                    if (fresh.isEmpty()) break
                    val endedAtLast = player.playbackState == Player.STATE_ENDED &&
                        st.index >= st.queue.lastIndex
                    state.value = st.copy(queue = st.queue + fresh)
                    if (endedAtLast) {
                        // The refill landed just after the station ran
                        // dry: step into the fresh tracks instead of
                        // sitting stopped.
                        next(manual = false)
                    }
                    pages++
                    val now = state.value
                    if (now.queue.size - now.index > RADIO_REFILL_AHEAD) break
                    if (radioContinuation == null) break
                }
            } finally {
                if (gen == radioGeneration) radioRefillActive = false
            }
        }
    }

    private fun resetAttempts() {
        attemptedClients.clear()
        servingClientId = null
    }

    /**
     * The recovery net: the current track has failed in playback
     * (an HTTP refusal, a network death, or a hang the watchdog
     * caught). Re-resolve it through the next ladder rung —
     * attempted rungs excluded, visitorData refreshed — keeping
     * the listener's place. Only when every rung (4 clients + the
     * cipher rung) has been tried does the track surface an error.
     */
    private fun recoverFromFailure() {
        val track = state.value.current ?: return
        servingClientId?.let { attemptedClients += it }
        if (attemptedClients.size >= LADDER_RUNGS) {
            state.value = state.value.copy(
                buffering = false,
                error = "Couldn't play \"${track.title}\"",
            )
            return
        }
        val pos = runCatching { player.currentPosition }.getOrDefault(0L)
        if (pos > 0) pendingSeekMs = pos
        ForteEngine.invalidate(track.id)
        lastAdvanceAtMs = System.currentTimeMillis()
        loadCurrent()
    }

    private fun loadCurrent() {
        val track = state.value.current ?: return
        maybeRefillRadio()
        resolveJob?.cancel()
        // Fast switching: the old song stops NOW, the moment the new
        // one is chosen — it never plays on while the new stream
        // resolves. The UI shows the buffering state instead.
        fadeJob?.cancel()
        fadingOutFor = null
        if (player.playbackState != Player.STATE_IDLE) {
            player.stop()
        }
        player.volume = 1f
        state.value = state.value.copy(
            buffering = true, playing = false, positionMs = 0, durationMs = 0,
        )
        saveSession()
        resolveJob = scope.launch {
            player.skipSilenceEnabled = settings.settings.value.skipSilence
            var streamLabel: String? = null
            val uri = try {
                val local = library.downloadedFile(track.id)
                if (local != null) {
                    streamLabel = "Saved file · offline"
                    android.net.Uri.fromFile(File(local)).toString()
                } else {
                    val lowData = settings.settings.value.dataSaver && isMetered()
                    // Forte's own engine — the only engine. On a
                    // recovery rotation the rungs already tried are
                    // excluded and the visitor id is refreshed.
                    val resolved = ForteEngine.resolve(
                        videoId = track.id,
                        quality = effectiveQuality(),
                        lowData = lowData,
                        excludeClients = attemptedClients,
                        refreshVisitor = attemptedClients.isNotEmpty(),
                    )
                    servingClientId = resolved.clientId
                    if (resolved.clientId.isNotBlank()) {
                        attemptedClients += resolved.clientId
                    }
                    streamLabel = listOf(
                        resolved.format,
                        // A genuinely unknown bitrate is omitted,
                        // never printed as "0 kbps".
                        if (resolved.bitrateKbps > 0) "${resolved.bitrateKbps} kbps" else "",
                        // Which engine served this track.
                        resolved.engine,
                    ).filter { it.isNotBlank() }.joinToString(" · ")
                    resolved.url
                }
            } catch (e: Exception) {
                state.value = state.value.copy(
                    buffering = false,
                    error = "Couldn't load \"${track.title}\"",
                )
                return@launch
            }
            lastBufferedPosMs = -1L
            lastAdvanceAtMs = System.currentTimeMillis()
            val fadeInMs = if (hadPreviousTrack) {
                settings.settings.value.crossfadeSec * 1000L
            } else 0L
            if (fadeInMs > 0) player.volume = 0f
            state.value = state.value.copy(streamInfo = streamLabel)
            player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(track.id).build())
            player.prepare()
            if (pendingSeekMs > 0) {
                player.seekTo(pendingSeekMs)
                pendingSeekMs = 0
            }
            player.playWhenReady = true
            hadPreviousTrack = true
            if (fadeInMs > 0) rampVolume(1f, fadeInMs)
            withContext(Dispatchers.IO) { library.recordPlay(track) }
            // Account history write-back (signed-in only, best effort)
            // and next-track pre-resolution run quietly behind.
            scope.launch(Dispatchers.IO) { YtAccount.registerWatch(track.id) }
            prefetchNext()
        }
    }

    /** Warm the stream cache for the track most likely to play next,
     * so auto-advance and manual skips land without a cold resolve. */
    private fun prefetchNext() {
        val s = state.value
        val candidate = s.queue.getOrNull(s.index + 1)
            ?: if (s.repeat == RepeatMode.ALL) s.queue.firstOrNull() else null
        val next = candidate ?: return
        if (next.id == s.current?.id) return
        val lowData = settings.settings.value.dataSaver && isMetered()
        scope.launch(Dispatchers.IO) {
            if (library.downloadedFile(next.id) == null) {
                // Pre-resolve through the same door playback uses,
                // so the next track's probe has already passed when
                // the listener reaches it.
                ForteEngine.prefetch(next.id, effectiveQuality(), lowData)
            }
        }
    }

    // ------------------------------------------------------------------
    // Crossfade ramps
    // ------------------------------------------------------------------

    private fun rampVolume(to: Float, overMs: Long) {
        fadeJob?.cancel()
        val from = player.volume
        if (overMs <= 0) {
            player.volume = to
            return
        }
        fadeJob = scope.launch {
            val steps = (overMs / 50).coerceIn(1, 200).toInt()
            for (i in 1..steps) {
                player.volume = from + (to - from) * (i.toFloat() / steps)
                delay(50)
            }
            player.volume = to
        }
    }

    /** Begin the fade-out when the current song enters the crossfade
     * window and another song will follow it. */
    private fun maybeStartFadeOut(durationMs: Long, positionMs: Long) {
        val fadeSec = settings.settings.value.crossfadeSec
        if (fadeSec <= 0 || durationMs <= 0) return
        val s = state.value
        val track = s.current ?: return
        if (s.repeat == RepeatMode.ONE) return
        val hasNext = s.index < s.queue.lastIndex ||
            s.repeat == RepeatMode.ALL || s.shuffle
        if (!hasNext) return
        val remaining = durationMs - positionMs
        if (remaining in 1..(fadeSec * 1000L) && fadingOutFor != track.id) {
            fadingOutFor = track.id
            rampVolume(0f, remaining)
        }
    }

    // ------------------------------------------------------------------
    // Sleep timer
    // ------------------------------------------------------------------

    /** Arm a timed sleep timer. Rule: playback fades out over the
     * last ~5 seconds and pauses; volume is restored for the next
     * manual play so nothing ever starts silent. */
    fun armSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        state.value = state.value.copy(sleepEndsAtMs = endsAt, sleepAtTrackEnd = false)
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            rampVolume(0f, 5_000)
            delay(5_200)
            player.pause()
            player.volume = 1f
            state.value = state.value.copy(sleepEndsAtMs = 0)
        }
    }

    fun armSleepEndOfTrack() {
        sleepJob?.cancel()
        state.value = state.value.copy(sleepEndsAtMs = 0, sleepAtTrackEnd = true)
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        if (state.value.sleepEndsAtMs > 0 || state.value.sleepAtTrackEnd) {
            state.value = state.value.copy(sleepEndsAtMs = 0, sleepAtTrackEnd = false)
        }
    }

    // ------------------------------------------------------------------
    // Session memory (queue + position, restored paused on relaunch)
    // ------------------------------------------------------------------

    private val sessionPrefs by lazy {
        context.getSharedPreferences("forte_session", Context.MODE_PRIVATE)
    }

    private fun saveSession() {
        val s = state.value
        if (s.queue.isEmpty()) return
        runCatching {
            val arr = org.json.JSONArray()
            s.queue.forEach { t ->
                arr.put(
                    org.json.JSONObject()
                        .put("id", t.id).put("title", t.title).put("artist", t.artist)
                        .put("artistUrl", t.artistUrl ?: org.json.JSONObject.NULL)
                        .put("durationSec", t.durationSec)
                        .put("thumbUrl", t.thumbUrl ?: org.json.JSONObject.NULL)
                        .put("viewCount", t.viewCount),
                )
            }
            sessionPrefs.edit()
                .putString("queue", arr.toString())
                .putInt("index", s.index)
                .putLong("positionMs", s.positionMs)
                .putBoolean("radio", s.radio)
                .putString("origin", s.origin)
                .putString("radioSeed", radioSeedId)
                .putString("radioContinuation", radioContinuation)
                .putBoolean("shuffle", s.shuffle)
                .apply()
        }
    }

    /** Restore the last session paused, exactly where it stopped.
     * Called once at startup; the stream itself resolves lazily when
     * the user presses play (pending seek applied on that load). */
    fun restoreSession() {
        if (state.value.current != null) return
        runCatching {
            val raw = sessionPrefs.getString("queue", null) ?: return
            val arr = org.json.JSONArray(raw)
            val queue = mutableListOf<Track>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: continue
                queue += Track(
                    id = id,
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    artistUrl = o.optString("artistUrl").takeIf { it.isNotBlank() },
                    durationSec = o.optLong("durationSec"),
                    thumbUrl = o.optString("thumbUrl").takeIf { it.isNotBlank() },
                    viewCount = o.optLong("viewCount", -1),
                ).withArtworkFloor()
            }
            if (queue.isEmpty()) return
            val index = sessionPrefs.getInt("index", 0).coerceIn(0, queue.lastIndex)
            val wasRadio = sessionPrefs.getBoolean("radio", false)
            if (wasRadio) {
                radioSeedId = sessionPrefs.getString("radioSeed", null)
                radioContinuation = sessionPrefs.getString("radioContinuation", null)
            }
            pendingSeekMs = sessionPrefs.getLong("positionMs", 0)
            state.value = state.value.copy(
                queue = queue,
                index = index,
                positionMs = pendingSeekMs,
                shuffle = sessionPrefs.getBoolean("shuffle", false),
                radio = wasRadio,
                origin = sessionPrefs.getString("origin", null)
                    ?.takeIf { it.isNotBlank() } ?: "Your last session",
            )
            hadPreviousTrack = true
        }
    }

    private fun clearSession() {
        runCatching { sessionPrefs.edit().clear().apply() }
    }

    /** Re-apply the equalizer settings (preset/bass/on-off changed). */
    fun refreshEffects() {
        AudioEffects.apply(player.audioSessionId, settings.settings.value)
    }

    private fun isMetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.isActiveNetworkMetered
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * The streaming quality for THIS play: the per-network ceiling
     * when one is set (Settings → Audio quality: On Wi-Fi / On
     * mobile data), else the base quality. Data saver still forces
     * the low lane on metered networks at resolve time.
     */
    fun effectiveQuality(): com.kotlinsyd.forte.data.AudioQuality {
        val s = settings.settings.value
        return when {
            isOnWifi() -> s.wifiQuality ?: s.audioQuality
            isMetered() -> s.mobileQuality ?: s.audioQuality
            else -> s.audioQuality
        }
    }

    /** Playback speed (0.5×–2×), applied live and kept in state so
     * the player UI can show the active rate. */
    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed.coerceIn(0.5f, 2f))
        state.value = state.value.copy(speed = speed.coerceIn(0.5f, 2f))
    }

    /**
     * Hand off to the system audio-effects panel (the device's own
     * equalizer) for this player's audio session. Some devices have
     * no panel — callers must treat a failed start as normal.
     */
    fun systemEqIntent(): android.content.Intent =
        android.content.Intent(
            android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL,
        ).apply {
            putExtra(
                android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME,
                context.packageName,
            )
            putExtra(
                android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION,
                player.audioSessionId,
            )
            putExtra(
                android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE,
                android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC,
            )
        }

    fun togglePlayPause() {
        if (player.isPlaying) {
            player.pause()
        } else if (state.value.current != null) {
            // Covers the restored-session case: pressing play on the
            // mini strip after a relaunch must start the service too.
            ensureService()
            if (player.playbackState == Player.STATE_IDLE ||
                player.playbackState == Player.STATE_ENDED
            ) {
                resetAttempts()
                loadCurrent()
            } else {
                player.play()
            }
        }
    }

    fun resume() {
        if (state.value.current == null) return
        ensureService()
        if (player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        ) {
            resetAttempts()
            loadCurrent()
        } else {
            player.play()
        }
    }

    fun pause() {
        // A pause cancels any crossfade ramp and restores full volume,
        // so a later resume never starts silent.
        fadeJob?.cancel()
        fadingOutFor = null
        player.volume = 1f
        player.pause()
        saveSession()
    }

    fun next(manual: Boolean) {
        val s = state.value
        if (s.queue.isEmpty()) return
        val nextIndex = when {
            s.shuffle && s.queue.size > 1 -> {
                var n = s.index
                while (n == s.index) n = s.queue.indices.random()
                n
            }
            s.index + 1 <= s.queue.lastIndex -> s.index + 1
            manual || s.repeat == RepeatMode.ALL -> 0
            else -> return
        }
        state.value = s.copy(index = nextIndex, error = null)
        resetAttempts()
        loadCurrent()
    }

    fun previous() {
        val s = state.value
        if (s.queue.isEmpty()) return
        // Ten seconds in, "back" means "hear this song again"; only
        // near the start does it mean the previous song.
        if (player.currentPosition > 10_000) {
            player.seekTo(0)
            return
        }
        val prevIndex = if (s.index - 1 >= 0) s.index - 1 else s.queue.lastIndex
        state.value = s.copy(index = prevIndex, error = null)
        resetAttempts()
        loadCurrent()
    }

    fun jumpTo(index: Int) {
        val s = state.value
        if (index !in s.queue.indices) return
        state.value = s.copy(index = index, error = null)
        resetAttempts()
        loadCurrent()
    }

    fun removeAt(index: Int) {
        val s = state.value
        if (index !in s.queue.indices) return
        val newQueue = s.queue.toMutableList().also { it.removeAt(index) }
        when {
            newQueue.isEmpty() -> stopAndClear()
            index < s.index -> state.value = s.copy(queue = newQueue, index = s.index - 1)
            index == s.index -> {
                state.value = s.copy(
                    queue = newQueue,
                    index = index.coerceAtMost(newQueue.lastIndex),
                    error = null,
                )
                resetAttempts()
                loadCurrent()
            }
            else -> state.value = s.copy(queue = newQueue)
        }
    }

    fun enqueueNext(track: Track) {
        val s = state.value
        if (s.queue.isEmpty()) {
            play(track)
            return
        }
        val newQueue = s.queue.toMutableList().also { it.add(s.index + 1, track) }
        state.value = s.copy(queue = newQueue)
    }

    fun addToQueue(track: Track) {
        val s = state.value
        if (s.queue.isEmpty()) {
            play(track)
            return
        }
        state.value = s.copy(queue = s.queue + track)
    }

    fun seekTo(ms: Long) {
        player.seekTo(ms)
        val dur = state.value.durationMs
        state.value = state.value.copy(
            positionMs = if (dur > 0) ms.coerceIn(0, dur) else ms.coerceAtLeast(0),
        )
    }

    fun setShuffle(on: Boolean) {
        state.value = state.value.copy(shuffle = on)
    }

    fun cycleRepeat() {
        val next = when (state.value.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        state.value = state.value.copy(repeat = next)
        player.repeatMode = if (next == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    private fun onTrackEnded() {
        val s = state.value
        if (s.repeat == RepeatMode.ONE) return // player loops natively
        if (s.sleepAtTrackEnd) {
            // Sleep timer (end of track): stop here instead of
            // advancing, and disarm.
            state.value = s.copy(sleepAtTrackEnd = false, playing = false, positionMs = 0)
            player.seekTo(0)
            player.pause()
            player.volume = 1f
            onPlaybackStopped()
            return
        }
        if (s.radio) maybeRefillRadio()
        if (s.index < s.queue.lastIndex || s.repeat == RepeatMode.ALL || s.shuffle) {
            next(manual = false)
        } else {
            state.value = s.copy(playing = false, positionMs = 0)
            player.seekTo(0)
            player.pause()
            onPlaybackStopped()
        }
    }

    fun stopAndClear() {
        endRadio()
        cancelSleepTimer()
        fadeJob?.cancel()
        resolveJob?.cancel()
        player.stop()
        player.clearMediaItems()
        player.volume = 1f
        hadPreviousTrack = false
        state.value = PlaybackState()
        clearSession()
        onPlaybackStopped()
    }

    fun clearError() {
        state.value = state.value.copy(error = null)
    }

    private companion object {
        /** Refill the station once this many tracks (or fewer) remain. */
        const val RADIO_REFILL_AHEAD = 10

        /** Ladder rungs a recovery rotation may spend per track:
         * the four direct clients plus the cipher rung. */
        const val LADDER_RUNGS = 5
    }
}
