import io
import json
import logging
from unittest.mock import patch

import pytest

from app.config import Settings, settings
from app.redaction import (
    MASK,
    SECRET_SETTINGS,
    SECRET_URL_SETTINGS,
    RedactingFilter,
    install_log_redaction,
    redact,
    redact_event,
)

TOKEN = "svc-0123456789abcdef"


def test_a_configured_token_is_masked():
    with patch.object(settings, "service_token", TOKEN):
        assert redact(f"Bearer {TOKEN} refusé") == f"Bearer {MASK} refusé"


def test_the_password_of_a_service_url_is_masked():
    url = "postgresql://maggie:s3cretPassw0rd@database:5432/maggie_agent"
    with patch.object(settings, "agent_database_url", url):
        assert redact(f"connexion {url}") == f"connexion postgresql://maggie:{MASK}@database:5432/maggie_agent"


def test_a_configured_value_shorter_than_eight_characters_is_not_a_secret():
    with patch.object(settings, "service_token", "short"):
        assert redact("short") == "short"


def test_every_secret_setting_exists():
    for name in (*SECRET_SETTINGS, *SECRET_URL_SETTINGS):
        assert name in Settings.model_fields


@pytest.mark.parametrize("card", ["4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111"])
def test_a_luhn_valid_card_number_is_masked(card):
    assert redact(f"carte {card} ok") == f"carte {MASK} ok"


def test_a_number_that_fails_luhn_is_left_alone():
    assert redact("4111 1111 1111 1112") == "4111 1111 1111 1112"


@pytest.mark.parametrize("text", ["FR76 3000 6000 0112 3456 7890 189", "1700000000003", "ts 1700000000003", "SIRET 55203253400646"])
def test_an_iban_and_a_timestamp_are_left_alone(text):
    assert redact(text) == text


def test_an_sk_key_and_a_jwt_are_masked():
    jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop"
    assert redact("clé sk-ant-api03-abcdefghijklmnopqrstuvwx") == f"clé {MASK}"
    assert redact(f"jeton {jwt}") == f"jeton {MASK}"


def test_a_text_without_secret_comes_back_unchanged():
    text = json.dumps({"amount": 1234.56, "date": "2026-11-19", "id": "01JC5ZQ8M0T4X7Y9A2B3C4D5E6"})
    assert redact(text) == text
    assert redact("") == ""


def test_an_event_is_masked_at_every_depth():
    with patch.object(settings, "service_token", TOKEN):
        event = {"exception": {"values": [{"value": f"refusé : {TOKEN}"}]}, "extra": [["a", TOKEN]], "n": 3}
        assert redact_event(event) == {
            "exception": {"values": [{"value": f"refusé : {MASK}"}]},
            "extra": [["a", MASK]],
            "n": 3,
        }


def test_a_log_record_is_written_masked():
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.addFilter(RedactingFilter())
    logger = logging.getLogger("test_redaction_dedicated")
    logger.propagate = False
    logger.addHandler(handler)
    with patch.object(settings, "service_token", TOKEN):
        logger.error("boom %s", TOKEN)
    assert MASK in stream.getvalue()
    assert TOKEN not in stream.getvalue()


def test_a_logged_exception_trace_is_masked():
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.addFilter(RedactingFilter())
    logger = logging.getLogger("test_redaction_trace")
    logger.propagate = False
    logger.addHandler(handler)
    with patch.object(settings, "service_token", TOKEN):
        try:
            raise RuntimeError(f"refusé : {TOKEN}")
        except RuntimeError:
            logger.exception("échec")
    assert MASK in stream.getvalue()
    assert TOKEN not in stream.getvalue()


def test_uvicorn_handlers_get_the_filter():
    handler = logging.StreamHandler(io.StringIO())
    logger = logging.getLogger("uvicorn.access")
    logger.addHandler(handler)
    try:
        install_log_redaction()
        assert any(isinstance(f, RedactingFilter) for f in handler.filters)
    finally:
        logger.removeHandler(handler)
