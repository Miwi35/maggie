import asyncio
from dataclasses import replace
from datetime import UTC, datetime
from types import SimpleNamespace
from zoneinfo import ZoneInfo

import pytest

from app.memory import conversations, paths
from tests.memory.conftest import USER_A, USER_B

PARIS = ZoneInfo("Europe/Paris")
ANECDOTE = "Françoise n'aime pas les roses, ça lui rappelle l'enterrement de sa mère. Les concerts commencent à 19 heures."


def message(ident: str, role: str, content: str, *, hour: int, minute: int = 0, **extra):
    return SimpleNamespace(
        id=ident,
        role=role,
        content=content,
        created_at=datetime(2026, 10, 4, hour, minute, tzinfo=UTC),
        blocks=extra.pop("blocks", None),
        interrupted=extra.pop("interrupted", False),
        **extra,
    )


@pytest.fixture(autouse=True)
def _clean_caches():
    conversations._files.clear()
    conversations._locks.clear()
    yield
    conversations._files.clear()
    conversations._locks.clear()


class TestFileOfAThread:
    async def test_the_exchange_is_written_whole_with_the_minute_of_each_message(self, env):
        question = message("m1", "user", ANECDOTE, hour=16, minute=14)
        answer = message("m2", "assistant", "Je comprends, je m'en souviendrai.", hour=16, minute=15)

        rel = await conversations.append_messages(USER_A, "ctx1", "Cadeau pour Françoise", [answer, question], tz=PARIS, service=env.service)

        assert rel == "Conversations/2026/10/2026-10-04 18h14 — Cadeau pour Françoise.md"
        text = env.local(rel)
        assert ANECDOTE in text
        assert "## 2026-10-04 18h14 — Propriétaire" in text
        assert "## 2026-10-04 18h15 — Maggie" in text
        assert text.index("Propriétaire") < text.index("Maggie")
        assert "contexte: ctx1" in text

    async def test_a_later_message_is_added_at_the_end_and_the_old_text_does_not_move(self, env):
        first = message("m1", "user", "Bonjour", hour=8)
        rel = await conversations.append_messages(USER_A, "ctx1", "Matin", [first], tz=PARIS, service=env.service)
        before = env.local(rel)

        await conversations.append_messages(
            USER_A, "ctx1", "Matin", [first, message("m2", "assistant", "Bonjour !", hour=8, minute=1)], tz=PARIS, service=env.service
        )

        after = env.local(rel)
        assert after.startswith(before)
        assert after.count("<!-- msg: m1 -->") == 1
        assert "Bonjour !" in after

    async def test_the_same_messages_twice_write_nothing_more(self, env):
        messages = [message("m1", "user", "Bonjour", hour=8), message("m2", "assistant", "Salut", hour=8, minute=1)]
        rel = await conversations.append_messages(USER_A, "ctx1", "Matin", messages, tz=PARIS, service=env.service)
        before = env.local(rel)

        again = await conversations.append_messages(USER_A, "ctx1", "Matin", messages, tz=PARIS, service=env.service)

        assert again == rel
        assert env.local(rel) == before

    async def test_a_file_is_found_again_from_its_head_when_the_cache_is_lost(self, env):
        first = message("m1", "user", "Bonjour", hour=8)
        rel = await conversations.append_messages(USER_A, "ctx1", "Matin", [first], tz=PARIS, service=env.service)
        conversations._files.clear()

        await conversations.append_messages(
            USER_A, "ctx1", "Matin", [first, message("m2", "assistant", "Salut", hour=9)], tz=PARIS, service=env.service
        )

        files = [f for f in env.service.folder(USER_A).files() if f.startswith("Conversations/")]
        assert files == [rel]

    async def test_two_threads_with_the_same_subject_and_minute_get_two_files(self, env):
        one = message("m1", "user", "Un", hour=8)
        two = message("m2", "user", "Deux", hour=8)

        a = await conversations.append_messages(USER_A, "ctx1", "Sujet", [one], tz=PARIS, service=env.service)
        b = await conversations.append_messages(USER_A, "ctx2", "Sujet", [two], tz=PARIS, service=env.service)

        assert a != b

    async def test_the_tools_called_and_a_cut_answer_are_said(self, env):
        blocks = [{"role": "assistant", "content": [{"type": "tool_use", "name": "memory_write_fiche", "input": {}}]}]
        answer = message("m2", "assistant", "Début de répon", hour=8, blocks=blocks, interrupted=True)

        rel = await conversations.append_messages(USER_A, "ctx1", "Matin", [answer], tz=PARIS, service=env.service)

        text = env.local(rel)
        assert "_Outils appelés : memory_write_fiche_" in text
        assert "Réponse coupée" in text

    async def test_a_conversation_goes_to_the_journal_of_no_one_and_does_not_grow_it(self, env):
        await conversations.append_messages(USER_A, "ctx1", "Matin", [message("m1", "user", "Bonjour", hour=8)], tz=PARIS, service=env.service)

        journal = env.local(paths.JOURNAL)
        assert journal is None or "Conversations/" not in journal


class TestNeverRewritten:
    async def test_the_service_refuses_a_text_that_does_not_start_with_the_old_one(self, env):
        rel = await conversations.append_messages(USER_A, "ctx1", "Matin", [message("m1", "user", "Bonjour", hour=8)], tz=PARIS, service=env.service)

        with pytest.raises(paths.UnsafePath):
            await env.service.write(USER_A, rel, "Résumé de la conversation.\n", conversation=True)

        assert "Bonjour" in env.local(rel)

    async def test_the_service_refuses_to_cut_the_end_of_a_conversation(self, env):
        rel = await conversations.append_messages(USER_A, "ctx1", "Matin", [message("m1", "user", "Bonjour", hour=8)], tz=PARIS, service=env.service)
        whole = env.local(rel)

        with pytest.raises(paths.UnsafePath):
            await env.service.write(USER_A, rel, whole[:-30], conversation=True)

    async def test_the_service_lets_a_conversation_grow(self, env):
        rel = await conversations.append_messages(USER_A, "ctx1", "Matin", [message("m1", "user", "Bonjour", hour=8)], tz=PARIS, service=env.service)

        await env.service.write(USER_A, rel, env.local(rel) + "\n## suite\n", conversation=True, journal=False)

        assert env.local(rel).endswith("## suite\n")


class TestTwoOwners:
    async def test_each_owner_has_a_file_of_their_own(self, env):
        await conversations.append_messages(USER_A, "ctx1", "Sujet", [message("m1", "user", "Secret de A", hour=8)], tz=PARIS, service=env.service)
        await conversations.append_messages(USER_B, "ctx1", "Sujet", [message("m2", "user", "Secret de B", hour=8)], tz=PARIS, service=env.service)

        a = [f for f in env.service.folder(USER_A).files() if f.startswith("Conversations/")]
        b = [f for f in env.service.folder(USER_B).files() if f.startswith("Conversations/")]
        assert "Secret de B" not in env.local(a[0], USER_A)
        assert "Secret de A" not in env.local(b[0], USER_B)
        assert env.remote(a[0], USER_B) is None


class TestRecord:
    async def test_a_question_is_ignored_until_its_answer_is_stored(self, env):
        from unittest.mock import patch

        question = message("m1", "user", ANECDOTE, hour=16, minute=14, user_id=USER_A, context_id=None)

        with patch.object(conversations, "memory_service", env.service):
            await conversations.record(question)

        assert not [f for f in env.service.folder(USER_A).files() if f.startswith("Conversations/")]

    async def test_a_service_that_is_off_writes_nothing(self, env):
        from unittest.mock import patch

        answer = message("m2", "assistant", "Oui", hour=16, user_id=USER_A, context_id=None)
        env.service.bucket = None

        with patch.object(conversations, "memory_service", env.service):
            await conversations.record(answer)

        assert not [f for f in env.service.folder(USER_A).files() if f.startswith("Conversations/")]

    async def test_a_user_who_is_not_a_ulid_writes_nothing(self, env):
        from unittest.mock import patch

        answer = message("m2", "assistant", "Oui", hour=16, user_id="../autre", context_id=None)

        with patch.object(conversations, "memory_service", env.service):
            await conversations.record(answer)

        assert not [f for f in env.service.folder(USER_A).files() if f.startswith("Conversations/")]

    async def test_an_error_is_logged_and_never_breaks_the_answer(self, env, caplog):
        from unittest.mock import AsyncMock, patch

        answer = message("m2", "assistant", "Oui", hour=16, user_id=USER_A, context_id=None)

        with (
            patch.object(conversations, "memory_service", env.service),
            patch.object(conversations, "_record", AsyncMock(side_effect=RuntimeError("boom"))),
        ):
            await conversations.record(answer)

        assert "Could not write the conversation" in caplog.text

    async def test_an_answer_brings_the_whole_thread_into_the_file_without_any_tool_call(self, env):
        from unittest.mock import AsyncMock, patch

        question = message("m1", "user", ANECDOTE, hour=16, minute=14, user_id=USER_A, context_id="ctx1")
        answer = message("m2", "assistant", "Noté.", hour=16, minute=15, user_id=USER_A, context_id="ctx1")

        with (
            patch.object(conversations, "memory_service", env.service),
            patch("app.db.message_repository.message_repo.find_by_context", AsyncMock(return_value=[question])),
            patch("app.db.context_repository.context_repo.get", AsyncMock(return_value=SimpleNamespace(label="Cadeau pour Françoise"))),
            patch("app.user_timezone.resolve_user_timezone", AsyncMock(return_value=PARIS)),
        ):
            await conversations.record(answer)

        files = [f for f in env.service.folder(USER_A).files() if f.startswith("Conversations/")]
        assert files == ["Conversations/2026/10/2026-10-04 18h14 — Cadeau pour Françoise.md"]
        text = env.local(files[0])
        assert "Les concerts commencent à 19 heures" in text and "Noté." in text


class TestCarteAndMessagesTogether:
    async def test_a_message_arriving_while_the_carte_is_rewritten_is_not_lost(self, env):
        first = message("m1", "user", ANECDOTE, hour=16, minute=14)
        rel = await conversations.append_messages(USER_A, "ctx1", "Cadeau", [first], tz=PARIS, service=env.service)

        for n in range(5):
            late = message(f"late{n}", "user", f"Encore {n}", hour=17, minute=n)
            await asyncio.gather(
                conversations.update_carte(
                    USER_A, "ctx1", lambda c, n=n: replace(c, resume=f"Résumé {n}"), reason="test", service=env.service
                ),
                conversations.append_messages(USER_A, "ctx1", "Cadeau", [first, late], tz=PARIS, service=env.service),
            )

        text = env.local(rel)
        assert all(f"Encore {n}" in text for n in range(5))
        assert text.count(ANECDOTE) == 1
        assert "resume: Résumé 4" in text
