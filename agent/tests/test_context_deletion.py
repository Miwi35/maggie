"""The owner deletes a thread or a message from the chat (MAG-342).

The conversation is one; threads are the agent's own filing, and the owner has to be able to
clean it. What is pinned here: a user only ever reaches their own data (404 for another's,
like for an unknown id), the rows are really gone, and the other devices are told through
Mercure.
"""

from unittest.mock import patch

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.api.routes import router
from app.db.context_model import ConversationContext
from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.db.models import Message
from app.mercure import topics

OWNER = "01OWNER0000000000000000000"
OTHER = "01OTHER0000000000000000000"

_private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_public_key_pem = (
    _private_key.public_key()
    .public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    .decode()
)
_private_key_pem = _private_key.private_bytes(
    serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()
).decode()


def _as(user_id: str) -> dict:
    return {"Authorization": f"Bearer {jwt.encode({'sub': user_id}, _private_key_pem, algorithm='RS256')}"}


@pytest.fixture()
def api():
    app = FastAPI()
    app.include_router(router)
    with patch("app.auth._get_public_key", return_value=_public_key_pem), TestClient(app) as client:
        yield client


async def _rows(chat_db, model, user_id: str) -> list:
    async with chat_db.session() as session:
        return list((await session.execute(select(model).where(model.user_id == user_id))).scalars().all())


async def _thread(user_id: str, label: str, messages: int) -> tuple[str, list[str]]:
    context = await context_repo.create(user_id, label)
    ids = []
    for index in range(messages):
        role = "user" if index % 2 == 0 else "assistant"
        message = await message_repo.create(user_id, role, f"{label} {index}", context_id=str(context.id))
        ids.append(str(message.id))
    return str(context.id), ids


class TestDeleteThread:
    async def test_it_requires_authentication(self, chat_db, api):
        assert api.delete("/contexts/whatever").status_code in (401, 403)

    async def test_the_thread_and_its_messages_are_gone_from_the_database(self, chat_db, api):
        context_id, _ = await _thread(OWNER, "Courses", 4)
        other_id, _ = await _thread(OWNER, "Budget", 2)

        response = api.delete(f"/contexts/{context_id}", headers=_as(OWNER))

        assert response.status_code == 200
        assert response.json() == {"deletedMessages": 4}
        assert [str(c.id) for c in await _rows(chat_db, ConversationContext, OWNER)] == [other_id]
        assert {m.context_id for m in await _rows(chat_db, Message, OWNER)} == {other_id}

    async def test_the_thread_of_another_user_is_a_404_and_stays_whole(self, chat_db, api):
        context_id, _ = await _thread(OTHER, "Courses", 3)
        chat_db.published.reset_mock()

        response = api.delete(f"/contexts/{context_id}", headers=_as(OWNER))

        assert response.status_code == 404
        assert len(await _rows(chat_db, ConversationContext, OTHER)) == 1
        assert len(await _rows(chat_db, Message, OTHER)) == 3
        chat_db.published.assert_not_awaited()

    async def test_an_unknown_thread_is_a_404(self, chat_db, api):
        assert api.delete("/contexts/nope", headers=_as(OWNER)).status_code == 404

    async def test_the_other_devices_are_told_on_both_streams(self, chat_db, api):
        context_id, message_ids = await _thread(OWNER, "Courses", 2)
        chat_db.published.reset_mock()

        api.delete(f"/contexts/{context_id}", headers=_as(OWNER))

        published = {call.args[0]: call.args[1] for call in chat_db.published.await_args_list}
        assert published[topics.for_user(topics.CONTEXTS, OWNER)] == {"id": context_id, "deleted": True}
        chat_event = published[topics.for_user(topics.CHAT, OWNER)]
        assert chat_event["deleted"] is True
        assert chat_event["contextId"] == context_id
        assert sorted(chat_event["messageIds"]) == sorted(message_ids)

    async def test_a_hub_that_is_down_does_not_undo_the_deletion(self, chat_db, api):
        context_id, _ = await _thread(OWNER, "Courses", 2)
        chat_db.published.side_effect = RuntimeError("hub down")

        response = api.delete(f"/contexts/{context_id}", headers=_as(OWNER))

        assert response.status_code == 200
        assert await _rows(chat_db, ConversationContext, OWNER) == []


class TestDeleteMessage:
    async def test_it_requires_authentication(self, chat_db, api):
        assert api.delete("/messages/whatever").status_code in (401, 403)

    async def test_only_that_message_is_gone_from_the_database(self, chat_db, api):
        context_id, ids = await _thread(OWNER, "Courses", 3)

        response = api.delete(f"/messages/{ids[1]}", headers=_as(OWNER))

        assert response.status_code == 204
        assert sorted(str(m.id) for m in await _rows(chat_db, Message, OWNER)) == sorted([ids[0], ids[2]])
        assert len(await _rows(chat_db, ConversationContext, OWNER)) == 1

    async def test_the_message_of_another_user_is_a_404_and_stays(self, chat_db, api):
        _, ids = await _thread(OTHER, "Courses", 2)
        chat_db.chat_published.reset_mock()

        response = api.delete(f"/messages/{ids[0]}", headers=_as(OWNER))

        assert response.status_code == 404
        assert len(await _rows(chat_db, Message, OTHER)) == 2
        chat_db.chat_published.assert_not_awaited()

    async def test_an_unknown_message_is_a_404(self, chat_db, api):
        assert api.delete("/messages/nope", headers=_as(OWNER)).status_code == 404

    async def test_the_other_devices_are_told_on_the_chat_stream(self, chat_db, api):
        _, ids = await _thread(OWNER, "Courses", 2)
        chat_db.chat_published.reset_mock()

        api.delete(f"/messages/{ids[0]}", headers=_as(OWNER))

        chat_db.chat_published.assert_awaited_once_with(
            topics.for_user(topics.CHAT, OWNER), {"deleted": True, "contextId": None, "messageIds": [ids[0]]}
        )


class TestListThreads:
    async def test_each_thread_says_how_many_messages_it_holds(self, chat_db, api):
        context_id, _ = await _thread(OWNER, "Courses", 3)
        empty = await context_repo.create(OWNER, "Vide")

        body = api.get("/contexts", headers=_as(OWNER)).json()

        assert {c["id"]: c["messageCount"] for c in body} == {context_id: 3, str(empty.id): 0}

    async def test_a_closed_thread_is_listed_only_when_asked(self, chat_db, api):
        from app.db.context_model import ContextStatus

        context_id, _ = await _thread(OWNER, "Ancien", 2)
        await context_repo.update_status(context_id, ContextStatus.CLOSED)

        assert api.get("/contexts", headers=_as(OWNER)).json() == []
        everything = api.get("/contexts?includeClosed=true", headers=_as(OWNER)).json()
        assert [c["id"] for c in everything] == [context_id]

    async def test_another_users_threads_are_not_counted_nor_listed(self, chat_db, api):
        await _thread(OTHER, "Courses", 3)

        assert api.get("/contexts?includeClosed=true", headers=_as(OWNER)).json() == []
