"""A code, a PIN or a password written or dictated to the chat is held back (MAG-871).

It is replaced by `[masqué]` on reception, before anything is stored, logged or sent to a model.
The model reads the block in front of that turn and answers in its own words: the code does not
speak in Maggie's place.
"""

import re

from app.redaction import MASK, redact

HEADER = "[Message retenu]"

_ONLY_CODE = re.compile(r"\d{4,8}")
_STRIP = re.compile(r"[\s.\-!?,;:]+")
_NOT_A_CODE = (
    r"(?!\s*(?:postal|promo|de réduction|réduction|article|produit|client|insee|source|couleur|de la route)\b)"
)
_LABELLED_CODE = re.compile(
    r"\b(code|pin|cvv|cvc|cryptogramme|digicode)\b"
    + _NOT_A_CODE
    + r"([^.!?\n\d]{0,20}?)(\d(?:[ .-]?\d){2,7}[A-Za-z]?)(?![ .-]?\d)(?![\w])",
    re.IGNORECASE,
)
_PASSWORD = re.compile(
    r"\b(mot de passe|mdp|password)((?:\s+[^\s:=.,;!?]+){0,3}?\s*(?:\bc'est\b|\best\b|[:=])\s*)([^\s,;]+)",
    re.IGNORECASE,
)


def mask_owner_secrets(said: str) -> str:
    if not said:
        return said
    if _ONLY_CODE.fullmatch(_STRIP.sub("", said)) and not re.search(r"[^\d\s.\-!?,;:]", said):
        return MASK
    said = _LABELLED_CODE.sub(lambda m: f"{m.group(1)}{m.group(2)}{MASK}", said)
    said = _PASSWORD.sub(lambda m: f"{m.group(1)}{m.group(2)}{MASK}", said)
    return redact(said)


def has_owner_secret(text: str) -> bool:
    return mask_owner_secrets(text) != text


def held_block() -> str:
    return (
        f"{HEADER}\n"
        "Le message de l'utilisateur contenait un code, un PIN ou un mot de passe : il a été remplacé par "
        f"{MASK} avant de te parvenir, il n'est ni transmis ni gardé. Dis-lui en une phrase que tu ne prends pas "
        "les codes dans la conversation, sans lui demander de le redonner. S'il demandait autre chose dans le "
        "même message, réponds à cette demande."
    )


def hold_secrets(said: str, screen: str | None) -> tuple[str, str | None]:
    masked = mask_owner_secrets(said)
    if masked == said:
        return said, screen
    return masked, "\n\n".join(part for part in (held_block(), screen) if part)
