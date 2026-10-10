package com.maggie.app.e2e

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * Where the e2e app learns which flow drives it, without a line in `MainActivity`.
 *
 * A content provider is created before `Application.onCreate`, so it is the one place an
 * e2e-only class can hook into the process without the shared code calling it. It
 * provides nothing: it registers a lifecycle callback that reads [E2eJourney] off the
 * intent of every activity, *before* the activity's own `onCreate` — the first request
 * (the sign-in) already carries the header. Declared in `src/e2e/AndroidManifest.xml`
 * only, so no shipped build has it.
 */
class E2eHooks : ContentProvider() {
    override fun onCreate(): Boolean {
        (context?.applicationContext as? Application)?.registerActivityLifecycleCallbacks(JourneyReader)
        return true
    }

    internal object JourneyReader : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) =
            E2eJourney.readFrom(activity.intent)

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
