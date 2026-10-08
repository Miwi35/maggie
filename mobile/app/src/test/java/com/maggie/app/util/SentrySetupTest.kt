package com.maggie.app.util

import android.content.Context
import com.maggie.app.BuildConfig
import io.mockk.mockk
import io.sentry.android.core.SentryAndroidOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentrySetupTest {

    private val context = mockk<Context>(relaxed = true)

    @Test
    fun `an empty DSN never starts the SDK`() {
        var started = false

        val result = SentrySetup.init(context, "", "0.1.0-abc1234", 42) { _, _ -> started = true }

        assertFalse(result)
        assertFalse(started)
    }

    @Test
    fun `a blank DSN never starts the SDK`() {
        var started = false

        val result = SentrySetup.init(context, "   ", "0.1.0", 1) { _, _ -> started = true }

        assertFalse(result)
        assertFalse(started)
    }

    @Test
    fun `the unit-test build carries no DSN`() {
        // Debug and e2e builds get an empty DSN (build.gradle.kts): nothing is sent from them.
        assertEquals("", BuildConfig.SENTRY_DSN)
    }

    @Test
    fun `a DSN starts the SDK with release, environment, component tag and no PII`() {
        val dsn = "https://public@glitchtip.meven.fr/4"
        val options = SentryAndroidOptions()

        val result = SentrySetup.init(context, dsn, "0.1.0-abc1234", 42) { _, configure -> configure(options) }

        assertTrue(result)
        assertEquals(dsn, options.dsn)
        assertEquals("0.1.0-abc1234+42", options.release)
        assertEquals("prod", options.environment)
        assertEquals("mobile", options.tags["component"])
        assertFalse(options.isSendDefaultPii)
        assertTrue(options.isAnrEnabled)
        assertNull(options.tracesSampleRate)
        assertNull(options.sessionReplay.sessionSampleRate)
        assertNull(options.sessionReplay.onErrorSampleRate)
    }
}
