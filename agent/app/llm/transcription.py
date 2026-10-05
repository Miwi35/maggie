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


async def _whisper_transcribe(audio_bytes: bytes, filename: str) -> str:
    """Transcribe audio using OpenAI Whisper."""
    # base_url is only set on the e2e stack, where it points at WireMock.
    # Passing None keeps the library's own default for dev and prod.
    client = openai.AsyncOpenAI(
        api_key=settings.openai_api_key,
        base_url=settings.openai_base_url or None,
    )
    transcript = await client.audio.transcriptions.create(
        model="whisper-1",
        file=(filename, audio_bytes),
        response_format="text",
    )
    return transcript.strip()


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
    when it was worth a call.
    """
    raw = await _whisper_transcribe(audio_bytes, filename)
    if not raw:
        return {"raw": "", "clean": ""}

    if cleanup == "auto" and needs_cleanup(raw):
        return {"raw": raw, "clean": await _cleanup_with_llm(raw)}

    return {"raw": raw, "clean": strip_hesitations(raw)}
