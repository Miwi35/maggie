"""Edge TTS synthesis — streams MP3 audio from Microsoft neural voices."""

import re
from collections.abc import AsyncGenerator

import edge_tts

# Curated French voices (fr-FR, fr-BE, fr-CA)
VOICES = [
    {"id": "fr-FR-DeniseNeural", "name": "Denise", "gender": "female", "locale": "fr-FR"},
    {"id": "fr-FR-EloiseNeural", "name": "Eloise", "gender": "female", "locale": "fr-FR"},
    {"id": "fr-FR-HenriNeural", "name": "Henri", "gender": "male", "locale": "fr-FR"},
    {"id": "fr-BE-CharlineNeural", "name": "Charline", "gender": "female", "locale": "fr-BE"},
    {"id": "fr-BE-GerardNeural", "name": "Gerard", "gender": "male", "locale": "fr-BE"},
    {"id": "fr-CA-SylvieNeural", "name": "Sylvie", "gender": "female", "locale": "fr-CA"},
    {"id": "fr-CA-AntoineNeural", "name": "Antoine", "gender": "male", "locale": "fr-CA"},
]

DEFAULT_VOICE = "fr-FR-DeniseNeural"

VOICE_IDS = {v["id"] for v in VOICES}

# ---------------------------------------------------------------------------
# Emoji → natural interjection mapping (French)
# The TTS will *act out* these emojis instead of ignoring or describing them.
# ---------------------------------------------------------------------------
EMOJI_INTERJECTIONS: dict[str, str] = {
    # Laughter / joy
    "😂": " ha ha ha ! ",
    "🤣": " ah ah ah ! ",
    "😆": " hé hé ! ",
    "😄": " ha ha ! ",
    "😁": " hé hé ! ",
    "😅": " hé hé, bon… ",
    "🙂": "",
    "😊": "",
    "☺️": "",
    # Surprise / shock
    "😮": " oh ! ",
    "😲": " oh là là ! ",
    "😱": " aah ! ",
    "🤯": " woh ! ",
    "😳": " oh ! ",
    # Sadness
    "😢": " … ",
    "😭": " ooh… ",
    "🥺": " … ",
    "😞": " pfff… ",
    "😔": " … ",
    # Thinking / doubt
    "🤔": " hmm… ",
    "🧐": " hmm… ",
    # Love / affection
    "❤️": "",
    "🥰": "",
    "😍": " oh ! ",
    "💕": "",
    "💖": "",
    "😘": "",
    # Anger / frustration
    "😡": " roh ! ",
    "😤": " pfff ! ",
    "🤬": "",
    # Relief / cool
    "😌": " aah… ",
    "😎": "",
    "🤗": "",
    # Disgust / meh
    "🤢": " beurk ! ",
    "🤮": " beurk ! ",
    "😒": " mouais… ",
    "🙄": " pfff… ",
    # Greeting / celebration
    "👋": "",
    "🎉": " yeah ! ",
    "🥳": " ouais ! ",
    "🎊": "",
    # Misc
    "🤷": " bof… ",
    "👍": "",
    "👎": "",
    "🤞": "",
    "💪": "",
    "🔥": "",
    "✨": "",
}

# Regex to strip any remaining emojis not in the map
_EMOJI_RE = re.compile(
    "[\U0001f600-\U0001f64f"  # emoticons
    "\U0001f300-\U0001f5ff"  # symbols & pictographs
    "\U0001f680-\U0001f6ff"  # transport & map
    "\U0001f900-\U0001f9ff"  # supplemental symbols
    "\U0001fa00-\U0001fa6f"  # chess symbols
    "\U0001fa70-\U0001faff"  # symbols extended-A
    "\U00002702-\U000027b0"  # dingbats
    "\U0000fe00-\U0000fe0f"  # variation selectors
    "\U0000200d"  # ZWJ
    "\U000020e3"  # combining enclosing keycap
    "]+",
    flags=re.UNICODE,
)


def prepare_text_for_tts(text: str) -> str:
    """Replace emojis with natural interjections, strip the rest."""
    for emoji, interjection in EMOJI_INTERJECTIONS.items():
        text = text.replace(emoji, interjection)

    # Remove any leftover emojis
    text = _EMOJI_RE.sub("", text)

    # Collapse multiple spaces / clean up
    text = re.sub(r"  +", " ", text).strip()

    return text


def get_voices() -> list[dict]:
    return VOICES


async def synthesize_speech(text: str, voice: str = DEFAULT_VOICE) -> AsyncGenerator[bytes, None]:
    """Stream MP3 chunks from Edge TTS."""
    text = prepare_text_for_tts(text)
    communicate = edge_tts.Communicate(text, voice)
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            yield chunk["data"]
