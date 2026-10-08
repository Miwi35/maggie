package com.maggie.app.util

import android.content.Context
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions

/**
 * Crash and ANR reports to GlitchTip (Sentry-compatible).
 *
 * The DSN comes from `BuildConfig.SENTRY_DSN`, which only the prod release
 * carries: an empty DSN means the SDK is never started and nothing is sent.
 * Every event is tagged `component=mobile` (read by the bridge that files
 * production errors in Linear) and carries no personal data.
 */
object SentrySetup {
    const val COMPONENT = "mobile"
    const val ENVIRONMENT = "prod"

    fun shouldInit(dsn: String): Boolean = dsn.isNotBlank()

    fun release(versionName: String, versionCode: Int): String = "$versionName+$versionCode"

    fun configure(options: SentryAndroidOptions, dsn: String, versionName: String, versionCode: Int) {
        options.dsn = dsn
        options.release = release(versionName, versionCode)
        options.environment = ENVIRONMENT
        options.setTag("component", COMPONENT)
        // No IP, no user: only the crash and the device.
        options.isSendDefaultPii = false
        // Crashes and ANRs only: no performance tracing, no session replay.
        options.isAnrEnabled = true
        options.tracesSampleRate = null
        options.sessionReplay.sessionSampleRate = null
        options.sessionReplay.onErrorSampleRate = null
    }

    /**
     * Starts the SDK when [dsn] is set; returns whether it did. [start] is the
     * real `SentryAndroid.init` in the app, a recorder in tests.
     */
    fun init(
        context: Context,
        dsn: String,
        versionName: String,
        versionCode: Int,
        start: (Context, (SentryAndroidOptions) -> Unit) -> Unit = { ctx, configure ->
            SentryAndroid.init(ctx) { options -> configure(options) }
        },
    ): Boolean {
        if (!shouldInit(dsn)) return false
        start(context) { options -> configure(options, dsn, versionName, versionCode) }
        return true
    }
}
