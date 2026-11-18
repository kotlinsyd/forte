package com.kotlinsyd.forte.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.kotlinsyd.forte.ForteApp
import com.kotlinsyd.forte.MainActivity
import com.kotlinsyd.forte.R
import com.kotlinsyd.forte.playback.PlayerService

/**
 * Home-screen widget (RemoteViews — no Glance in the offline cache,
 * and none needed): cover, title/artist, prev / play-pause / next in
 * Forte's glass-dark card. Buttons send control intents to
 * [PlayerService], which drives the one PlayerController and pushes
 * refreshed views back here via [updateAll].
 */
class ForteWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Pull the live state if the app process is up; otherwise the
        // static "Forte / Nothing playing" face stands.
        runCatching {
            val state = ForteApp.instance.player.state.value
            push(context, state.current?.title, state.current?.artist, state.playing, null)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.kotlinsyd.forte.widget.TOGGLE"
        const val ACTION_NEXT = "com.kotlinsyd.forte.widget.NEXT"
        const val ACTION_PREV = "com.kotlinsyd.forte.widget.PREV"

        fun updateAll(context: Context) {
            runCatching {
                val state = ForteApp.instance.player.state.value
                push(context, state.current?.title, state.current?.artist, state.playing, null)
            }
        }

        /** Called by the player service with the loaded artwork. */
        fun push(
            context: Context,
            title: String?,
            artist: String?,
            playing: Boolean,
            art: Bitmap?,
        ) {
            // FORTE-C fix: on devices with no widget system at all
            // (Wear OS watches, most TVs) AppWidgetManager.getInstance()
            // returns null, and the next line used to call
            // getAppWidgetIds on that null — an NPE that took the
            // player service down on a watch. The widget is decoration:
            // skip the push entirely where widgets cannot exist, and
            // null-check the manager everywhere else.
            if (!context.packageManager.hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_APP_WIDGETS
                )
            ) return
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, ForteWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_forte)
            views.setTextViewText(R.id.widget_title, title ?: "Soniko")
            views.setTextViewText(R.id.widget_artist, artist ?: "Nothing playing")
            views.setImageViewResource(
                R.id.widget_playpause,
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            )
            if (art != null) views.setImageViewBitmap(R.id.widget_art, art)
            views.setOnClickPendingIntent(R.id.widget_playpause, serviceIntent(context, ACTION_TOGGLE, 1))
            views.setOnClickPendingIntent(R.id.widget_next, serviceIntent(context, ACTION_NEXT, 2))
            views.setOnClickPendingIntent(R.id.widget_prev, serviceIntent(context, ACTION_PREV, 3))
            views.setOnClickPendingIntent(
                R.id.widget_art,
                PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            manager.updateAppWidget(ids, views)
        }

        private fun serviceIntent(context: Context, action: String, code: Int): PendingIntent {
            val intent = Intent(context, PlayerService::class.java).setAction(action)
            // The playback service is a foreground service; widget taps
            // must start it the foreground way.
            return PendingIntent.getForegroundService(
                context, code, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
