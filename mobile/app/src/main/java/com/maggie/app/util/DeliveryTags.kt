package com.maggie.app.util

private const val TAG = """\[[^\[\]\n]{1,40}\]"""
private val tagBeforePunctuation = Regex("""\s*$TAG(?=\s*[.,;:!?…])""")
private val anyTag = Regex("""\s*$TAG\s*""")
private val openTagAtEnd = Regex("""\s*\[[^\[\]\n]{0,40}$""")
private val repeatedSpaces = Regex(" {2,}")

/**
 * The text without Maggie's voice tags (« [warmly] », « [softly] »…), same rule as the agent's
 * `strip_delivery_tags`; a tag still open at the end (streaming) is held back. For display only:
 * what goes to speech synthesis keeps its tags.
 */
fun stripDeliveryTags(text: String): String =
    text.replace(tagBeforePunctuation, "")
        .replace(anyTag, " ")
        .replace(repeatedSpaces, " ")
        .replace(openTagAtEnd, "")
        .trim()
