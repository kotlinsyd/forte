package com.kotlinsyd.forte.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import coil.imageLoader
import coil.request.ImageRequest
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.MainActivity
import com.kotlinsyd.forte.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground shell around [PlayerController]: keeps the process alive for
 * background play and mirrors playback into a media notification and the
 * lock-screen session. All playback decisions stay in the controller.
 */
class PlayerService : Service() {

    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var artworkFor: String? = null
    private var artwork: Bitmap? = null
    /** True once startForeground() has landed for the current foreground
     * stint; reset whenever update() drops back out of foreground. */
    private var foreground = false
    /** Last widget state pushed — playback state flows every 500 ms,
     * and the widget must not be rebuilt at that rate. */
    private var widgetKey: String? = null

    private val controller get() = ForteApp.instance.player

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
        )
        // FORTE-2 fix: start() reaches us via startForegroundService,
        // which gives the service ~5 s to call startForeground() or
        // Android kills the app (RemoteServiceException). The rich
        // notification in update() can only be built after the track's
        // artwork has been fetched over the network — far too late to
        // be the first foreground call. Go foreground NOW with a
        // minimal notification; update() swaps in the rich one (same
        // NOTIF_ID) as soon as a track is ready.
        ensureForeground()
        // NB: inside apply{} the name "controller" means the session's
        // MediaControllerCompat, so capture ours under a different name.
        val ctl = controller
        session = MediaSessionCompat(this, "Forte").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = ctl.resume()
                override fun onPause() = ctl.pause()
                override fun onSkipToNext() = ctl.next(manual = true)
                override fun onSkipToPrevious() = ctl.previous()
                override fun onSeekTo(pos: Long) = ctl.seekTo(pos)
                override fun onStop() = ctl.stopAndClear()
            })
            isActive = true
        }
        controller.onPlaybackStopped = {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        scope.launch {
            controller.state.collectLatest { update(it) }
        }
        scope.launch {
            ForteApp.instance.appForeground.collectLatest { foreground ->
                if (!foreground &&
                    !ForteApp.instance.settings.settings.value.backgroundPlay &&
                    controller.state.value.playing
                ) {
                    controller.pause()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A (re)start after update() dropped out of foreground (no
        // track) re-arms the same 5 s deadline — re-post the minimal
        // notification synchronously before handling anything else.
        // While a track's rich notification is up this is a no-op, so
        // media-button/widget restarts never clobber it.
        ensureForeground()
        // Media-button intents arrive via the manifest MediaButtonReceiver,
        // which restarts this service with the key event attached.
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON) {
            @Suppress("DEPRECATION")
            val event = intent.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
            if (event != null) session.controller.dispatchMediaButtonEvent(event)
        }
        // Home-screen widget buttons (see ForteWidget).
        when (intent?.action) {
            com.kotlinsyd.forte.widget.ForteWidget.ACTION_TOGGLE ->
                controller.togglePlayPause()
            com.kotlinsyd.forte.widget.ForteWidget.ACTION_NEXT ->
                controller.next(manual = true)
            com.kotlinsyd.forte.widget.ForteWidget.ACTION_PREV ->
                controller.previous()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private suspend fun update(state: PlaybackState) {
        val track = state.current
        if (track == null) {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        if (artworkFor != track.id) {
            artworkFor = track.id
            artwork = track.thumbUrl?.let { url ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        // The app-wide singleton — never a second loader:
                        // two image caches on the same directory can
                        // corrupt each other's journal.
                        val loader = this@PlayerService.imageLoader
                        val result = loader.execute(
                            ImageRequest.Builder(this@PlayerService)
                                .data(com.kotlinsyd.forte.data.bigArtwork(url)).build()
                        )
                        (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    }.getOrNull()
                }
            }
        }
        // Keep the home-screen widget in step with the notification —
        // but only when what it shows actually changed.
        val key = "${track.id}|${state.playing}|${artwork != null}"
        if (key != widgetKey) {
            widgetKey = key
            com.kotlinsyd.forte.widget.ForteWidget.push(
                this, track.title, track.artist, state.playing, artwork,
            )
        }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
                .apply { artwork?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        val pbState = if (state.buffering) PlaybackStateCompat.STATE_BUFFERING
            else if (state.playing) PlaybackStateCompat.STATE_PLAYING
            else PlaybackStateCompat.STATE_PAUSED
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_STOP
                )
                .setState(pbState, state.positionMs, 1f)
                .build()
        )

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // A notification problem must never kill playback: if the rich
        // notification fails to build, fall back to a minimal one so the
        // foreground-service start still lands.
        val notification = runCatching {
            NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(track.title)
            .setContentText(track.artist)
            .setLargeIcon(artwork)
            .setContentIntent(contentIntent)
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_STOP
                )
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_prev, "Previous",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                )
            )
            .addAction(
                NotificationCompat.Action(
                    if (state.playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (state.playing) "Pause" else "Play",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        if (state.playing) PlaybackStateCompat.ACTION_PAUSE
                        else PlaybackStateCompat.ACTION_PLAY
                    )
                )
            )
            .addAction(
                NotificationCompat.Action(
                    R.drawable.ic_next, "Next",
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    )
                )
            )
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(state.playing)
            .build()
        }.getOrElse {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(track.title)
                .setContentText(track.artist)
                .setContentIntent(contentIntent)
                .build()
        }
        startForeground(NOTIF_ID, notification)
        foreground = true
    }

    /**
     * Post the minimal foreground notification if we are not already
     * foreground. Synchronous and network-free, so it always lands
     * inside startForegroundService's deadline.
     */
    private fun ensureForeground() {
        if (foreground) return
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startForeground(
            NOTIF_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Preparing playback")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build()
        )
        foreground = true
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, PlayerService::class.java)
            )
        }
    }
}
