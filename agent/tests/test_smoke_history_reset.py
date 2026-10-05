"""The technical smoke account starts every run from an empty conversation (MAG-253).

The post-deploy smoke suite asks Maggie the same question on every deployment. After fifty
runs the history held fifty identical question/answer pairs, and the model answered from it
without calling the tool — twice in a row, which rolled production back twice. The suite now
wipes the account's history first; these tests pin what that wipe may and may not touch.
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
from app.auth import SMOKE_ACCOUNT_EMAIL
from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.db.models import Message
from app.llm.history import build_history

SMOKE_ID = "01SMOKE0000000000000000000"
OWNER_ID = "01OWNER0000000000000000000"
QUESTION = "Appelle l'outil get_upcoming_events, puis réponds en une phrase : combien d'événements vois-tu ?"
ANSWER = "Vous n'avez aucun événement prévu pour les 7 prochains jours."

_private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_public_key_pem = (
    _private_key.public_key()
    .public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    .decode()
)
_private_key_pem = _private_key.private_bytes(
    serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()
).decode()


def _token(user_id: str, email: str | None) -> dict:
    """What the API's JWT carries: `sub` is the user's id, `username` the login identifier — the email."""
    claims = {"sub": user_id}
    if email is not None:
        claims["username"] = email
    return {"Authorization": f"Bearer {jwt.encode(claims, _private_key_pem, algorithm='RS256')}"}


@pytest.fixture()
def api():
    app = FastAPI()
    app.include_router(router)
    with patch("app.auth._get_public_key", return_value=_public_key_pem), TestClient(app) as client:
        yield client


async def _talk(user_id: str, exchanges: int, context_id: str | None = None) -> None:
    for _ in range(exchanges):
        await message_repo.create(user_id=user_id, role="user", content=QUESTION, context_id=context_id)
        await message_repo.create(user_id=user_id, role="assistant", content=ANSWER, context_id=context_id)


async def _stored(chat_db, user_id: str) -> list[Message]:
    async with chat_db.session() as session:
        return list((await session.execute(select(Message).where(Message.user_id == user_id))).scalars().all())


class TestTheHistoryThatMadeTheSmokeFail:
    async def test_identical_questions_pile_up_in_what_the_model_is_sent(self, chat_db):
        context = await context_repo.create(SMOKE_ID, "Agenda")
        await _talk(SMOKE_ID, 50, context_id=str(context.id))

        turns = await build_history(SMOKE_ID, context_id=str(context.id), pending_message=QUESTION)

        # The reproduction: the model is shown the very question it is asked, answered
        # many times without a tool call, which is what it then imitates.
        asked_before = [turn for turn in turns if turn["role"] == "user" and QUESTION in turn["content"]]
        answers_without_tool = [turn for turn in turns if turn["role"] == "assistant" and ANSWER in turn["content"]]
        assert len(asked_before) > 1
        assert len(answers_without_tool) > 1

    async def test_after_the_reset_the_model_is_sent_the_question_alone(self, chat_db, api):
        context = await context_repo.create(SMOKE_ID, "Agenda")
        await _talk(SMOKE_ID, 50, context_id=str(context.id))

        response = api.delete("/smoke/history", headers=_token(SMOKE_ID, SMOKE_ACCOUNT_EMAIL))
        assert response.status_code == 200

        turns = await build_history(SMOKE_ID, context_id=None, pending_message=QUESTION)
        assert turns == [{"role": "user", "content": QUESTION}]


class TestTheResetIsTheTechnicalAccountsOwn:
    async def test_it_wipes_the_messages_and_threads_of_the_technical_account(self, chat_db, api):
        context = await context_repo.create(SMOKE_ID, "Agenda")
        await _talk(SMOKE_ID, 3, context_id=str(context.id))

        response = api.delete("/smoke/history", headers=_token(SMOKE_ID, SMOKE_ACCOUNT_EMAIL))

        assert response.status_code == 200
        assert response.json() == {"deletedMessages": 6, "deletedContexts": 1}
        assert await _stored(chat_db, SMOKE_ID) == []
        assert await context_repo.find_by_user(SMOKE_ID) == []

    async def test_nothing_of_another_account_is_touched(self, chat_db, api):
        owner_context = await context_repo.create(OWNER_ID, "Courses")
        await _talk(OWNER_ID, 4, context_id=str(owner_context.id))
        smoke_context = await context_repo.create(SMOKE_ID, "Agenda")
        await _talk(SMOKE_ID, 4, context_id=str(smoke_context.id))

        api.delete("/smoke/history", headers=_token(SMOKE_ID, SMOKE_ACCOUNT_EMAIL))

        assert len(await _stored(chat_db, OWNER_ID)) == 8
        assert [str(ctx.id) for ctx in await context_repo.find_by_user(OWNER_ID)] == [str(owner_context.id)]

    async def test_another_account_cannot_use_it_to_wipe_its_own_history(self, chat_db, api):
        await _talk(OWNER_ID, 4)

        response = api.delete("/smoke/history", headers=_token(OWNER_ID, "owner@example.com"))

        assert response.status_code == 403
        assert len(await _stored(chat_db, OWNER_ID)) == 8

    async def test_a_token_that_names_no_account_is_refused(self, chat_db, api):
        await _talk(OWNER_ID, 2)

        response = api.delete("/smoke/history", headers=_token(OWNER_ID, None))

        assert response.status_code == 403
        assert len(await _stored(chat_db, OWNER_ID)) == 4

    async def test_the_technical_email_does_not_open_another_users_id(self, chat_db, api):
        # The wipe follows the token's `sub`, never a parameter: no way to aim it at someone else.
        await _talk(OWNER_ID, 2)

        response = api.delete(
            "/smoke/history", params={"user_id": OWNER_ID}, headers=_token(SMOKE_ID, SMOKE_ACCOUNT_EMAIL)
        )

        assert response.status_code == 200
        assert response.json() == {"deletedMessages": 0, "deletedContexts": 0}
        assert len(await _stored(chat_db, OWNER_ID)) == 4

    def test_without_a_token_it_is_refused(self, api):
        assert api.delete("/smoke/history").status_code in (401, 403)

    async def test_an_empty_history_is_a_success(self, chat_db, api):
        response = api.delete("/smoke/history", headers=_token(SMOKE_ID, SMOKE_ACCOUNT_EMAIL))

        assert response.status_code == 200
        assert response.json() == {"deletedMessages": 0, "deletedContexts": 0}
