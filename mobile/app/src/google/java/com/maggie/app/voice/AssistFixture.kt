package com.maggie.app.voice

import android.content.Context
import android.content.Intent

/** Dev and prod never stage a screen: only Android hands one over. Its counterpart lives in `src/e2e/`. */
@Suppress("UNUSED_PARAMETER")
fun fixtureScreenContext(context: Context, intent: Intent): ScreenContext? = null
