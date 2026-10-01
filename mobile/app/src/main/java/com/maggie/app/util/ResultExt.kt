package com.maggie.app.util

import kotlin.coroutines.cancellation.CancellationException

// runCatching also catches the cancellation of the coroutine it runs in; swallowing it reads as "not found".
fun <T> Result<T>.rethrowCancellation(): Result<T> = onFailure { if (it is CancellationException) throw it }
