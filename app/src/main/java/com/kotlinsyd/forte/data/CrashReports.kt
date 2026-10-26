package com.kotlinsyd.forte.data

import android.content.Context
import com.kotlinsyd.forte.BuildConfig
import io.sentry.android.core.SentryAndroid

/**
 * Sentry crash reporting — Forte's OWN project in the owner's Sentry
 * org, with its own DSN and no other project's. Errors only: no
 * tracing, no profiling, no session replay, so the free plan's quota
 * is spent on crashes alone. The DSN is Sentry's client ingest key —
 * public by design, it can only SEND events to this project.
 *
 * ON by default; the Settings "Crash reports" switch turns it off,
 * which closes the SDK for the rest of the process and prevents any
 * init at the next launch. While the DSN slot in build.gradle.kts is
 * empty, every call here is a no-op. Every call is failure-proof: a
 * Sentry problem must never break app startup or settings.
 */
object CrashReports {

    fun initIfEnabled(context: Context, settings: SettingsStore) {
        if (!settings.settings.value.crashReports) return
        init(context)
    }

    /** Applies a toggle change immediately (init now, or close until relaunch). */
    fun apply(context: Context, enabled: Boolean) {
        runCatching {
            if (enabled) init(context) else io.sentry.Sentry.close()
        }
    }

    private fun init(context: Context) {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return
        runCatching {
            SentryAndroid.init(context) { options ->
                options.dsn = dsn
                options.isEnableUncaughtExceptionHandler = true
                // The native (NDK) crash handler stays OFF — Java-side
                // reporting only. (A house lesson learned the hard way:
                // the native handler initializing at startup can kill
                // an app on open before any report is written.)
                options.isEnableNdk = false
                // Crashes only — keep the free plan's quota for errors.
                options.tracesSampleRate = 0.0
                options.isEnableAutoSessionTracking = false
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
            }
        }
    }
}
