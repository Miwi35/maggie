package com.maggie.app.data.fcm

import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.maggie.app.data.api.MaggieApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Where this device's FCM token comes from — Firebase, or a fake in tests. */
interface FcmTokenSource {
    /** The current token, or null when Firebase is not set up in this build. */
    suspend fun current(): String?

    /** Invalidates the token: the push stops reaching this install. */
    suspend fun delete()
}

class FirebaseTokenSource : FcmTokenSource {
    override suspend fun current(): String? = firebase { messaging ->
        suspendCancellableCoroutine<String?> { cont ->
            messaging.token.addOnCompleteListener { task ->
                cont.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }

    override suspend fun delete() {
        firebase { messaging ->
            suspendCancellableCoroutine<Unit> { cont ->
                messaging.deleteToken().addOnCompleteListener { cont.resume(Unit) }
            }
        }
    }

    // A build without google-services.json has no FirebaseApp: getInstance() throws.
    private suspend fun <T> firebase(block: suspend (FirebaseMessaging) -> T): T? = try {
        block(FirebaseMessaging.getInstance())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Firebase unavailable: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "FirebaseTokenSource"
    }
}

/**
 * Keeps the server's list of devices in step with the session: the token is sent when
 * the user is signed in — at sign-in, and at every start of an app that is already
 * signed in, which `onNewToken` alone never covers — and removed on sign-out.
 */
class PushTokenRegistrar(
    private val apiService: MaggieApiService,
    private val tokenSource: FcmTokenSource,
    private val deviceName: String = Build.MODEL,
    private val attempts: Int = 3,
    private val retryDelayMs: Long = 30_000,
) {
    /** One attempt: true when the server has the token. */
    suspend fun register(token: String? = null): Boolean {
        val value = token ?: tokenSource.current() ?: return false
        return try {
            apiService.registerFcmToken(value, deviceName)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register FCM token: ${e.message}")
            false
        }
    }

    /** Registers once per sign-in, retrying a few times: the network may not be up at launch. */
    suspend fun keepRegistered(signedIn: Flow<Boolean>) {
        signedIn.distinctUntilChanged().filter { it }.collectLatest {
            repeat(attempts) { attempt ->
                if (register()) return@collectLatest
                if (attempt < attempts - 1) delay(retryDelayMs * (attempt + 1))
            }
        }
    }

    /**
     * Call before the credentials are cleared: the server only takes the removal from the
     * signed-in user. A failure is not fatal — FCM reports the token dead on the next send
     * and the server drops it — so sign-out always goes through.
     */
    suspend fun unregister() {
        val token = tokenSource.current()
        if (token != null) {
            try {
                apiService.unregisterFcmToken(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister FCM token: ${e.message}")
            }
        }
        tokenSource.delete()
    }

    private companion object {
        const val TAG = "PushTokenRegistrar"
    }
}
