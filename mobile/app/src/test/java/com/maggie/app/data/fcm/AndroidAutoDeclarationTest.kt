package com.maggie.app.data.fcm

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** The manifest tells Android Auto that Maggie posts notifications for the car (MAG-682). */
@RunWith(AndroidJUnit4::class)
class AndroidAutoDeclarationTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the manifest points Android Auto at a descriptor that declares notifications`() {
        val metaData = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
        val resource = metaData.getInt("com.google.android.gms.car.application")
        assertTrue("meta-data com.google.android.gms.car.application is missing", resource != 0)

        val parser = app.resources.getXml(resource)
        val uses = mutableListOf<String?>()
        var root: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            if (root == null) root = parser.name
            if (parser.name == "uses") uses += parser.getAttributeValue(null, "name")
        }

        assertEquals("automotiveApp", root)
        assertEquals(listOf<String?>("notification"), uses)
    }
}
