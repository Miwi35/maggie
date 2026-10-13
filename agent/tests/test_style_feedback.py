"""The owner's thumbs on Maggie's answers, and the directives she proposes from them (MAG-23).

What is pinned here: a thumb lands on the answer it was given to and nowhere else; only the
owner's own answers can be rated; the synthesis reads the new thumbs only, and what it
draws from them is *proposed* — nothing reaches the behaviour directives before the owner
accepts it, and a refused proposal is shown to the next pass so it is not offered again.
"""

from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import select

from app.api.routes import router
from app.db.instruction_model import Instruction, InstructionKind
from app.db.message_repository import message_repo
from app.db.models import Message
from app.db.style_proposal_model import StyleProposal, StyleProposalStatus
from app.db.style_proposal_repository import style_proposal_repo
from app.db.user_setting_model import UserSetting
from app.db.user_setting_repository import user_setting_repo
from app.llm import style_synthesis
from app.llm.style_synthesis import StyleSynthesizer, _parse
from tests.conftest import _SyncSessionAsAsync

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
def db():
    """Real SQLite behind every repository the feature touches."""
    from sqlalchemy import create_engine
    from sqlalchemy.orm import sessionmaker
    from sqlalchemy.pool import StaticPool

    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
    for model in (Message, Instruction, StyleProposal, UserSetting):
        model.__table__.create(engine)
    factory = sessionmaker(engine, expire_on_commit=False)

    def open_session():
        return _SyncSessionAsAsync(factory())

    with (
        patch("app.db.message_repository.agent_session", open_session),
        patch("app.db.instruction_repository.agent_session", open_session),
        patch("app.db.style_proposal_repository.agent_session", open_session),
        patch("app.db.user_setting_repository.agent_session", open_session),
        patch("app.db.message_repository.message_repo.publisher.publish", new=AsyncMock()),
        patch("app.db.instruction_repository.instruction_repo.publisher.publish", new=AsyncMock()),
    ):
        yield open_session
    engine.dispose()


@pytest.fixture()
def api():
    app = FastAPI()
    app.include_router(router)
    with patch("app.auth._get_public_key", return_value=_public_key_pem), TestClient(app) as client:
        yield client


async def _rows(db, model, **where) -> list:
    async with db() as session:
        query = select(model)
        for column, value in where.items():
            query = query.where(getattr(model, column) == value)
        return list((await session.execute(query)).scalars().all())


async def _exchange(user_id: str, question: str, answer: str) -> tuple[Message, Message]:
    asked = await message_repo.create(user_id, "user", question)
    answered = await message_repo.create(user_id, "assistant", answer)
    return asked, answered


def _model_answering(text: str):
    client = SimpleNamespace(messages=SimpleNamespace(create=AsyncMock()))
    client.messages.create.return_value = SimpleNamespace(
        content=[SimpleNamespace(type="text", text=text)],
        usage=SimpleNamespace(input_tokens=10, output_tokens=5),
    )
    return client


class TestRateAnAnswer:
    async def test_it_requires_authentication(self, db, api):
        assert api.put("/messages/whatever/feedback", json={"rating": "up"}).status_code in (401, 403)

    async def test_a_thumb_down_with_a_comment_is_stored_on_the_answer(self, db, api):
        _, answer = await _exchange(OWNER, "Quel temps demain ?", "Un très long paragraphe…")

        response = api.put(
            f"/messages/{answer.id}/feedback", json={"rating": "down", "comment": " Trop long "}, headers=_as(OWNER)
        )

        assert response.status_code == 200
        assert response.json()["feedback"] == "down"
        assert response.json()["feedbackComment"] == "Trop long"
        [stored] = await _rows(db, Message, id=answer.id)
        assert (stored.feedback, stored.feedback_comment) == ("down", "Trop long")
        assert stored.feedback_at is not None

    async def test_rating_again_replaces_the_thumb_and_drops_the_old_comment(self, db, api):
        _, answer = await _exchange(OWNER, "Q", "R")
        api.put(f"/messages/{answer.id}/feedback", json={"rating": "down", "comment": "Bof"}, headers=_as(OWNER))

        api.put(f"/messages/{answer.id}/feedback", json={"rating": "up"}, headers=_as(OWNER))

        [stored] = await _rows(db, Message, id=answer.id)
        assert (stored.feedback, stored.feedback_comment) == ("up", None)

    async def test_the_thumb_can_be_taken_back(self, db, api):
        _, answer = await _exchange(OWNER, "Q", "R")
        api.put(f"/messages/{answer.id}/feedback", json={"rating": "up"}, headers=_as(OWNER))

        response = api.delete(f"/messages/{answer.id}/feedback", headers=_as(OWNER))

        assert response.status_code == 200
        [stored] = await _rows(db, Message, id=answer.id)
        assert (stored.feedback, stored.feedback_at) == (None, None)

    async def test_the_history_carries_the_thumb(self, db, api):
        _, answer = await _exchange(OWNER, "Q", "R")
        api.put(f"/messages/{answer.id}/feedback", json={"rating": "up"}, headers=_as(OWNER))

        messages = api.get("/messages", headers=_as(OWNER)).json()

        assert [m["feedback"] for m in messages] == [None, "up"]

    @pytest.mark.parametrize(
        "body", [{"rating": "meh"}, {}, {"rating": "up", "comment": "x" * 1001}], ids=["unknown", "missing", "long"]
    )
    async def test_a_bad_rating_is_refused(self, db, api, body):
        _, answer = await _exchange(OWNER, "Q", "R")

        assert api.put(f"/messages/{answer.id}/feedback", json=body, headers=_as(OWNER)).status_code == 422
        [stored] = await _rows(db, Message, id=answer.id)
        assert stored.feedback is None

    async def test_the_owners_own_message_cannot_be_rated(self, db, api):
        question, _ = await _exchange(OWNER, "Q", "R")

        assert api.put(f"/messages/{question.id}/feedback", json={"rating": "up"}, headers=_as(OWNER)).status_code == 404

    async def test_another_users_answer_is_a_404_and_stays_unrated(self, db, api):
        _, answer = await _exchange(OTHER, "Q", "R")

        response = api.put(f"/messages/{answer.id}/feedback", json={"rating": "down"}, headers=_as(OWNER))

        assert response.status_code == 404
        [stored] = await _rows(db, Message, id=answer.id)
        assert stored.feedback is None


class TestSynthesis:
    async def test_below_the_threshold_the_model_is_not_called(self, db):
        synthesizer = StyleSynthesizer()
        synthesizer.client = _model_answering("[]")
        _, answer = await _exchange(OWNER, "Q", "R")
        await message_repo.set_feedback(answer.id, OWNER, "down")

        assert await synthesizer.synthesize(OWNER, min_feedback=2) == []
        synthesizer.client.messages.create.assert_not_awaited()

    async def test_the_proposals_are_stored_pending_and_no_directive_is_written(self, db):
        synthesizer = StyleSynthesizer()
        synthesizer.client = _model_answering(
            'Voici : [{"directive": "Réponds en deux phrases au plus", "raison": "Deux pouces bas"}]'
        )
        _, answer = await _exchange(OWNER, "Quel temps demain ?", "Un très long paragraphe")
        await message_repo.set_feedback(answer.id, OWNER, "down", "Trop long")

        stored = await synthesizer.synthesize(OWNER, min_feedback=1)

        assert [(p.content, p.reason) for p in stored] == [("Réponds en deux phrases au plus", "Deux pouces bas")]
        [proposal] = await _rows(db, StyleProposal, user_id=OWNER)
        assert proposal.status == StyleProposalStatus.PENDING
        assert await _rows(db, Instruction, user_id=OWNER) == []
        sent = synthesizer.client.messages.create.await_args.kwargs["messages"][0]["content"]
        assert "pouce bas" in sent
        assert "Commentaire : Trop long" in sent
        assert "Utilisateur : Quel temps demain ?" in sent
        assert "Assistante : Un très long paragraphe" in sent

    async def test_a_second_pass_reads_only_the_new_thumbs(self, db):
        synthesizer = StyleSynthesizer()
        synthesizer.client = _model_answering("[]")
        _, first = await _exchange(OWNER, "Q1", "Première réponse")
        await message_repo.set_feedback(first.id, OWNER, "down")
        await synthesizer.synthesize(OWNER, min_feedback=1)

        assert await synthesizer.synthesize(OWNER, min_feedback=1) == []
        assert synthesizer.client.messages.create.await_count == 1

        _, second = await _exchange(OWNER, "Q2", "Seconde réponse")
        await message_repo.set_feedback(second.id, OWNER, "up")
        await synthesizer.synthesize(OWNER, min_feedback=1)

        sent = synthesizer.client.messages.create.await_args.kwargs["messages"][0]["content"]
        assert "Seconde réponse" in sent
        assert "Première réponse" not in sent

    async def test_a_refused_proposal_and_the_current_directives_are_shown_to_the_model(self, db):
        synthesizer = StyleSynthesizer()
        synthesizer.client = _model_answering("[]")
        refused = await style_proposal_repo.store(OWNER, "Utilise des emojis")
        await style_proposal_repo.decide(OWNER, refused.id, StyleProposalStatus.REJECTED)
        from app.db.instruction_repository import instruction_repo

        await instruction_repo.store(OWNER, "Tutoie-moi", kind=InstructionKind.BEHAVIOR)
        _, answer = await _exchange(OWNER, "Q", "R")
        await message_repo.set_feedback(answer.id, OWNER, "down")

        await synthesizer.synthesize(OWNER, min_feedback=1)

        sent = synthesizer.client.messages.create.await_args.kwargs["messages"][0]["content"]
        assert "Refusées par l'utilisateur :\n- Utilise des emojis" in sent
        assert "Directives actuelles :\n- Tutoie-moi" in sent

    async def test_a_failing_model_leaves_the_thumbs_for_the_next_pass(self, db):
        synthesizer = StyleSynthesizer()
        synthesizer.client = _model_answering("pas du JSON")
        _, answer = await _exchange(OWNER, "Q", "R")
        await message_repo.set_feedback(answer.id, OWNER, "down")

        assert await synthesizer.synthesize(OWNER, min_feedback=1) == []
        assert await user_setting_repo.get(OWNER) is None

    async def test_without_a_model_nothing_happens(self, db):
        synthesizer = StyleSynthesizer()
        synthesizer.client = None

        assert await synthesizer.synthesize(OWNER) == []

    def test_the_answer_is_capped_and_empty_directives_dropped(self):
        answer = '[{"directive": ""}, ' + ", ".join(f'{{"directive": "D{i}"}}' for i in range(5)) + "]"

        assert [p["content"] for p in _parse(answer)] == ["D0", "D1", "D2"]


class TestProposals:
    async def test_listing_requires_authentication(self, db, api):
        assert api.get("/style-proposals").status_code in (401, 403)

    async def test_the_pending_proposals_of_the_owner_only_are_listed(self, db, api):
        mine = await style_proposal_repo.store(OWNER, "Sois brève", "Trois pouces bas")
        await style_proposal_repo.store(OTHER, "Pas à moi")

        response = api.get("/style-proposals", headers=_as(OWNER))

        assert response.status_code == 200
        assert [(p["id"], p["content"], p["reason"], p["status"]) for p in response.json()] == [
            (mine.id, "Sois brève", "Trois pouces bas", "pending")
        ]

    async def test_accepting_writes_a_behaviour_directive(self, db, api):
        proposal = await style_proposal_repo.store(OWNER, "Sois brève")

        response = api.post(f"/style-proposals/{proposal.id}/accept", headers=_as(OWNER))

        assert response.status_code == 200
        assert response.json()["proposal"]["status"] == "accepted"
        [directive] = await _rows(db, Instruction, user_id=OWNER)
        assert (directive.content, directive.kind) == ("Sois brève", InstructionKind.BEHAVIOR)
        [stored] = await _rows(db, StyleProposal, id=proposal.id)
        assert stored.status == StyleProposalStatus.ACCEPTED
        assert stored.decided_at is not None

    async def test_the_owner_can_reword_it_before_accepting(self, db, api):
        proposal = await style_proposal_repo.store(OWNER, "Sois brève")

        api.post(f"/style-proposals/{proposal.id}/accept", json={"content": "Deux phrases max"}, headers=_as(OWNER))

        [directive] = await _rows(db, Instruction, user_id=OWNER)
        assert directive.content == "Deux phrases max"

    async def test_an_empty_rewording_is_refused(self, db, api):
        proposal = await style_proposal_repo.store(OWNER, "Sois brève")

        response = api.post(f"/style-proposals/{proposal.id}/accept", json={"content": ""}, headers=_as(OWNER))

        assert response.status_code == 422
        assert await _rows(db, Instruction, user_id=OWNER) == []

    async def test_refusing_writes_nothing(self, db, api):
        proposal = await style_proposal_repo.store(OWNER, "Sois brève")

        response = api.post(f"/style-proposals/{proposal.id}/reject", headers=_as(OWNER))

        assert response.status_code == 200
        assert response.json()["status"] == "rejected"
        assert await _rows(db, Instruction, user_id=OWNER) == []
        assert api.get("/style-proposals", headers=_as(OWNER)).json() == []

    async def test_a_decided_proposal_cannot_be_decided_again(self, db, api):
        proposal = await style_proposal_repo.store(OWNER, "Sois brève")
        api.post(f"/style-proposals/{proposal.id}/reject", headers=_as(OWNER))

        assert api.post(f"/style-proposals/{proposal.id}/accept", headers=_as(OWNER)).status_code == 409
        assert await _rows(db, Instruction, user_id=OWNER) == []

    async def test_another_users_proposal_is_a_404(self, db, api):
        proposal = await style_proposal_repo.store(OTHER, "Sois brève")

        assert api.post(f"/style-proposals/{proposal.id}/accept", headers=_as(OWNER)).status_code == 404
        assert api.post(f"/style-proposals/{proposal.id}/reject", headers=_as(OWNER)).status_code == 404
        [stored] = await _rows(db, StyleProposal, id=proposal.id)
        assert stored.status == StyleProposalStatus.PENDING

    async def test_generating_now_reads_even_a_single_thumb(self, db, api):
        _, answer = await _exchange(OWNER, "Q", "R")
        await message_repo.set_feedback(answer.id, OWNER, "down", "Trop long")
        model = _model_answering('[{"directive": "Sois brève", "raison": "Trop long"}]')

        with patch.object(style_synthesis.style_synthesizer, "client", model):
            response = api.post("/style-proposals/generate", headers=_as(OWNER))

        assert response.status_code == 200
        assert [p["content"] for p in response.json()] == ["Sois brève"]
        assert len(await _rows(db, StyleProposal, user_id=OWNER, status=StyleProposalStatus.PENDING)) == 1


class TestDailyPass:
    async def test_every_user_with_thumbs_is_read(self, db):
        from app.queue import scheduler

        for user_id in (OWNER, OTHER):
            _, answer = await _exchange(user_id, "Q", "R")
            await message_repo.set_feedback(answer.id, user_id, "up")

        synthesize = AsyncMock(return_value=[object()])
        with patch.object(scheduler.style_synthesizer, "synthesize", synthesize):
            assert await scheduler.run_style_synthesis() == 2

        assert sorted(call.args[0] for call in synthesize.await_args_list) == [OTHER, OWNER]


    async def test_the_loop_runs_at_boot_then_once_a_day(self, monkeypatch):
        import asyncio

        from app.config import settings
        from app.queue import scheduler

        passes = AsyncMock(return_value=0)
        monkeypatch.setattr(scheduler, "run_style_synthesis", passes)
        sleeps = []

        async def sleep(seconds):
            sleeps.append(seconds)
            if len(sleeps) == 2:
                raise asyncio.CancelledError

        monkeypatch.setattr(scheduler.asyncio, "sleep", sleep)

        with pytest.raises(asyncio.CancelledError):
            await scheduler._style_synthesis_loop()

        assert passes.await_count == 2
        assert sleeps == [settings.style_synthesis_interval_hours * 3600] * 2
