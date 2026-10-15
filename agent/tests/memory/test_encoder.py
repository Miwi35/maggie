"""Encoding along the way (MAG-15): the carte and the obvious fiches of a thread that went quiet."""

import json
import uuid
from pathlib import Path
from datetime import UTC, datetime, timedelta
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.db.context_model import ConversationContext
from app.db.context_repository import context_repo
from app.db.models import Message
from app.llm.fake import FakeAnthropicClient
from app.memory import carte as carte_mod
from app.memory import conversations, encoder
from app.memory.encoder import PassageEncoder, parse_proposal
from app.queue import scheduler
from tests.memory.conftest import USER_A, USER_B

SHIPPED = Path(__file__).resolve().parents[2] / "fixtures" / "fake-llm"
PARIS = ZoneInfo("Europe/Paris")
NOW = datetime(2026, 10, 14, 10, 30, tzinfo=UTC)
AFTER = timedelta(seconds=300)
SAID = "Ma nièce Zoé est végétarienne, pense-y pour les repas de famille."
CLAIM = "Zoé, la nièce du propriétaire, est végétarienne"
MINUTE = "2026-10-14 12h00"  # 10:00 UTC, in Paris


def proposal(**over) -> dict:
    base = {
        "carte": {
            "resume": "Zoé est végétarienne.",
            "moments": [{"heure": MINUTE, "texte": "Le propriétaire dit que Zoé est végétarienne"}],
            "personnes": ["Zoé"],
            "evoque": ["repas de famille"],
        },
        "fiches": [
            {
                "chemin": None,
                "titre": CLAIM,
                "dossiers": ["Famille"],
                "resume": "Le régime de Zoé",
                "personnes": ["Zoé"],
                "evoque": ["repas de famille"],
                "alias": [],
                "ajouts": [{"texte": CLAIM, "heure": MINUTE}],
                "doute": None,
            }
        ],
        "doute": None,
    }
    base.update(over)
    return base


class ScriptedModel:
    """The fast model, answering what the test scripts, and remembering what it was asked."""

    def __init__(self, *answers: str | dict | Exception):
        self.answers = list(answers)
        self.requests: list[dict] = []
        self.messages = SimpleNamespace(create=self._create)

    async def _create(self, **kwargs):
        self.requests.append(kwargs)
        answer = self.answers.pop(0) if len(self.answers) > 1 else self.answers[0]
        if isinstance(answer, Exception):
            raise answer
        text = answer if isinstance(answer, str) else json.dumps(answer)
        return SimpleNamespace(
            content=[SimpleNamespace(type="text", text=text)],
            usage=SimpleNamespace(input_tokens=100, output_tokens=50),
        )

    @property
    def calls(self) -> int:
        return len(self.requests)


@pytest.fixture(autouse=True)
def usage(chat_db, env):
    conversations._files.clear()
    conversations._locks.clear()
    paris = AsyncMock(return_value=PARIS)
    with (
        patch("app.user_timezone.resolve_user_timezone", paris),
        patch("app.memory.encoder.resolve_user_timezone", paris),
        patch("app.memory.encoder.record_llm_usage") as usage,
        patch.object(conversations, "memory_service", env.service),
    ):
        yield usage
    conversations._files.clear()
    conversations._locks.clear()


def encoder_with(env, model: ScriptedModel) -> PassageEncoder:
    result = PassageEncoder(service=env.service)
    result.client = model
    return result


def add_thread(chat_db, messages: list[tuple[str, str, datetime]], *, label: str = "Famille", user_id: str = USER_A):
    """A context and its messages at the instants given. Returns the context id."""
    context_id = uuid.uuid4().hex
    session = chat_db.session()._session
    session.add(ConversationContext(id=context_id, user_id=user_id, label=label, updated_at=messages[-1][2]))
    for index, (role, content, when) in enumerate(messages):
        session.add(Message(id=f"{context_id[:20]}{index:06d}", user_id=user_id, role=role, content=content, context_id=context_id, created_at=when))
    session.commit()
    return context_id


def speak(chat_db, context_id: str, content: str, when: datetime, **extra) -> None:
    """A new message in the thread, as the router touching it would leave it."""
    session = chat_db.session()._session
    session.add(Message(id=uuid.uuid4().hex[:26], user_id=USER_A, role="user", content=content, context_id=context_id, created_at=when, **extra))
    session.get(ConversationContext, context_id).updated_at = when
    session.commit()


def exchange(chat_db, *, when: datetime = NOW - timedelta(minutes=30), said: str = SAID, **kwargs) -> str:
    return add_thread(
        chat_db,
        [("user", said, when), ("assistant", "D'accord, j'y penserai.", when + timedelta(seconds=20))],
        **kwargs,
    )


async def context(context_id: str):
    return await context_repo.get(context_id)


async def run(model: ScriptedModel, env, context_id: str, now: datetime = NOW):
    with patch("app.queue.scheduler.memory_encoder", encoder_with(env, model)):
        return await scheduler.run_memory_encoding(now)


def conversation_text(env, context_id: str) -> str:
    rel = conversations.find_file(env.service, USER_A, context_id)
    assert rel is not None, "the conversation has no file"
    return env.local(rel)


def journal(env) -> str:
    return env.local("journal.md") or ""


def fiche_files(env) -> list[str]:
    return [f for f in env.service.folder(USER_A).files() if f.startswith("Famille/")]


@pytest.mark.parametrize(
    "raw",
    ['{"carte": {}}', '```json\n{"carte": {}}\n```', 'Voici : {"carte": {}} voilà'],
)
def test_the_proposal_is_read_however_the_model_fenced_it(raw):
    assert parse_proposal(raw) == {"carte": {}}


@pytest.mark.parametrize("raw", ["", "pas de JSON", "{cassé", "[1, 2]"])
def test_an_unreadable_answer_is_refused(raw):
    with pytest.raises(encoder.ProposalError):
        parse_proposal(raw)


class TestTheCarteOfThePassage:
    async def test_the_carte_is_written_on_top_of_the_conversation_and_the_messages_do_not_move(self, env, chat_db):
        context_id = exchange(chat_db)
        model = ScriptedModel(proposal(fiches=[]))

        assert await run(model, env, context_id) == 1

        text = conversation_text(env, context_id)
        head, body = carte_mod.split(text)
        parsed = carte_mod.parse(text).carte
        assert parsed.resume == "Zoé est végétarienne."
        assert parsed.moments == [f"{MINUTE} — Le propriétaire dit que Zoé est végétarienne"]
        assert parsed.personnes == ["Zoé"]
        assert parsed.evoque == ["repas de famille"]
        assert "contexte:" in text[:600], "the thread's file is still found from its head"
        assert SAID in body
        assert body.count("## 2026-10-14 12h00 — Propriétaire") == 1

    async def test_the_journal_names_this_pass_for_the_carte(self, env, chat_db):
        context_id = exchange(chat_db)

        await run(ScriptedModel(proposal(fiches=[])), env, context_id)

        assert "modifié" in journal(env)
        assert "encodage au fil de l'eau, fil « Famille » : carte du passage" in journal(env)

    async def test_a_moment_with_no_message_at_that_minute_is_not_kept(self, env, chat_db):
        context_id = exchange(chat_db)
        invented = proposal(carte={"resume": "Zoé.", "moments": [{"heure": "2026-10-14 03h33", "texte": "Rien ne s'est dit"}]})

        await run(ScriptedModel(invented), env, context_id)

        assert carte_mod.parse(conversation_text(env, context_id)).carte.moments == []

    async def test_a_second_pass_on_the_same_messages_writes_nothing_more(self, env, chat_db):
        context_id = exchange(chat_db)
        model = ScriptedModel(proposal())
        await run(model, env, context_id)
        after_first = conversation_text(env, context_id)
        lines = journal(env).count("\n")

        assert await run(model, env, context_id, NOW + timedelta(minutes=10)) == 0

        assert model.calls == 1
        assert conversation_text(env, context_id) == after_first
        assert journal(env).count("\n") == lines

    async def test_the_same_proposal_on_a_resumed_thread_does_not_double_the_carte(self, env, chat_db):
        context_id = exchange(chat_db)
        model = ScriptedModel(proposal())
        await run(model, env, context_id)
        # The thread is resumed (a new exchange), then goes quiet again.
        later = NOW + timedelta(minutes=5)
        speak(chat_db, context_id, "Merci", later)

        assert await run(model, env, context_id, later + AFTER + timedelta(minutes=1)) == 1

        text = conversation_text(env, context_id)
        carte = carte_mod.parse(text).carte
        assert len(carte.moments) == 1
        assert carte.personnes == ["Zoé"]
        assert text.count("contexte:") == 1
        assert len(fiche_files(env)) == 1

    async def test_a_carte_the_owner_wrote_by_hand_is_enriched_not_replaced(self, env, chat_db):
        context_id = exchange(chat_db)
        await conversations.record_thread(USER_A, context_id, service=env.service)
        await conversations.update_carte(
            USER_A,
            context_id,
            lambda carte: carte_mod.Carte(id=carte.id, resume="À moi.", personnes=["Léa"], evoque=["fêtes"], extra=carte.extra),
            reason="le propriétaire",
            service=env.service,
        )

        await run(ScriptedModel(proposal(fiches=[], carte={"resume": "Zoé aussi.", "personnes": ["Zoé", "léa"], "evoque": ["Fêtes", "repas de famille"]})), env, context_id)

        carte = carte_mod.parse(conversation_text(env, context_id)).carte
        assert carte.personnes == ["Léa", "Zoé"]
        assert carte.evoque == ["fêtes", "repas de famille"]


class TestTheObviousFiches:
    async def test_the_fiche_is_written_with_the_owner_saying_and_the_minute_of_the_message(self, env, chat_db):
        context_id = exchange(chat_db)

        await run(ScriptedModel(proposal()), env, context_id)

        assert fiche_files(env) == [f"Famille/{CLAIM}.md"]
        text = env.local(f"Famille/{CLAIM}.md")
        conversation = conversations.find_file(env.service, USER_A, context_id)
        assert f"- {CLAIM} — dit · {conversation} · {MINUTE}" in text
        assert f"créé `Famille/{CLAIM}.md` — encodage au fil de l'eau, fil « Famille »" in journal(env)

    async def test_a_claim_on_a_message_the_owner_did_not_write_is_not_written(self, env, chat_db):
        context_id = exchange(chat_db)
        wrong = proposal()
        wrong["fiches"][0]["ajouts"] = [{"texte": CLAIM, "heure": "2026-10-14 03h33"}]

        await run(ScriptedModel(wrong), env, context_id)

        assert fiche_files(env) == []

    async def test_a_claim_is_added_to_the_fiche_that_already_holds_the_subject(self, env, chat_db):
        first = exchange(chat_db)
        await run(ScriptedModel(proposal()), env, first)
        path = f"Famille/{CLAIM}.md"
        other = exchange(chat_db, when=NOW - timedelta(minutes=20), said="Zoé ne mange pas de poisson non plus.", label="Famille 2")
        more = proposal(carte={"resume": "Zoé."})
        more["fiches"][0].update(chemin=path, ajouts=[{"texte": "Zoé ne mange pas de poisson", "heure": "2026-10-14 12h10"}])

        await run(ScriptedModel(more), env, other)

        text = env.local(path)
        assert f"- {CLAIM} — dit" in text, "what was known stays"
        assert "- Zoé ne mange pas de poisson — dit" in text
        assert fiche_files(env) == [path]

    async def test_a_claim_already_in_the_fiche_is_not_added_twice(self, env, chat_db):
        first = exchange(chat_db)
        await run(ScriptedModel(proposal()), env, first)
        other = exchange(chat_db, when=NOW - timedelta(minutes=20), said=SAID, label="Encore")
        again = proposal()
        again["fiches"][0]["chemin"] = f"Famille/{CLAIM}.md"
        again["fiches"][0]["ajouts"] = [{"texte": CLAIM, "heure": "2026-10-14 12h10"}]
        before = env.local(f"Famille/{CLAIM}.md")

        await run(ScriptedModel(again), env, other)

        assert env.local(f"Famille/{CLAIM}.md") == before

    async def test_a_proposal_on_a_fiche_that_does_not_exist_is_left_to_the_night(self, env, chat_db):
        context_id = exchange(chat_db)
        ghost = proposal()
        ghost["fiches"][0]["chemin"] = "Famille/Inconnue.md"

        await run(ScriptedModel(ghost), env, context_id)

        assert fiche_files(env) == []
        assert "laissée à la nuit" in journal(env)

    async def test_the_claim_the_owner_once_retired_is_not_brought_back(self, env, chat_db):
        context_id = exchange(chat_db)
        path = f"Famille/{CLAIM}.md"
        retired = (
            "---\nresume: Zoé\n---\n\n## Ce qui n'est plus vrai\n\n"
            f"- ~~{CLAIM}~~ — dit · Conversations/x.md · 2026-09-01 10h00 — remplacé le 2026-10-01 : mange de la viande\n"
        )
        await env.service.write(USER_A, path, retired, reason="test")
        stamped = env.local(path)
        again = proposal()
        again["fiches"][0]["chemin"] = path

        await run(ScriptedModel(again), env, context_id)

        assert env.local(path) == stamped
        assert "laissée à la nuit" in journal(env)

    async def test_a_doubt_on_a_fiche_writes_the_carte_and_leaves_the_fiche_to_the_night(self, env, chat_db):
        context_id = exchange(chat_db)
        doubtful = proposal()
        doubtful["fiches"][0]["doute"] = "contredit la fiche sur le régime de Zoé"

        await run(ScriptedModel(doubtful), env, context_id)

        assert fiche_files(env) == []
        assert carte_mod.parse(conversation_text(env, context_id)).carte.resume == "Zoé est végétarienne."
        assert "laissée à la nuit" in journal(env)
        assert "contredit la fiche sur le régime de Zoé" in journal(env)

    async def test_a_doubt_on_the_whole_passage_writes_the_carte_alone(self, env, chat_db):
        context_id = exchange(chat_db)

        await run(ScriptedModel(proposal(doute="le propriétaire se reprend plusieurs fois")), env, context_id)

        assert fiche_files(env) == []
        assert carte_mod.parse(conversation_text(env, context_id)).carte.resume == "Zoé est végétarienne."
        assert "carte seule" in journal(env)
        assert "laissées à la nuit" in journal(env)

    async def test_a_contradicting_fiche_is_never_touched(self, env, chat_db):
        context_id = exchange(chat_db, said="Finalement Zoé mange de la viande.")
        path = "Famille/Zoé est végétarienne.md"
        known = (
            "---\nresume: Régime de Zoé\n---\n\n## Ce qu'on sait\n\n"
            "- Zoé est végétarienne — dit · Conversations/x.md · 2026-09-01 10h00\n"
        )
        await env.service.write(USER_A, path, known, reason="test")
        stamped = env.local(path)
        contradiction = proposal(carte={"resume": "Zoé mange de la viande."})
        contradiction["fiches"][0].update(chemin=path, doute="contredit « Zoé est végétarienne »")
        model = ScriptedModel(contradiction)

        await run(model, env, context_id)

        assert env.local(path) == stamped
        assert "Zoé est végétarienne — dit" in model.requests[0]["messages"][0]["content"]


class TestNeverOnThePathOfATurn:
    async def test_a_thread_still_inside_the_delay_is_not_encoded(self, env, chat_db):
        context_id = exchange(chat_db, when=NOW - timedelta(minutes=2))
        model = ScriptedModel(proposal())

        assert await run(model, env, context_id) == 0

        assert model.calls == 0
        assert conversations.find_file(env.service, USER_A, context_id) is None

    async def test_a_thread_whose_answer_is_still_being_written_is_not_encoded(self, env, chat_db):
        context_id = exchange(chat_db)
        speak(
            chat_db, context_id, "Et aussi", NOW - timedelta(minutes=20),
            turn_status="running", turn_lease_until=NOW + timedelta(minutes=2),
        )
        model = ScriptedModel(proposal())

        assert await run(model, env, context_id) == 0
        assert model.calls == 0

    async def test_the_cost_is_counted_under_its_own_call_type(self, env, chat_db, usage):
        context_id = exchange(chat_db)

        await run(ScriptedModel(proposal()), env, context_id)

        usage.assert_called_once()
        assert usage.call_args.kwargs["call_type"] == "memory_encoding"
        assert usage.call_args.kwargs["input_tokens"] == 100

    async def test_a_thread_is_encoded_after_five_minutes_not_after_the_day_of_dormancy(self, env, chat_db):
        context_id = exchange(chat_db, when=NOW - timedelta(minutes=6))

        assert await run(ScriptedModel(proposal()), env, context_id) == 1

        assert (await context(context_id)).status.value == "active"


class TestWhenTheModelFails:
    async def test_an_error_leaves_the_thread_for_the_next_cycle(self, env, chat_db):
        context_id = exchange(chat_db)
        model = ScriptedModel(RuntimeError("overloaded"), proposal())
        encode = encoder_with(env, model)

        with patch("app.queue.scheduler.memory_encoder", encode):
            assert await scheduler.run_memory_encoding(NOW) == 0
            assert (await context(context_id)).encoded_up_to is None
            assert await scheduler.run_memory_encoding(NOW) == 1

        assert fiche_files(env) == [f"Famille/{CLAIM}.md"]

    async def test_an_answer_that_is_not_json_writes_nothing_and_gives_up_after_a_few_tries(self, env, chat_db):
        context_id = exchange(chat_db)
        model = ScriptedModel("[fake-llm] aucun scénario ne correspond")
        encode = encoder_with(env, model)

        with patch("app.queue.scheduler.memory_encoder", encode):
            for _ in range(encoder.MAX_ATTEMPTS + 2):
                assert await scheduler.run_memory_encoding(NOW) == 0

        assert model.calls == encoder.MAX_ATTEMPTS
        assert fiche_files(env) == []
        assert carte_mod.parse(conversation_text(env, context_id)).carte.resume == ""

    async def test_a_write_that_fails_is_counted_too_and_the_model_is_not_called_for_ever(self, env, chat_db):
        context_id = exchange(chat_db)
        model = ScriptedModel(proposal())
        encode = encoder_with(env, model)
        await conversations.record_thread(USER_A, context_id, service=env.service)

        with (
            patch("app.queue.scheduler.memory_encoder", encode),
            patch.object(env.service, "write", AsyncMock(side_effect=OSError("disk full"))),
        ):
            for _ in range(encoder.MAX_ATTEMPTS + 2):
                assert await scheduler.run_memory_encoding(NOW) == 0

        assert model.calls == encoder.MAX_ATTEMPTS
        assert (await context(context_id)).encoded_up_to is None

    async def test_a_failed_thread_is_tried_again_once_it_is_spoken_in(self, env, chat_db):
        context_id = exchange(chat_db)
        model = ScriptedModel("rien")
        encode = encoder_with(env, model)
        with patch("app.queue.scheduler.memory_encoder", encode):
            for _ in range(encoder.MAX_ATTEMPTS):
                await scheduler.run_memory_encoding(NOW)
            later = NOW + timedelta(minutes=5)
            speak(chat_db, context_id, "Encore", later)
            await scheduler.run_memory_encoding(later + AFTER + timedelta(minutes=1))

        assert model.calls == encoder.MAX_ATTEMPTS + 1

    async def test_threads_that_cannot_be_encoded_do_not_hide_the_ones_behind_them(self, env, chat_db):
        for n in range(25):
            exchange(chat_db, when=NOW - timedelta(minutes=120 - n), said="Rien d'encodable", user_id="not-a-ulid")
        context_id = exchange(chat_db, when=NOW - timedelta(minutes=30))

        await run(ScriptedModel(proposal()), env, context_id)

        assert (await context(context_id)).encoded_up_to is not None
        assert fiche_files(env) == [f"Famille/{CLAIM}.md"]

    async def test_a_memory_that_is_off_encodes_nothing(self, env, chat_db):
        context_id = exchange(chat_db)
        model = ScriptedModel(proposal())
        encode = encoder_with(env, model)

        with (
            patch.object(type(env.service), "enabled", new=False),
            patch("app.queue.scheduler.memory_encoder", encode),
        ):
            assert await scheduler.run_memory_encoding(NOW) == 0

        assert model.calls == 0
        assert (await context(context_id)).encoded_up_to is None


class TestTheWatermark:
    async def test_it_moves_to_the_last_message_read(self, env, chat_db):
        context_id = exchange(chat_db)

        await run(ScriptedModel(proposal()), env, context_id)

        done = await context(context_id)
        assert done.encoded_up_to is not None
        assert done.encoded_up_to.replace(tzinfo=UTC) == (NOW - timedelta(minutes=30) + timedelta(seconds=20))

    async def test_two_passes_cannot_both_move_it(self, chat_db):
        context_id = exchange(chat_db)
        first, second = NOW - timedelta(minutes=29), NOW - timedelta(minutes=28)

        assert await context_repo.set_encoded(context_id, first, None) is True
        assert await context_repo.set_encoded(context_id, second, None) is False
        assert await context_repo.set_encoded(context_id, second, first) is True

    async def test_a_thread_encoded_up_to_its_last_message_is_not_listed(self, chat_db):
        context_id = exchange(chat_db)
        silent = NOW - AFTER
        assert [str(c.id) for c in await context_repo.find_to_encode(silent, NOW)] == [context_id]

        await context_repo.set_encoded(context_id, NOW - timedelta(minutes=30) + timedelta(seconds=20), None)

        assert await context_repo.find_to_encode(silent, NOW) == []

    async def test_only_the_oldest_threads_are_listed_up_to_the_limit(self, chat_db):
        ids = [exchange(chat_db, when=NOW - timedelta(minutes=60 - n)) for n in range(3)]

        listed = await context_repo.find_to_encode(NOW - AFTER, NOW, limit=2)

        assert [str(c.id) for c in listed] == ids[:2]

    async def test_a_thread_with_no_message_is_not_listed(self, chat_db):
        await context_repo.create(USER_A, "Vide")

        assert await context_repo.find_to_encode(NOW + timedelta(days=1), NOW + timedelta(days=2)) == []


class TestTheShippedScenario:
    async def test_the_scenario_of_the_e2e_stack_writes_the_carte_and_the_fiche_through_the_real_checks(self, env, chat_db):
        context_id = exchange(chat_db)
        encode = PassageEncoder(service=env.service)
        encode.client = FakeAnthropicClient(fixtures_dir=SHIPPED)

        with patch("app.queue.scheduler.memory_encoder", encode):
            assert await scheduler.run_memory_encoding(NOW) == 1

        conversation = conversations.find_file(env.service, USER_A, context_id)
        assert f"- {CLAIM} — dit · {conversation} · {MINUTE}" in env.local(f"Famille/{CLAIM}.md")
        carte = carte_mod.parse(conversation_text(env, context_id)).carte
        assert carte.moments == [f"{MINUTE} — Le propriétaire dit que sa nièce Zoé est végétarienne"]
        assert "encodage au fil de l'eau" in journal(env)

    async def test_another_subject_matches_nothing_and_writes_nothing(self, env, chat_db):
        context_id = exchange(chat_db, said="Les concerts commencent à 19 heures.")
        encode = PassageEncoder(service=env.service)
        encode.client = FakeAnthropicClient(fixtures_dir=SHIPPED)

        with patch("app.queue.scheduler.memory_encoder", encode):
            assert await scheduler.run_memory_encoding(NOW) == 0

        assert fiche_files(env) == []
        assert carte_mod.parse(conversation_text(env, context_id)).carte.resume == ""


class TestOneUserOnly:
    async def test_the_listing_can_be_narrowed_to_one_user(self, chat_db):
        mine = exchange(chat_db)
        exchange(chat_db, user_id=USER_B)

        listed = await context_repo.find_to_encode(NOW - AFTER, NOW, user_id=USER_A)

        assert [str(c.id) for c in listed] == [mine]

    async def test_a_pass_narrowed_to_one_user_leaves_the_other_threads_alone(self, env, chat_db):
        mine = exchange(chat_db)
        theirs = exchange(chat_db, user_id=USER_B)

        with patch("app.queue.scheduler.memory_encoder", encoder_with(env, ScriptedModel(proposal()))):
            assert await scheduler.run_memory_encoding(NOW, user_id=USER_A) == 1

        assert (await context(mine)).encoded_up_to is not None
        assert (await context(theirs)).encoded_up_to is None


class TestTheE2eRoute:
    """The journey cannot wait for the clock: the stack's one is nearly frozen (MAG-234), so the route runs the pass at a later instant."""

    async def test_it_encodes_the_threads_of_the_user_that_just_went_quiet_in_the_stack_clock(self, env, chat_db):
        from app import e2e

        context_id = exchange(chat_db, when=datetime.now(UTC) - timedelta(seconds=30))
        encode = PassageEncoder(service=env.service)
        encode.client = FakeAnthropicClient(fixtures_dir=SHIPPED)

        with patch("app.queue.scheduler.memory_encoder", encode):
            assert await e2e.memory_encode(USER_A) == {"encoded": 1}
            assert await e2e.memory_encode(USER_A) == {"encoded": 0}

        assert fiche_files(env) == [f"Famille/{CLAIM}.md"]
        assert "encodage au fil de l'eau" in journal(env)
        assert (await context(context_id)).encoded_up_to is not None

    def test_it_needs_the_token(self, monkeypatch):
        from fastapi import FastAPI
        from fastapi.testclient import TestClient

        from app.e2e import setup_e2e

        monkeypatch.setattr("app.e2e.settings.tts_provider", "fake")
        monkeypatch.setattr("app.e2e.settings.e2e_login_token", "token-for-the-test")
        app = FastAPI()
        setup_e2e(app)

        with TestClient(app) as client:
            assert client.post(f"/e2e/memory/{USER_A}/encode").status_code == 401
