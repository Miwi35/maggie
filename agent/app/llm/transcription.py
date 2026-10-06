"""Whisper transcription, and the model cleanup that is now the exception (MAG-222).

Every dictated sentence used to pay for two calls in series: Whisper, then the main
model to tidy what it heard — even on the way to Maggie, who reads through a « euh »
on her own. So the cleanup is now asked for only when the text is destined to be
written as is (a form field, the dictation service of MAG-216) *and* actually needs
it, and it runs on the fast model. Everything else gets the rule below, which costs
nothing: the isolated fillers are dropped and the rest is left alone.
"""

import logging
import re
import time
import unicodedata
from dataclasses import dataclass, field
from typing import Any

import openai

from app.config import settings
from app.llm.client import create_llm_client
from app.metrics import record_llm_usage

logger = logging.getLogger(__name__)

# What a caller may ask of the transcript. "none" never reaches the model — what the
# conversation with Maggie uses. "auto" cleans only a text that needs it, with the
# fast model — what a dictation meant to be written uses.
CLEANUP_MODES = ("none", "auto")

CLEANUP_PROMPT = (
    "Tu es un assistant de transcription. L'utilisateur a dicté le texte suivant. "
    "Nettoie-le : supprime les hésitations (euh, hmm, ben...), corrige la grammaire et la ponctuation, "
    "mais garde le sens original et la langue utilisée. "
    "Retourne UNIQUEMENT le texte nettoyé, sans commentaire ni explication."
)

# The fillers the owner says without meaning them. Kept short on purpose: every word
# here is one a sentence could legitimately contain, so the rule only ever removes a
# standalone token, never a fragment of a word. Its Kotlin twin, applied to what the
# phone's own recognition hands back, is `voice/HesitationFilter.kt` — the two lists
# and the two test tables are meant to say the same thing.
HESITATIONS = ("euh", "euhh", "euheu", "heu", "hum", "humm", "hmm", "mmh", "ben", "bah")

_HESITATION = re.compile(r"\b(?:" + "|".join(HESITATIONS) + r")\b", re.IGNORECASE)
_LEFTOVER_SPACE = re.compile(r"\s{2,}")
# The space a removal leaves in front of a comma or a period. Only those two: French
# wants a space before « ; : ? ! », so closing it up there would break the typography
# of a sentence the rule is supposed to leave alone.
_LEFTOVER_PUNCTUATION = re.compile(r"\s+([,.])")

# A word said twice in a row — a sign the sentence stumbled. These are left out: they
# are French rather than a stammer (« nous nous sommes vus », « très très bien »,
# « oui oui »), and a false positive here buys a cleanup nobody needed.
_NOT_A_STAMMER = ("nous", "vous", "très", "tres", "oui", "non", "si")
_REPEATED_WORD = re.compile(r"\b(\w+)\s+\1\b", re.IGNORECASE)

# Below this, a sentence with no final period is just a short answer ("oui", "demain
# matin"), not a transcript the model has to punctuate.
MIN_WORDS_FOR_PUNCTUATION = 8

# How many cleanups were asked of the model since the last reset. Read by the voice
# journey through the e2e surface to prove an absence — « talking to Maggie no longer
# pays for a cleanup » is a negative, and nothing on screen shows it (MAG-222). It
# counts everywhere, dev and prod included, because the cleanup itself is the same code
# there; only the route that reads it is mounted on the e2e stack alone.
_cleanup_requests = 0


def cleanup_requests() -> int:
    return _cleanup_requests


def reset_cleanup_requests() -> None:
    global _cleanup_requests
    _cleanup_requests = 0


def strip_hesitations(text: str) -> str:
    """Drop the isolated fillers, without a model and without touching anything else.

    What the chat bubble shows for a dictated message. Returns the text unchanged when
    removing them would leave nothing — « euh » alone is still better than an empty
    bubble.
    """
    stripped = _HESITATION.sub("", text)
    stripped = _LEFTOVER_PUNCTUATION.sub(r"\1", stripped)
    stripped = _LEFTOVER_SPACE.sub(" ", stripped).strip(" ,;:")
    return stripped if stripped else text.strip()


def needs_cleanup(text: str) -> bool:
    """Whether a text destined to be written as is would gain from the model.

    The three signs the owner named: hesitations, a word said twice in a row, and a
    long sentence with no punctuation at all.
    """
    if not text.strip():
        return False
    if _HESITATION.search(text):
        return True
    if any(match.group(1).lower() not in _NOT_A_STAMMER for match in _REPEATED_WORD.finditer(text)):
        return True
    return len(text.split()) >= MIN_WORDS_FOR_PUNCTUATION and not re.search(r"[.!?…]", text)


# --- Whisper hears things that were never said ---
#
# Given an audio with no speech in it, Whisper does not answer nothing: it writes the
# subtitle boilerplate it was trained on. The owner got « Thank you for watching » in
# his chat without having said a word (retour de recette du 7 octobre). So no text
# coming out of a clip that holds no speech may leave this module, and the three signs
# below decide, read off the transcription itself — no second model call.

# Whisper's own two scores, per segment. Its reference implementation drops a segment
# when both fire; here either is enough, because the failure they guard is not
# symmetrical: a hallucination over silence often comes out *confidently* (a good
# `avg_logprob`) with a high `no_speech_prob`, and « send nothing when in doubt » is
# what the owner asked for. Real speech sits far from both — under 0.1 and above -0.5.
NO_SPEECH_PROB_MAX = 0.6
AVG_LOGPROB_MIN = -1.0

# Under this there is no sentence, only a finger slip or a door closing. Whisper pads
# anything shorter to its 30-second window with silence, which is exactly what it
# invents over.
MIN_AUDIO_SECONDS = 0.4

# What Whisper says when it has heard nothing: end-of-video credits, subtitle
# signatures, a lone « merci ». Matched on the whole transcript, accents and case
# folded away — a sentence that merely *contains* « merci » or « regarder » is a
# sentence the owner may well have dictated and is left alone.
HALLUCINATION_PHRASES = (
    "thank you",
    "thank you for watching",
    "thanks for watching",
    "merci",
    "merci beaucoup",
    "merci a tous",
    "merci de votre attention",
    "au revoir",
)

# The same boilerplate, in the forms whose tail varies ("… par la communauté
# d'Amara.org", "… Société Radio-Canada"). Matched as a prefix.
HALLUCINATION_PREFIXES = (
    "thank you for watching",
    "thanks for watching",
    "merci d'avoir regarde",
    "sous-titres realises",
    "sous-titrage",
    "sous-titres par",
    "subtitles by",
    "subtitled by",
    "abonnez-vous",
    "amara.org",
)


@dataclass
class WhisperTranscript:
    """What Whisper heard, and what it thinks of what it heard.

    `duration` and `segments` are empty when the endpoint answered plain text rather
    than `verbose_json` — then [holds_speech] judges the words alone.
    """

    text: str
    duration: float | None = None
    segments: list[Any] = field(default_factory=list)


def _fold(text: str) -> str:
    """Lower-cased, accent-free, punctuation-free — what the phrase lists are written in."""
    stripped = unicodedata.normalize("NFKD", text.strip().lower())
    stripped = "".join(c for c in stripped if not unicodedata.combining(c))
    stripped = re.sub(r"[!?.,;:…\"»«\s]+$", "", stripped)
    return re.sub(r"\s+", " ", stripped).strip()


def looks_hallucinated(text: str) -> bool:
    """Whether [text] is one of the sentences Whisper says over silence."""
    folded = _fold(text)
    if not folded:
        return True
    # Nothing but music notes, dots or dashes: the model marking a silence it was
    # asked to transcribe anyway.
    if re.fullmatch(r"[\W_]+", folded, re.UNICODE):
        return True
    return folded in HALLUCINATION_PHRASES or folded.startswith(HALLUCINATION_PREFIXES)


def _score(segment: Any, name: str) -> float | None:
    """One of Whisper's per-segment scores, from a mapping or from a model object."""
    value = segment.get(name) if isinstance(segment, dict) else getattr(segment, name, None)
    try:
        return float(value)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        return None


def _is_silent_segment(segment: Any) -> bool:
    no_speech_prob = _score(segment, "no_speech_prob")
    avg_logprob = _score(segment, "avg_logprob")
    if no_speech_prob is not None and no_speech_prob > NO_SPEECH_PROB_MAX:
        return True
    return avg_logprob is not None and avg_logprob < AVG_LOGPROB_MIN


def holds_speech(transcript: WhisperTranscript) -> bool:
    """Whether the clip behind [transcript] really had someone speaking in it.

    Three signs, cheapest first: an empty or boilerplate text, a clip too short to
    hold a sentence, and Whisper's own scores saying every segment is silence. A
    transcription that reports no segment at all — a server answering plain text —
    is judged on its words alone rather than refused.
    """
    if looks_hallucinated(transcript.text):
        return False
    if transcript.duration is not None and transcript.duration < MIN_AUDIO_SECONDS:
        return False
    if not transcript.segments:
        return True
    return any(not _is_silent_segment(segment) for segment in transcript.segments)


async def _whisper_transcribe(audio_bytes: bytes, filename: str) -> WhisperTranscript:
    """Transcribe audio using OpenAI Whisper, asking for the scores that judge it.

    `verbose_json` rather than `text`: it is the only response format carrying
    `no_speech_prob` and `avg_logprob`, without which nothing can tell a sentence from
    an invention over silence.
    """
    # base_url is only set on the e2e stack, where it points at WireMock.
    # Passing None keeps the library's own default for dev and prod.
    client = openai.AsyncOpenAI(
        api_key=settings.openai_api_key,
        base_url=settings.openai_base_url or None,
    )
    transcript = await client.audio.transcriptions.create(
        model="whisper-1",
        file=(filename, audio_bytes),
        response_format="verbose_json",
    )
    return _as_transcript(transcript)


def _as_transcript(answer: Any) -> WhisperTranscript:
    """Read whatever the endpoint answered: a model object, a mapping, or plain text."""
    if isinstance(answer, str):
        return WhisperTranscript(text=answer.strip())

    read = answer.get if isinstance(answer, dict) else lambda name, default=None: getattr(answer, name, default)
    duration = read("duration")
    try:
        duration = float(duration)  # type: ignore[arg-type]
    except (TypeError, ValueError):
        duration = None
    return WhisperTranscript(
        text=str(read("text") or "").strip(),
        duration=duration,
        segments=list(read("segments") or []),
    )


async def _cleanup_with_llm(raw_text: str) -> str:
    """Clean up raw transcript with the fast model — nobody reads a transcript twice."""
    global _cleanup_requests
    _cleanup_requests += 1
    client = create_llm_client()
    model = settings.anthropic_fast_model
    t0 = time.monotonic()
    try:
        response = await client.messages.create(
            model=model,
            max_tokens=1024,
            messages=[
                {"role": "user", "content": f"{CLEANUP_PROMPT}\n\nTexte dicté :\n{raw_text}"},
            ],
        )
        duration = time.monotonic() - t0
        record_llm_usage(
            model=model,
            call_type="transcription_cleanup",
            input_tokens=response.usage.input_tokens,
            output_tokens=response.usage.output_tokens,
            duration_seconds=duration,
        )
    except Exception:
        duration = time.monotonic() - t0
        record_llm_usage(
            model=model,
            call_type="transcription_cleanup",
            input_tokens=0,
            output_tokens=0,
            duration_seconds=duration,
            status="error",
        )
        raise
    return response.content[0].text.strip()


async def transcribe_audio(
    audio_bytes: bytes,
    filename: str,
    cleanup: str = "auto",
) -> dict[str, str]:
    """Whisper STT, then the model only if `cleanup` is "auto" and the text needs it.

    Returns {raw, clean}: `raw` is what Whisper heard, word for word, and `clean` is
    what a caller displays or writes — the rule-stripped text, or the model's version
    when it was worth a call. Both are empty when the clip held no speech: a caller
    that gets nothing back says « Je n'ai rien entendu » and sends nothing (see
    [holds_speech]).
    """
    transcript = await _whisper_transcribe(audio_bytes, filename)
    if not holds_speech(transcript):
        logger.info("No speech in the clip; refusing the transcript %r", transcript.text[:80])
        return {"raw": "", "clean": ""}

    raw = transcript.text
    if cleanup == "auto" and needs_cleanup(raw):
        return {"raw": raw, "clean": await _cleanup_with_llm(raw)}

    return {"raw": raw, "clean": strip_hesitations(raw)}
