package com.maggie.app.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.maggie.app.MainActivity

/** Entry of the system share sheet (MAG-895): opens the chat on the shared text as a draft, never sends it. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = ShareLink.chatLinkOf(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
        )
        if (link != null) {
            startActivity(
                Intent(Intent.ACTION_VIEW, link, this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
    }
}
