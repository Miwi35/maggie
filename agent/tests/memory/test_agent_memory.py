from datetime import timedelta
from unittest.mock import AsyncMock, patch

import pytest

from types import SimpleNamespace

from app.memory import agent_memory
from app.memory.agent_memory import AgentMemory
from app.memory.frontmatter import NoteDoc
from app.memory.service import MemoryService
from app.memory.sync import TurnState
from tests.memory.conftest import RacyBucket

FACT = SimpleNamespace(content="Aime le café")
FACTS_BLOCK = "\n\nCe que tu sais sur l'utilisateur :\n- Aime le café"


@pytest.fixture()
def facts():
    with patch.object(agent_memory.memory_repo, "find_by_user", AsyncMock(return_value=[FACT])) as find:
        yield find


@pytest.fixture()
def serve(world):
    """AgentMemory reading the world's service."""

    def attach(world_=world) -> AgentMemory:
        service = MemoryService(world_.bucket, world_.store, world_.reconciler, world_.summary, world_.sync)
        patcher = patch("app.memory.service.memory_service", service)
        patcher.start()
        serve.patchers.append(patcher)
        return AgentMemory()

    serve.patchers = []
    yield attach
    for patcher in serve.patchers:
        patcher.stop()


class TestWithoutService:
    async def test_only_the_facts_as_before(self, facts):
        with patch("app.memory.service.memory_service", None):
            assert await AgentMemory().get_memory_context("u") == FACTS_BLOCK

    async def test_no_facts_no_service_is_empty(self):
        with (
            patch.object(agent_memory.memory_repo, "find_by_user", AsyncMock(return_value=[])),
            patch("app.memory.service.memory_service", None),
        ):
            assert await AgentMemory().get_memory_context("u") == ""

    async def test_a_failing_fact_lookup_is_swallowed(self):
        with (
            patch.object(agent_memory.memory_repo, "find_by_user", AsyncMock(side_effect=RuntimeError("db"))),
            patch("app.memory.service.memory_service", None),
        ):
            assert await AgentMemory().get_memory_context("u") == ""


class TestWithService:
    @pytest.fixture(autouse=True)
    def no_facts(self):
        with patch.object(agent_memory.memory_repo, "find_by_user", AsyncMock(return_value=[])):
            yield

    async def test_notes_are_listed_with_their_excerpt(self, world, serve):
        world.seed(f"{world.user}/a.md", title="Allergies", body="Arachide")
        memory = serve()

        text = await memory.get_memory_context(world.user)

        assert text.startswith("\n\nTes notes sur l'utilisateur :")
        assert "## Allergies\nArachide" in text
        assert "index local" not in text and "sautée" not in text

    async def test_the_facts_come_first_then_the_notes(self, world, serve, facts):
        world.seed(f"{world.user}/a.md", title="Allergies", body="Arachide")

        text = await serve().get_memory_context(world.user)

        assert text.index("Aime le café") < text.index("Allergies")

    async def test_pinned_notes_come_first_and_the_list_is_bounded(self, world, serve, monkeypatch):
        monkeypatch.setattr("app.memory.agent_memory.settings.memory_prompt_max_notes", 2)
        world.seed(f"{world.user}/old.md", title="Old", at=world.clock())
        world.clock.advance(100)
        world.seed(f"{world.user}/new.md", title="New", at=world.clock())
        world.clock.advance(100)
        world.seed(f"{world.user}/pin.md", title="Pinned", pinned=True, at=world.clock() - timedelta(days=30))
        world.seed(f"{world.user}/other.md", title="Another", at=world.clock() - timedelta(days=60))

        text = await serve().get_memory_context(world.user)

        titles = [line[3:] for line in text.splitlines() if line.startswith("## ")]
        assert titles == ["Pinned", "New"]

    async def test_an_excerpt_is_cut_to_the_configured_length(self, world, serve, monkeypatch):
        monkeypatch.setattr("app.memory.agent_memory.settings.memory_prompt_note_chars", 10)
        world.seed(f"{world.user}/a.md", title="Long", body="0123456789ABCDEFGHIJ")

        text = await serve().get_memory_context(world.user)

        assert "0123456789" in text and "ABCDEF" not in text

    async def test_only_active_readable_live_notes_reach_the_prompt(self, world, serve):
        world.seed(f"{world.user}/archive/gone.md", title="Archived")
        world.bucket.seed(f"{world.user}/bad.md", "---\ntitle: [x\n---\n")
        world.seed(f"{world.user}/ok.md", title="Visible")
        world.seed(f"{world.user}/removed.md", title="Removed")
        world.seed(f"{world.user}/keep1.md", title="Keep1")
        await world.reconciler.reconcile()
        world.bucket.remove(f"{world.user}/removed.md")
        await world.reconciler.reconcile()

        text = await serve().get_memory_context(world.user)

        titles = [line[3:] for line in text.splitlines() if line.startswith("## ")]
        assert sorted(titles) == ["Keep1", "Visible"]

    async def test_a_user_never_sees_another_users_notes(self, world, serve):
        world.seed(f"{world.user}/mine.md", title="Mine", body="private to me")
        world.seed(f"{world.other_user}/theirs.md", title="Theirs", body="private to them")
        memory = serve()

        mine = await memory.get_memory_context(world.user)
        theirs = await memory.get_memory_context(world.other_user)
        nobody = await memory.get_memory_context("someone-else")

        assert "private to me" in mine and "private to them" not in mine
        assert "private to them" in theirs and "private to me" not in theirs
        assert nobody == ""

    async def test_serving_a_note_counts_a_use_for_that_user_only(self, world, serve):
        mine = world.seed(f"{world.user}/mine.md", title="Mine")
        theirs = world.seed(f"{world.other_user}/theirs.md", title="Theirs")
        memory = serve()
        await world.sync.run_pass("loop")

        await memory.get_memory_context(world.user)
        await memory.get_memory_context(world.user)

        assert world.row(mine.id).use_count == 2 and world.row(mine.id).last_used_at is not None
        assert world.row(theirs.id).use_count == 0

    async def test_a_degraded_index_says_how_old_it_is(self, world, serve):
        world.seed(f"{world.user}/a.md", title="Allergies", body="Arachide")
        await world.sync.run_pass("loop")
        world.clock.advance(300)
        world.bucket.down = True

        text = await serve().get_memory_context(world.user)

        assert "index local" in text and "5 min" in text
        assert "## Allergies" in text
        assert "sautée" not in text

    async def test_a_skipped_sync_is_announced_apart_from_a_stale_index(self, make_world, serve):
        bucket = RacyBucket()
        import asyncio

        bucket.list_gate = asyncio.Event()
        world = make_world(bucket, turn_timeout_seconds=0.01)
        world.seed(f"{world.user}/a.md", title="Allergies")
        memory = serve(world)

        text = await memory.get_memory_context(world.user)

        assert "Synchronisation avec le bucket sautée" in text
        assert "index local" not in text
        bucket.list_gate.set()
        await world.sync._inflight

    async def test_notices_alone_are_enough_to_speak(self, world, serve):
        world.bucket.down = True

        text = await serve().get_memory_context(world.user)

        assert "index local" in text and "âge inconnu" in text

    async def test_nothing_to_say_is_an_empty_string(self, world, serve):
        assert await serve().get_memory_context(world.user) == ""

    async def test_a_failing_sync_never_breaks_the_prompt(self, world, serve, monkeypatch):
        async def broken() -> TurnState:
            raise RuntimeError("boom")

        monkeypatch.setattr(world.sync, "before_turn", broken)

        assert await serve().get_memory_context(world.user) == ""

    @pytest.mark.parametrize(
        ("delta", "text"),
        [
            (None, "âge inconnu"),
            (timedelta(seconds=45), "45 s"),
            (timedelta(seconds=300), "5 min"),
            (timedelta(hours=3), "3 h"),
        ],
    )
    def test_the_age_is_readable(self, delta, text):
        assert agent_memory._age(delta) == text


async def test_a_saved_note_reaches_the_next_prompt(world, serve):
    with patch.object(agent_memory.memory_repo, "find_by_user", AsyncMock(return_value=[])):
        await world.store.save(world.user, NoteDoc(title="Chat", body="Un chat nommé Pixel"))

        text = await serve().get_memory_context(world.user)

    assert "Un chat nommé Pixel" in text
