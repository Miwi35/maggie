package com.maggie.app.voice

import java.io.OutputStream

/**
 * The phone's engine, scripted for the journeys — the same idea as the agent's fake LLM.
 *
 * The CI emulator has no sound card and no Google engine, and since MAG-222's retour de
 * recette a hold with no voice in it is sent nowhere: a journey that relied on Whisper
 * hearing something in the emulator's silence no longer received any text. Injecting
 * real audio was ruled out by the owner (7 Oct.) as too heavy and fragile for CI, so
 * this engine « hears » one fixed sentence instead, and the journey proves what follows
 * it: the overlay sends that text, without a cleanup, and Maggie answers.
 *
 * The silence itself is unit-tested (`SpeechPresenceTest`, `VoiceManagerTest`). Compiled
 * into the `e2e` flavor alone; `NoFakeVoiceInProdTest` checks prod never carries it.
 */
class ScriptedDeviceSpeech : DeviceSpeechRecognizer {
    companion object {
        /**
         * What the WireMock Whisper stub dictates too, so both legs ask Maggie the same
         * thing (`41-grocery-add-dictated.yaml`). The « euh » is there for the rule that
         * drops it ([HesitationFilter]).
         */
        const val SENTENCE = "euh ajoute des tomates à la liste de courses s'il te plaît"

        /**
         * The next hold hears this one: the agent takes the same words, sent again within the
         * minute, for the same message and replays its answer (MAG-386), so a journey that
         * dictates twice in a row needs two different sentences. Same meaning, same scenarios.
         */
        const val OTHER_SENTENCE = "euh ajoute des tomates à la liste de courses s'il vous plaît"

        /** Above [TranscriptionQuality.MIN_CONFIDENCE]: a sentence the phone heard well. */
        const val CONFIDENCE = 0.9f

        private var delivered = 0

        /** Starts the alternation over: one process serves every flow, a test wants the first sentence. */
        fun restart() {
            delivered = 0
        }

        /** What the next delivery says: the two sentences in turn, so two holds never repeat each other. */
        private fun nextSentence(): String = if (delivered++ % 2 == 0) SENTENCE else OTHER_SENTENCE
    }

    // One instance per hold, like the real engine: a session released late cannot
    // take the listener of the one that followed it.
    private var listener: DeviceSpeechRecognizer.Listener? = null

    override val isAvailable = true

    /** Null: it listens for itself, so the recorder's placeholder bytes go nowhere. */
    override fun start(listener: DeviceSpeechRecognizer.Listener): OutputStream? {
        this.listener = listener
        listener.onPartial(SENTENCE)
        return null
    }

    override fun stopListening() {
        listener?.let { it.onResult(DeviceSpeechResult(nextSentence(), CONFIDENCE)) }
        listener = null
    }

    override fun destroy() {
        listener = null
    }
}
