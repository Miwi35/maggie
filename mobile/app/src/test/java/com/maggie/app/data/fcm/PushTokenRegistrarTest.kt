package com.maggie.app.data.fcm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.api.MaggieApiService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PushTokenRegistrarTest {

    private class FakeTokenSource(var token: String? = "tok-1") : FcmTokenSource {
        var deleted = 0
        override suspend fun current(): String? = token
        override suspend fun delete() {
            deleted++
        }
    }

    private val api = mockk<MaggieApiService>(relaxed = true)
    private val source = FakeTokenSource()
    private val registrar = PushTokenRegistrar(api, source, deviceName = "Pixel", attempts = 3, retryDelayMs = 1_000)

    @Test
    fun `the token is sent when the user is signed in, not only when Firebase rotates it`() = runTest {
        val signedIn = MutableSharedFlow<Boolean>()
        backgroundScope.launch { registrar.keepRegistered(signedIn) }
        runCurrent()

        signedIn.emit(false)
        runCurrent()
        coVerify(exactly = 0) { api.registerFcmToken(any(), any()) }

        signedIn.emit(true)
        runCurrent()
        coVerify(exactly = 1) { api.registerFcmToken("tok-1", "Pixel") }
    }

    @Test
    fun `a signed-in app that starts again registers again`() = runTest {
        val signedIn = MutableSharedFlow<Boolean>()
        backgroundScope.launch { registrar.keepRegistered(signedIn) }
        runCurrent()

        signedIn.emit(true)
        signedIn.emit(false)
        signedIn.emit(true)
        runCurrent()

        coVerify(exactly = 2) { api.registerFcmToken("tok-1", "Pixel") }
    }

    @Test
    fun `a registration that fails because the network is not up is tried again`() = runTest {
        coEvery { api.registerFcmToken(any(), any()) } throws java.io.IOException("offline") andThen Unit
        val signedIn = MutableSharedFlow<Boolean>()
        backgroundScope.launch { registrar.keepRegistered(signedIn) }
        runCurrent()

        signedIn.emit(true)
        runCurrent()
        coVerify(exactly = 1) { api.registerFcmToken(any(), any()) }

        advanceTimeBy(1_001)
        runCurrent()
        coVerify(exactly = 2) { api.registerFcmToken(any(), any()) }

        advanceTimeBy(10_000)
        runCurrent()
        coVerify(exactly = 2) { api.registerFcmToken(any(), any()) }
    }

    @Test
    fun `a build without Firebase registers nothing`() = runTest {
        source.token = null

        assertFalse(registrar.register())
        coVerify(exactly = 0) { api.registerFcmToken(any(), any()) }
    }

    @Test
    fun `a token handed over by onNewToken is the one sent`() = runTest {
        assertTrue(registrar.register("tok-2"))

        coVerify { api.registerFcmToken("tok-2", "Pixel") }
    }

    @Test
    fun `signing out removes the token from the server then from the device`() = runTest {
        registrar.unregister()

        coVerifyOrder { api.unregisterFcmToken("tok-1") }
        assertTrue(source.deleted == 1)
    }

    @Test
    fun `signing out goes through when the server cannot be reached`() = runTest {
        coEvery { api.unregisterFcmToken(any()) } throws java.io.IOException("offline")

        registrar.unregister()

        assertTrue(source.deleted == 1)
    }
}
