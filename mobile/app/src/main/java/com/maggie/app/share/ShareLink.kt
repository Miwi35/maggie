package com.maggie.app.share

import android.net.Uri
import com.maggie.app.ui.navigation.DeepLinks

/** Translates a shared text into the chat link that prefills a draft (MAG-895). */
object ShareLink {
    fun chatLinkOf(text: String?, subject: String?): Uri? {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return null
        val title = subject?.trim().orEmpty()
        val draft = if (title.isNotEmpty() && !body.contains(title)) "$title\n$body" else body
        // A shared text never opens an existing message: chatTarget would take an id for one.
        if (DeepLinks.isMessageId(draft)) return null
        return Uri.parse("${DeepLinks.SCHEME}://chat?message=${Uri.encode(draft)}")
    }
}
