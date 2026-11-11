package com.maggie.app.media

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MaggieMediaServiceDeclarationTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the service is exported for media browsing, as a media playback foreground service`() {
        val info = app.packageManager.getServiceInfo(ComponentName(app, MaggieMediaService::class.java), 0)

        assertTrue(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, info.foregroundServiceType)
        listOf("androidx.media3.session.MediaLibraryService", "android.media.browse.MediaBrowserService").forEach { action ->
            val found = app.packageManager.queryIntentServices(Intent(action).setPackage(app.packageName), PackageManager.MATCH_ALL)
            assertTrue("$action is not declared", found.any { it.serviceInfo.name == MaggieMediaService::class.java.name })
        }
    }

    @Test
    fun `the manifest asks for the media playback foreground permission`() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()

        assertTrue(requested.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))
    }

    @Test
    fun `the skips are 15 seconds`() {
        assertEquals(15_000L, DigestPlayback.SKIP_MS)
    }
}
