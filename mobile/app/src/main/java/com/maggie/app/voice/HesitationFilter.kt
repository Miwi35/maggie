package com.maggie.app.voice

/**
 * Drops the isolated fillers from a transcript, without a model (MAG-222).
 *
 * What the chat bubble shows for something said out loud. The twin of
 * `strip_hesitations` in `agent/app/llm/transcription.py`: the phone's own recognition
 * never reaches the server, so the rule has to exist on both sides — the two word
 * lists and the two test tables are meant to say the same thing.
 */
object HesitationFilter {
    /**
     * Kept short on purpose: every word here is one a sentence could legitimately
     * contain, so only a standalone token is ever removed, never a fragment of a word
     * ("hummus" keeps its "hum").
     */
    val HESITATIONS = listOf("euh", "euhh", "euheu", "heu", "hum", "humm", "hmm", "mmh", "ben", "bah")

    // `(?U)` so a word boundary counts an accented letter as a letter, the way Python's
    // `\b` does on the agent's side — without it, "clé" + "euh" glued together would
    // lose its filler and "cléeuh" would come out as "clé".
    private val filler = Regex("""(?U)\b(?:${HESITATIONS.joinToString("|")})\b""", RegexOption.IGNORE_CASE)

    // The space a removal leaves in front of a comma or a period. Only those two:
    // French wants a space before « ; : ? ! », so closing it up there would break the
    // typography of a sentence the rule is supposed to leave alone.
    private val leftoverPunctuation = Regex("""\s+([,.])""")
    private val leftoverSpace = Regex("""\s{2,}""")

    /**
     * Returns [text] without its fillers, or [text] itself when removing them would
     * leave nothing — an empty bubble says less than « euh ».
     */
    fun strip(text: String): String {
        val stripped = filler.replace(text, "")
            .replace(leftoverPunctuation, "$1")
            .replace(leftoverSpace, " ")
            .trim(' ', ',', ';', ':')
        return stripped.ifBlank { text.trim() }
    }
}
