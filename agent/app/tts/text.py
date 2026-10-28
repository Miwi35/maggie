"""What is said, before any provider hears it: emojis acted out, delivery tags kept or stripped."""

import re

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


# ElevenLabs delivery tags: « [warmly] », « [softly] », « [reassuring] »… Maggie writes them for
# a voice that acts them out; a voice that cannot would read the word aloud.
_DELIVERY_TAG = r"\[[^\[\]\n]{1,40}\]"
_TAG_BEFORE_PUNCTUATION_RE = re.compile(rf"\s*{_DELIVERY_TAG}(?=\s*[.,;:!?…])")
_DELIVERY_TAG_RE = re.compile(rf"\s*{_DELIVERY_TAG}\s*")


def strip_delivery_tags(text: str) -> str:
    """The text without its `[...]` delivery tags, the words around them left readable."""
    text = _TAG_BEFORE_PUNCTUATION_RE.sub("", text)
    return re.sub(r"  +", " ", _DELIVERY_TAG_RE.sub(" ", text)).strip()
