package com.kotlinsyd.forte

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kotlinsyd.forte.data.AppStats
import com.kotlinsyd.forte.data.CrashReports
import com.kotlinsyd.forte.data.DownloadManager
import com.kotlinsyd.forte.data.Library
import com.kotlinsyd.forte.data.SettingsStore
import com.kotlinsyd.forte.playback.PlayerController
import com.kotlinsyd.forte.playback.PlayerService
import com.kotlinsyd.forte.ui.ForteColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** App-wide singletons, kept deliberately simple: one library, one settings
 * store, one player. No DI framework needed at this size. */
class ForteApp : Application(), ImageLoaderFactory {

    /** Bounded image caches (memory discipline): artwork is downsampled
     * by Coil to the view size anyway; these caps stop the caches from
     * growing without limit on long sessions. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()

    lateinit var library: Library
    lateinit var settings: SettingsStore
    lateinit var downloads: DownloadManager
    lateinit var player: PlayerController

    /** True while any activity is in the foreground; drives the
     * "background play" setting. */
    val appForeground = MutableStateFlow(true)

    /** Bumped whenever the YouTube account session changes (sign-in
     * confirmed / signed out) so account-aware screens re-read it. */
    val accountVersion = MutableStateFlow(0)

    /** The playing song's artwork colour, published by the player so
     * the rest of the app (mini strip, headers) can breathe with it.
     * Ambience only — Onyx + red stays the identity. */
    val nowPlayingTint = MutableStateFlow(ForteColors.Bg)

    /** Ids of the signed-in account's YouTube liked songs, so like
     * state in the UI reflects the account, not just this phone. */
    val ytLikedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Short-lived honest message for account actions (a like that
     * YouTube refused, a playlist add that failed). Null = nothing. */
    val userMessage = MutableStateFlow<String?>(null)

    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        library = Library(this)
        settings = SettingsStore(this)
        // Region first: every InnerTube context reads RegionState.
        com.kotlinsyd.forte.data.RegionState.current = settings.region()
        // Crash reporting first among the optional systems: if anything
        // below ever fails at startup, the report is already armed.
        CrashReports.initIfEnabled(this, settings)
        player = PlayerController(this, library, settings)
        player.ensureService = { PlayerService.start(this) }
        downloads = DownloadManager(this, library, settings)
        // Session memory: bring back the queue exactly where he left
        // it — paused, stream resolving lazily on first play.
        player.restoreSession()
        refreshYtLiked()
        // Artwork backfill: rows saved with no picture (old history,
        // radio plays) resolve their real posters once, in the
        // background — never on the first-paint path.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { library.backfillArtwork() }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                appForeground.value = startedActivities > 0
                if (startedActivities == 1) {
                    // Install/session counting in Forte's own stats lane.
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        runCatching { AppStats.trackLaunch(this@ForteApp) }
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                appForeground.value = startedActivities > 0
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })

    }

    /** Refresh the account liked-ids cache (sign-in, sign-out, and
     * after a like/unlike lands). Signed-out = empty set. */
    fun refreshYtLiked() {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            ytLikedIds.value = if (com.kotlinsyd.forte.data.YtAccount.isSignedIn()) {
                runCatching { com.kotlinsyd.forte.data.YtAccount.likedIds() }
                    .getOrDefault(emptySet())
            } else {
                emptySet()
            }
        }
    }

    /** Show a short honest message (account action outcomes) and let
     * it clear itself. */
    fun showMessage(text: String) {
        userMessage.value = text
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            kotlinx.coroutines.delay(4000)
            if (userMessage.value == text) userMessage.value = null
        }
    }

    companion object {
        lateinit var instance: ForteApp
    }
}
