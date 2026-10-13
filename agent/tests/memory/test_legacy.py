from datetime import UTC, datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch
from zoneinfo import ZoneInfo

import pytest

from app.memory import carte as carte_mod
from app.memory import conversations, legacy
from app.memory.fiches import parse_body
from tests.memory.conftest import USER_A

PARIS = ZoneInfo("Europe/Paris")


def msg(ident, role, content, day, hour=9, context_id=None):
    return SimpleNamespace(
        id=ident, role=role, content=content, context_id=context_id, blocks=None, interrupted=False,
        created_at=datetime(2026, 9, day, hour, 0, tzinfo=UTC),
    )


def row(content, created=datetime(2026, 9, 1, 12, 0, tzinfo=UTC)):
    return SimpleNamespace(content=content, created_at=created, type="factual")


@pytest.fixture()
def old(env):
    conversations._files.clear()
    conversations._locks.clear()
    thread = [msg("m1", "user", "Je cherche un cadeau", 3, context_id="ctx1"), msg("m2", "assistant", "Quel budget ?", 3, 10, "ctx1")]
    loose = [msg("m3", "user", "Les concerts commencent à 19 heures", 5), msg("m4", "assistant", "Noté", 5, 10)]
    with (
        patch.object(legacy, "memory_service", env.service),
        patch.object(legacy, "resolve_user_timezone", AsyncMock(return_value=PARIS)),
        patch.object(legacy.context_repo, "find_by_user", AsyncMock(return_value=[SimpleNamespace(id="ctx1", label="Cadeau")])),
        patch.object(legacy.message_repo, "find_by_context", AsyncMock(return_value=thread)),
        patch.object(legacy.message_repo, "find_without_context", AsyncMock(return_value=loose)),
        patch.object(legacy.memory_repo, "find_by_user", AsyncMock(return_value=[row("Allergique aux noix. Préfère le thé au café.")])),
    ):
        yield
    conversations._files.clear()
    conversations._locks.clear()
    legacy._done.clear()


async def test_the_history_becomes_whole_conversations_one_per_thread_and_one_per_day(env, old):
    report = await legacy.import_user(USER_A, env.service)

    files = [f for f in env.service.folder(USER_A).files() if f.startswith("Conversations/")]
    assert report.conversations == 2 and len(files) == 2
    assert any("Cadeau" in f for f in files) and any(conversations.NO_THREAD_SUBJECT in f for f in files)
    assert "Les concerts commencent à 19 heures" in env.local(next(f for f in files if "Hors" in f))


async def test_an_old_memory_row_becomes_a_fiche_with_no_invented_source(env, old):
    await legacy.import_user(USER_A, env.service)

    chemin = f"{legacy.LEGACY_FOLDER}/Allergique aux noix.md"
    text = env.local(chemin)
    claim = parse_body(carte_mod.parse(text).body).known[0]
    assert claim.provenance == "déduit"
    assert claim.source == "ancienne mémoire (sans source), enregistrée le 01/09/2026"
    assert "Conversations/" not in claim.source
    assert "Préfère le thé au café." in claim.texte


async def test_running_it_twice_changes_nothing(env, old):
    await legacy.import_user(USER_A, env.service)
    files = sorted(env.service.folder(USER_A).files())

    again = await legacy.import_user(USER_A, env.service)

    assert (again.conversations, again.fiches) == (0, 0)
    assert sorted(env.service.folder(USER_A).files()) == files


async def test_a_fiche_the_owner_deleted_does_not_come_back(env, old):
    await legacy.import_user(USER_A, env.service)
    chemin = f"{legacy.LEGACY_FOLDER}/Allergique aux noix.md"
    await env.service.delete(USER_A, chemin)

    await legacy.import_user(USER_A, env.service)

    assert env.local(chemin) is None


async def test_a_thread_already_on_file_is_left_alone(env, old):
    first = await conversations.append_messages(
        USER_A, "ctx1", "Cadeau", [msg("m1", "user", "Je cherche un cadeau", 3)], tz=PARIS, service=env.service
    )
    before = env.local(first)

    await legacy.import_user(USER_A, env.service)

    assert env.local(first) == before


async def test_a_row_with_no_text_is_ignored(env, old):
    with patch.object(legacy.memory_repo, "find_by_user", AsyncMock(return_value=[row("  ")])):
        report = await legacy.import_user(USER_A, env.service)

    assert report.fiches == 0


async def test_import_once_never_breaks_a_turn(env, old):
    with patch.object(legacy, "import_user", AsyncMock(side_effect=RuntimeError("boom"))):
        await legacy.import_once(USER_A)

    assert USER_A not in legacy._done
