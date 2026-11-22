package com.maggie.app.share

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.MainActivity
import com.maggie.app.ui.navigation.DeepLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class ShareActivityTest {
    private val scheme = DeepLinks.SCHEME

    private fun share(text: String?, subject: String? = null): Pair<Intent?, Boolean> {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").apply {
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }
        val controller = Robolectric.buildActivity(ShareActivity::class.java, intent).create()
        return shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity to controller.get().isFinishing
    }

    @Test
    fun `a shared link opens the chat on it as a draft`() {
        val (started, _) = share("https://example.org/tarte")

        assertEquals(MainActivity::class.java.name, started!!.component!!.className)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("$scheme://chat?message=https%3A%2F%2Fexample.org%2Ftarte", started.data.toString())
    }

    @Test
    fun `the subject comes before the text when the text does not hold it`() {
        val (started, _) = share("https://example.org/tarte", "Tarte aux poires")

        assertEquals("Tarte aux poires\nhttps://example.org/tarte", started!!.data!!.getQueryParameter("message"))
    }

    @Test
    fun `a subject already in the text is not repeated`() {
        val (started, _) = share("Tarte aux poires https://example.org/tarte", "Tarte aux poires")

        assertEquals("Tarte aux poires https://example.org/tarte", started!!.data!!.getQueryParameter("message"))
    }

    @Test
    fun `an empty share opens nothing and the activity finishes`() {
        listOf(null, "", "   ").forEach { text ->
            val (started, finishing) = share(text)
            assertNull(started)
            assertTrue(finishing)
        }
    }

    @Test
    fun `a shared text shaped like a message id opens nothing`() {
        val (started, _) = share("01JABCDEFGHJKMNPQRSTVWXYZ0")

        assertNull(started)
    }

    @Test
    fun `the share activity always finishes`() {
        assertTrue(share("https://example.org/tarte").second)
    }
}
