"""Maggie's voice messages (MAG-685): said, kept in the bucket, joined to a message, played through a signed URL."""

import json
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from urllib.parse import urlparse

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import router
from app.auth import get_current_user_id
from app.db.models import Message
from app.llm.tools import ToolRouter
from app.memory.bucket import FakeBucket
from app.policy.engine import PolicyEngine
from app.tts.providers import SILENT_MP3_FRAME
from app.tts.text import to_spoken_text
from app.tts.voice_message import (
    NOTHING_TO_SAY,
    SPEECH_DOWN,
    STORAGE_DOWN,
    STORAGE_OFF,
    audio_key,
    mp3_duration_ms,
    purge_voice_messages,
)

OWNER = "01JOWNER000000000000000000"
STRANGER = "01JSTRANGER000000000000000"
DIGEST = "Bonjour ! Voici ta journée : un rendez-vous à dix heures."


@pytest.fixture()
def bucket():
    fake = FakeBucket()
    with patch("app.tts.voice_message.memory_service", SimpleNamespace(bucket=fake)), patch(
        "app.api.routes.memory_service", SimpleNamespace(bucket=fake)
    ):
        yield fake


@pytest.fixture()
def no_bucket():
    with patch("app.tts.voice_message.memory_service", SimpleNamespace(bucket=None)), patch(
        "app.api.routes.memory_service", SimpleNamespace(bucket=None)
    ):
        yield


@pytest.fixture(autouse=True)
def silent_voice(monkeypatch):
    """The fake TTS, the one e2e runs on, and the user's voice read from their settings."""
    monkeypatch.setattr("app.tts.synthesis.settings.tts_provider", "fake")
    voice = AsyncMock(return_value=SimpleNamespace(tts_voice="fr-FR-HenriNeural"))
    with patch("app.tts.voice_message.user_setting_repo.get", voice):
        yield voice


def client_as(user_id: str | None) -> TestClient:
    app = FastAPI()
    app.include_router(router)
    if user_id:
        app.dependency_overrides[get_current_user_id] = lambda: user_id
    return TestClient(app)


async def send(text: str = DIGEST, context_id: str | None = "ctx-1") -> dict:
    return json.loads(
        await ToolRouter().call_tool("send_voice_message", {"text": text}, user_id=OWNER, context_id=context_id)
    )


class TestSpokenText:
    def test_markdown_is_said_as_plain_sentences(self):
        written = "## Ta journée\n\n- **10 h** : dentiste\n- _midi_ : marché\n\n> Pense à `la carte`"

        assert to_spoken_text(written) == "Ta journée. 10 h : dentiste. midi : marché. Pense à la carte."

    def test_a_table_reads_cell_after_cell(self):
        table = "| Heure | Quoi |\n|---|---|\n| 10 h | Dentiste |"

        assert to_spoken_text(table) == "Heure, Quoi. 10 h, Dentiste."

    def test_no_url_is_read_aloud(self):
        text = "Le menu est [sur le site](https://exemple.fr/menu) et là : https://exemple.fr/x ou www.exemple.fr"

        assert to_spoken_text(text) == "Le menu est sur le site et là : ou."

    def test_delivery_tags_and_plain_text_are_left_alone(self):
        assert to_spoken_text("[warmly] Bonjour ! Belle journée à toi.") == "[warmly] Bonjour ! Belle journée à toi."


class TestDuration:
    def test_from_the_bitrate_of_the_first_frame(self):
        # MPEG-1 Layer III at 128 kbps: 16 000 bytes a second.
        assert mp3_duration_ms(SILENT_MP3_FRAME * 160) == round(len(SILENT_MP3_FRAME) * 160 * 8 / 128)

    def test_an_id3_tag_is_skipped(self):
        tag = b"ID3\x04\x00\x00\x00\x00\x00\x0a" + b"\x00" * 10
        assert mp3_duration_ms(tag + SILENT_MP3_FRAME * 10) == mp3_duration_ms(SILENT_MP3_FRAME * 10)

    def test_nothing_that_is_mp3_lasts_nothing(self):
        assert mp3_duration_ms(b"pas du son") == 0


class TestTheTool:
    async def test_the_vocal_lands_in_the_thread_with_its_audio(self, chat_db, bucket, silent_voice):
        result = await send()

        assert result["sent"] is True
        async with chat_db.session() as session:
            [message] = (await session.execute(Message.__table__.select())).all()
        assert (message.user_id, message.role, message.context_id, message.content) == (
            OWNER,
            "assistant",
            "ctx-1",
            DIGEST,
        )
        stored = bucket.objects[audio_key(OWNER, message.id)]
        assert stored.body.startswith(SILENT_MP3_FRAME)
        assert bucket.content_types[audio_key(OWNER, message.id)] == "audio/mpeg"
        assert message.audio == {
            "type": "audio/mpeg",
            "durationMs": mp3_duration_ms(stored.body),
            "size": len(stored.body),
            "transcript": DIGEST,
        }
        silent_voice.assert_awaited_once_with(OWNER)

    async def test_the_mercure_payload_carries_the_attachment(self, chat_db, bucket):
        result = await send()

        [call] = chat_db.chat_published.await_args_list
        topic, payload = call.args
        assert topic == f"/chat/{OWNER}"
        assert payload["id"] == result["messageId"]
        assert payload["audio"] == {
            "type": "audio/mpeg",
            "durationMs": result["durationMs"],
            "size": len(bucket.objects[audio_key(OWNER, result["messageId"])].body),
            "url": f"/agent/messages/{result['messageId']}/audio",
            "transcript": DIGEST,
        }

    async def test_the_thread_reads_it_back_with_the_attachment(self, chat_db, bucket):
        result = await send()

        [message] = client_as(OWNER).get("/messages").json()
        assert message["id"] == result["messageId"]
        assert message["audio"]["url"] == f"/agent/messages/{result['messageId']}/audio"

    async def test_markdown_is_said_as_text_for_the_ear(self, chat_db, bucket):
        await send("**Bonjour** ! Voir https://exemple.fr")

        [payload] = [c.args[1] for c in chat_db.chat_published.await_args_list]
        assert payload["content"] == payload["audio"]["transcript"] == "Bonjour ! Voir."

    async def test_without_memory_and_bucket_a_clear_error_and_no_message(self, chat_db, no_bucket):
        assert await send() == {"error": STORAGE_OFF}
        chat_db.chat_published.assert_not_awaited()

    async def test_a_bucket_that_is_down_is_a_clear_error_and_no_message(self, chat_db, bucket):
        bucket.down = True

        assert await send() == {"error": STORAGE_DOWN}
        chat_db.chat_published.assert_not_awaited()

    async def test_a_speech_service_that_is_down_is_a_clear_error(self, chat_db, bucket):
        from app.tts.synthesis import SpeechUnavailableError

        with patch("app.tts.voice_message.synthesize_file", AsyncMock(side_effect=SpeechUnavailableError)):
            assert await send() == {"error": SPEECH_DOWN}
        assert bucket.objects == {}

    async def test_an_empty_text_says_nothing(self, chat_db, bucket):
        assert await send("  ") == {"error": NOTHING_TO_SAY}

    async def test_it_is_offered_to_the_model(self):
        with patch("app.llm.tools.mcp_client") as mcp:
            mcp.list_tools = AsyncMock(return_value=[])
            names = [t["name"] for t in await ToolRouter().get_tool_definitions()]
            a2a = [t["name"] for t in await ToolRouter().get_tool_definitions(source="a2a")]

        assert "send_voice_message" in names and "send_voice_message" not in a2a

    async def test_the_policy_still_has_its_say(self, chat_db, bucket):
        deny = PolicyEngine.from_mapping({"default": "allow", "rules": [{"tools": ["send_*"], "mode": "deny"}]})
        with patch("app.llm.tools.policy_engine", deny):
            assert "error" in await send()
        assert bucket.objects == {}


class TestTheAudioRoute:
    async def test_the_owner_gets_a_signed_url_that_plays(self, chat_db, bucket):
        result = await send()
        client = client_as(OWNER)

        response = client.get(f"/messages/{result['messageId']}/audio")

        assert response.status_code == 200
        body = response.json()
        assert body["type"] == "audio/mpeg" and body["transcript"] == DIGEST
        assert datetime.fromisoformat(body["expiresAt"]) > datetime.now(UTC)
        played = client_as(None).get(urlparse(body["url"]).path.removeprefix("/agent") + "?" + urlparse(body["url"]).query)
        assert played.status_code == 200
        assert played.headers["content-type"] == "audio/mpeg"
        assert played.content == bucket.objects[audio_key(OWNER, result["messageId"])].body

    async def test_still_readable_the_next_day(self, chat_db, bucket):
        result = await send()
        async with chat_db.session() as session:
            await session.execute(
                Message.__table__.update().values(created_at=datetime.now(UTC) - timedelta(days=1, hours=2))
            )
            await session.commit()

        body = client_as(OWNER).get(f"/messages/{result['messageId']}/audio").json()
        url = urlparse(body["url"])

        assert client_as(None).get(url.path.removeprefix("/agent") + "?" + url.query).status_code == 200

    async def test_another_users_token_gets_403(self, chat_db, bucket):
        result = await send()

        assert client_as(STRANGER).get(f"/messages/{result['messageId']}/audio").status_code == 403

    def test_without_a_token_401_or_403(self):
        assert client_as(None).get("/messages/any/audio").status_code in (401, 403)

    async def test_a_message_without_audio_is_404(self, chat_db, bucket):
        from app.db.message_repository import message_repo

        message = await message_repo.create(OWNER, "assistant", "Juste du texte")

        assert client_as(OWNER).get(f"/messages/{message.id}/audio").status_code == 404
        assert client_as(OWNER).get("/messages/inconnu/audio").status_code == 404

    async def test_a_bucket_switched_off_since_is_a_503_not_a_crash(self, chat_db, bucket):
        result = await send()
        with patch("app.tts.voice_message.memory_service", SimpleNamespace(bucket=None)):
            response = client_as(OWNER).get(f"/messages/{result['messageId']}/audio")

        assert response.status_code == 503 and response.json()["detail"] == STORAGE_OFF

    async def test_a_forged_or_expired_signature_reads_nothing(self, chat_db, bucket):
        result = await send()
        key = audio_key(OWNER, result["messageId"])
        anon = client_as(None)

        assert anon.get(f"/fake-bucket/{key}?expires=9999999999&signature=faux").status_code == 403
        expired = 1
        assert anon.get(f"/fake-bucket/{key}?expires={expired}&signature={bucket._signature(key, expired)}").status_code == 403

    def test_the_fake_bucket_route_is_closed_on_a_real_bucket(self):
        with patch("app.api.routes.memory_service", SimpleNamespace(bucket=object())):
            assert client_as(None).get("/fake-bucket/audio/x.mp3?expires=1&signature=s").status_code == 404


class TestRetention:
    async def test_the_audio_older_than_the_retention_is_purged(self, chat_db, bucket, monkeypatch):
        monkeypatch.setattr("app.tts.voice_message.settings.voice_message_retention_days", 30)
        old, recent = await send("Vieux vocal"), await send("Vocal récent")
        now = datetime.now(UTC)
        async with chat_db.session() as session:
            await session.execute(
                Message.__table__.update()
                .where(Message.__table__.c.id == old["messageId"])
                .values(created_at=now - timedelta(days=31))
            )
            await session.commit()
        old_key, recent_key = audio_key(OWNER, old["messageId"]), audio_key(OWNER, recent["messageId"])
        bucket.seed(old_key, bucket.objects[old_key].body, at=now - timedelta(days=31))
        orphan = audio_key(OWNER, "supprime")
        bucket.seed(orphan, SILENT_MP3_FRAME, at=now - timedelta(days=40))
        bucket.seed(f"{OWNER}/Mémoire/a.md", "fiche", at=now - timedelta(days=400))

        assert await purge_voice_messages(now) == {"objects": 2, "messages": 1}

        assert old_key not in bucket.objects and orphan not in bucket.objects
        assert recent_key in bucket.objects and f"{OWNER}/Mémoire/a.md" in bucket.objects
        messages = {m["id"]: m for m in client_as(OWNER).get("/messages").json()}
        assert "audio" not in messages[old["messageId"]]
        assert messages[old["messageId"]]["content"] == "Vieux vocal."
        assert "audio" in messages[recent["messageId"]]

    async def test_no_bucket_nothing_to_purge(self, no_bucket):
        assert await purge_voice_messages() == {"objects": 0, "messages": 0}


class TestTheAnswerOfATurn:
    async def test_a_vocal_sent_on_the_way_is_not_taken_for_the_answer(self, chat_db, bucket):
        """A duplicate `POST /chat` returns the turn's answer, not the vocal stored just before it."""
        from app.db.message_repository import message_repo

        question = await message_repo.create(OWNER, "user", "Envoie-moi un vocal")
        await send()
        answer = await message_repo.create(OWNER, "assistant", "Je t'ai envoyé un vocal.")

        assert (await message_repo.find_answer_to(OWNER, question)).id == answer.id


def test_the_fake_bucket_serves_nothing_but_audio(bucket):
    bucket.seed(f"{OWNER}/Mémoire/a.md", "fiche")
    key = f"{OWNER}/Mémoire/a.md"
    expires = 9999999999

    response = client_as(None).get(f"/fake-bucket/{key}?expires={expires}&signature={bucket._signature(key, expires)}")

    assert response.status_code == 404
