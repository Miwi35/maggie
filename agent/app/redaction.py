"""Known secret values, masked wherever text leaves toward the model, the logs or GlitchTip (MAG-871).

`[masqué]` is the only mask. `known_secrets()` is the contract other parts rely on: the settings that
hold a secret, the passwords of the service URLs, then patterns (`sk-` keys, JWTs, Luhn-valid card numbers).
"""

import logging
import re
from urllib.parse import urlsplit

from app.config import settings

MASK = "[masqué]"

SECRET_SETTINGS = (
    "anthropic_api_key",
    "openai_api_key",
    "elevenlabs_api_key",
    "gradium_api_key",
    "e2e_login_token",
    "service_token",
    "a2a_token",
    "mercure_jwt_secret",
    "memory_application_key",
    "sentry_dsn",
)
SECRET_URL_SETTINGS = ("agent_database_url", "rabbitmq_url")

# Shorter values (« guest », « maggie » in the dev URLs) are not secrets worth masking everywhere.
_MIN_LENGTH = 8

_SK_KEY = re.compile(r"(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}")
_JWT = re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}")
# Never inside a word: the digits of a UUID or a ULID are not a card, and a masked identifier breaks the tool loop.
# An escaped `\n`, `\t` or `\r` of a JSON tool result still starts one.
_CARD = re.compile(r"(?:(?<=\\[ntr])|(?<![\w-]))(?<!\d[ -])[2-6](?:[ -]?\d){12,18}(?![ -]?\d)(?!-?\w)")


def known_secrets() -> list[str]:
    """The secret values currently configured, longest first."""
    values: list[str] = []
    for name in SECRET_SETTINGS:
        values.append(str(getattr(settings, name, "") or ""))
    for name in SECRET_URL_SETTINGS:
        try:
            values.append(urlsplit(str(getattr(settings, name, "") or "")).password or "")
        except ValueError:
            continue
    return sorted({v for v in values if len(v) >= _MIN_LENGTH}, key=len, reverse=True)


def _luhn_ok(digits: str) -> bool:
    total = 0
    for i, char in enumerate(reversed(digits)):
        n = int(char)
        if i % 2 == 1:
            n *= 2
            if n > 9:
                n -= 9
        total += n
    return total % 10 == 0


def _mask_card(match: re.Match) -> str:
    digits = re.sub(r"\D", "", match.group(0))
    # A SIRET is 14 Luhn-valid digits; only the 14-digit Diners prefixes count as a card.
    if len(digits) == 14 and not digits.startswith(("30", "36", "38")):
        return match.group(0)
    if 13 <= len(digits) <= 19 and _luhn_ok(digits):
        return MASK
    return match.group(0)


def redact(text: str) -> str:
    if not text:
        return text
    for secret in known_secrets():
        text = text.replace(secret, MASK)
    text = _SK_KEY.sub(MASK, text)
    text = _JWT.sub(MASK, text)
    return _CARD.sub(_mask_card, text)


def contains_secret(text: str) -> bool:
    return redact(text) != text


def redact_event(event):
    """Every string of a nested dict / list, masked."""
    if isinstance(event, str):
        return redact(event)
    if isinstance(event, dict):
        return {key: redact_event(value) for key, value in event.items()}
    if isinstance(event, list):
        return [redact_event(value) for value in event]
    return event


class RedactingFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        masked = redact(message)
        if masked != message:
            record.msg = masked
            record.args = None
        if record.exc_info and not record.exc_text:
            record.exc_text = logging.Formatter().formatException(record.exc_info)
        if record.exc_text:
            record.exc_text = redact(record.exc_text)
        if record.stack_info:
            record.stack_info = redact(record.stack_info)
        return True


def install_log_redaction() -> None:
    """On every root and uvicorn handler: a logger filter does not see records propagated from its children."""
    log_filter = RedactingFilter()
    for name in ("", "uvicorn", "uvicorn.error", "uvicorn.access"):
        for handler in logging.getLogger(name).handlers:
            if not any(isinstance(f, RedactingFilter) for f in handler.filters):
                handler.addFilter(log_filter)
