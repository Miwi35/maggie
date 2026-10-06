"""Tests for the scripted LLM stand-in (MAG-95).

Two things are worth guarding here, and they are different: that the scenario
files are matched and read the way their authors expect, and that what the fake
returns really drives the *production* tool loop and streaming gateway. The
second is the whole point of the ticket — a fake the real code cannot consume
would pass every unit test and break every journey.
"""

import json
import re
import uuid
from datetime import UTC, datetime
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import anthropic
import pytest
import yaml
from pydantic import ValidationError

from app.config import Settings
from app.db.context_model import ContextStatus, ConversationContext
from app.llm import fake as fake_module
from app.llm.client import create_llm_client, llm_configured
from app.llm.contexts import active_contexts_section
from app.llm.directives import HEADER
from app.llm.fake import (
    DEFAULT_FIXTURES_DIR,
    FakeAnthropicClient,
    FakeTextBlock,
    FakeToolUseBlock,
    ScenarioLibrary,
    history_text,
    last_user_text,
    no_scenario_message,
    no_turn_message,
    parse_scenario,
    system_text,
    turn_index,
)
from app.llm.runner import run_tool_loop
from app.llm.streaming import StreamingGateway


def write_scenario(directory: Path, filename: str, scenario: dict) -> Path:
    path = directory / filename
    path.write_text(yaml.safe_dump(scenario, allow_unicode=True))
    return path


@pytest.fixture()
def fixtures_dir(tmp_path: Path) -> Path:
    directory = tmp_path / "fake-llm"
    directory.mkdir()
    return directory


def build_client(directory: Path) -> FakeAnthropicClient:
    return FakeAnthropicClient(fixtures_dir=directory)


async def ask(
    client: FakeAnthropicClient,
    message: str,
    *,
    system: str = "Tu es Maggie.",
    tools=None,
    history: list[dict] | None = None,
):
    return await client.messages.create(
        model="fake",
        max_tokens=1024,
        system=system,
        messages=[*(history or []), {"role": "user", "content": message}],
        tools=tools,
    )


def text_of(message) -> str:
    return "".join(block.text for block in message.content if isinstance(block, FakeTextBlock))


class TestReadingTheRequest:
    def test_last_user_text_ignores_tool_result_batches(self):
        messages = [
            {"role": "user", "content": "mon agenda ?"},
            {"role": "assistant", "content": [{"type": "tool_use"}]},
            {"role": "user", "content": [{"type": "tool_result", "content": "{}"}]},
        ]
        assert last_user_text(messages) == "mon agenda ?"

    def test_last_user_text_is_empty_without_one(self):
        assert last_user_text([{"role": "assistant", "content": "bonjour"}]) == ""
        assert last_user_text(None) == ""

    def test_system_text_joins_cache_blocks(self):
        blocks = [
            {"type": "text", "text": "stable", "cache_control": {"type": "ephemeral"}},
            {"type": "text", "text": "volatile"},
        ]
        assert system_text(blocks) == "stable\nvolatile"
        assert system_text("plain") == "plain"
        assert system_text(None) == ""

    def test_history_text_leaves_out_the_message_being_answered(self):
        """Counting it would let a scenario "prove" the history carried what the user just typed."""
        messages = [
            {"role": "user", "content": "Où en est mon budget ?"},
            {"role": "assistant", "content": "Il tient la route."},
            {"role": "user", "content": "Reprends le fil"},
        ]

        assert history_text(messages) == "Où en est mon budget ?\nIl tient la route."

    def test_history_text_of_a_single_message_is_empty(self):
        assert history_text([{"role": "user", "content": "salut"}]) == ""
        assert history_text(None) == ""

    def test_history_text_stringifies_tool_result_batches(self):
        messages = [
            {"role": "assistant", "content": [{"type": "tool_use", "name": "get_budget"}]},
            {"role": "user", "content": "et après ?"},
        ]

        assert "get_budget" in history_text(messages)

    def test_turn_index_counts_tool_rounds(self):
        # History is plain strings, so only the current run's tool result
        # batches count — that is what lets the fake stay stateless.
        history = [{"role": "user", "content": "salut"}, {"role": "assistant", "content": "bonjour"}]
        assert turn_index(history) == 0

        after_one_round = [
            *history,
            {"role": "user", "content": "mon agenda ?"},
            {"role": "assistant", "content": [{"type": "tool_use"}]},
            {"role": "user", "content": [{"type": "tool_result"}]},
        ]
        assert turn_index(after_one_round) == 1


class TestParsing:
    def test_a_turn_needs_text_or_tools(self):
        with pytest.raises(ValueError, match="neither 'text' nor 'tools'"):
            parse_scenario({"name": "x", "turns": [{}]}, source="x.yaml")

    def test_a_scenario_needs_a_turn(self):
        with pytest.raises(ValueError, match="at least one turn"):
            parse_scenario({"name": "x", "turns": []}, source="x.yaml")

    def test_name_defaults_to_the_file_name(self):
        scenario = parse_scenario({"turns": [{"text": "ok"}]}, source="30-upcoming-events.yaml")
        assert scenario.name == "30-upcoming-events"

    def test_an_invalid_regex_is_refused_at_load(self):
        # Not at match time: a `re.error` out of `resolve()` comes back as a 500
        # on /chat and as "Désolé, une erreur est survenue." on the stream, and
        # the `[fake-llm]` sentence never gets printed.
        with pytest.raises(ValueError, match="not a valid regex"):
            parse_scenario({"match": {"user_matches": "ajoute("}, "turns": [{"text": "ok"}]}, source="x.yaml")

    def test_stream_delay_defaults_to_none(self):
        assert parse_scenario({"turns": [{"text": "ok"}]}, source="x.yaml").stream_delay_ms == 0

    def test_stream_delay_is_read_from_the_scenario(self):
        scenario = parse_scenario({"stream_delay_ms": 600, "turns": [{"text": "ok"}]}, source="x.yaml")

        assert scenario.stream_delay_ms == 600

    @pytest.mark.parametrize("value", [-1, "600", 1.5, True, None])
    def test_a_bad_stream_delay_is_refused_at_load(self, value):
        with pytest.raises(ValueError, match="stream_delay_ms"):
            parse_scenario({"stream_delay_ms": value, "turns": [{"text": "ok"}]}, source="x.yaml")

    async def test_a_stream_delay_spaces_the_text_deltas_out(self, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "20-slow.yaml",
            {"match": {"user_contains": "histoire"}, "stream_delay_ms": 250, "turns": [{"text": "x" * 60}]},
        )
        write_scenario(fixtures_dir, "30-fast.yaml", {"match": {"user_contains": "salut"}, "turns": [{"text": "x" * 60}]})
        client = build_client(fixtures_dir)

        async def waits(question: str) -> list[float]:
            sleeps = AsyncMock()
            with patch.object(fake_module.asyncio, "sleep", sleeps):
                async with client.messages.stream(
                    model="fake", max_tokens=1024, system="", messages=[{"role": "user", "content": question}]
                ) as stream:
                    deltas = [e.delta.text async for e in stream if e.type == "content_block_delta"]
            assert "".join(deltas) == "x" * 60
            return [call.args[0] for call in sleeps.await_args_list]

        slow = await waits("une histoire")
        assert len(slow) > 1
        assert set(slow) == {0.25}
        assert await waits("salut") == []

    def test_a_broken_file_is_skipped_not_fatal(self, fixtures_dir):
        (fixtures_dir / "10-broken.yaml").write_text("turns: []\n")
        write_scenario(fixtures_dir, "20-fine.yaml", {"match": {"user_contains": "salut"}, "turns": [{"text": "ok"}]})

        library = ScenarioLibrary(directory=fixtures_dir)
        library.load()

        assert [s.name for s in library.scenarios] == ["20-fine"]


class TestMatching:
    async def test_user_contains_is_case_insensitive(self, fixtures_dir):
        write_scenario(fixtures_dir, "10-a.yaml", {"match": {"user_contains": "agenda"}, "turns": [{"text": "vu"}]})
        client = build_client(fixtures_dir)

        assert text_of(await ask(client, "Mon AGENDA du jour ?")) == "vu"

    async def test_all_declared_conditions_must_hold(self, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "10-a.yaml",
            {"match": {"user_contains": ["agenda", "demain"]}, "turns": [{"text": "les deux"}]},
        )
        client = build_client(fixtures_dir)

        assert text_of(await ask(client, "mon agenda demain ?")) == "les deux"
        assert "[fake-llm]" in text_of(await ask(client, "mon agenda ?"))

    async def test_system_contains_separates_the_infrastructure_calls(self, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "10-router.yaml",
            {"match": {"system_contains": "routeur de contexte"}, "turns": [{"text": '{"context_id": null}'}]},
        )
        write_scenario(fixtures_dir, "20-chat.yaml", {"match": {"user_contains": "salut"}, "turns": [{"text": "hello"}]})
        client = build_client(fixtures_dir)

        routed = await ask(client, "Message : salut", system="Tu es un routeur de contexte.")
        assert text_of(routed) == '{"context_id": null}'
        assert text_of(await ask(client, "salut")) == "hello"

    async def test_history_contains_matches_on_what_the_conversation_held(self, fixtures_dir):
        """The only handle a journey has on the history it was sent (MAG-13)."""
        write_scenario(
            fixtures_dir,
            "10-recall.yaml",
            {
                "match": {"user_contains": "reprends le fil", "history_contains": "où en est mon budget"},
                "turns": [{"text": "On parlait de ton budget."}],
            },
        )
        client = build_client(fixtures_dir)

        recalled = await ask(
            client,
            "Reprends le fil",
            history=[
                {"role": "user", "content": "Où en est mon budget ?"},
                {"role": "assistant", "content": "Il tient la route."},
            ],
        )
        assert text_of(recalled) == "On parlait de ton budget."

    async def test_history_contains_fails_when_the_thread_was_not_sent(self, fixtures_dir):
        """A history built from the wrong thread leaves the `[fake-llm]` sentence, which names its cause."""
        write_scenario(
            fixtures_dir,
            "10-recall.yaml",
            {
                "match": {"user_contains": "reprends le fil", "history_contains": "où en est mon budget"},
                "turns": [{"text": "On parlait de ton budget."}],
            },
        )
        client = build_client(fixtures_dir)

        answered = await ask(
            client,
            "Reprends le fil",
            history=[{"role": "user", "content": "Il me faut de la farine"}],
        )
        assert "[fake-llm]" in text_of(answered)

    async def test_history_contains_ignores_the_message_being_answered(self, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "10-recall.yaml",
            {"match": {"history_contains": "mon budget"}, "turns": [{"text": "vu"}]},
        )
        client = build_client(fixtures_dir)

        assert "[fake-llm]" in text_of(await ask(client, "Où en est mon budget ?"))

    async def test_file_name_order_decides_the_winner(self, fixtures_dir):
        write_scenario(fixtures_dir, "20-second.yaml", {"match": {"user_contains": "liste"}, "turns": [{"text": "b"}]})
        write_scenario(fixtures_dir, "10-first.yaml", {"match": {"user_contains": "liste"}, "turns": [{"text": "a"}]})
        client = build_client(fixtures_dir)

        assert text_of(await ask(client, "ma liste ?")) == "a"

    async def test_the_default_is_tried_last_whatever_its_name(self, fixtures_dir):
        write_scenario(fixtures_dir, "00-default.yaml", {"default": True, "turns": [{"text": "fallback"}]})
        write_scenario(fixtures_dir, "90-precise.yaml", {"match": {"user_contains": "agenda"}, "turns": [{"text": "vu"}]})
        client = build_client(fixtures_dir)

        assert text_of(await ask(client, "mon agenda ?")) == "vu"
        assert text_of(await ask(client, "autre chose")) == "fallback"

    async def test_a_scenario_without_conditions_matches_nothing(self, fixtures_dir):
        write_scenario(fixtures_dir, "10-unreachable.yaml", {"turns": [{"text": "jamais"}]})
        client = build_client(fixtures_dir)

        assert text_of(await ask(client, "n'importe quoi")) == no_scenario_message("n'importe quoi")

    async def test_no_scenario_answers_with_its_own_cause(self, fixtures_dir):
        client = build_client(fixtures_dir)

        answer = await ask(client, "une question que personne n'a scriptée")

        assert answer.stop_reason == "end_turn"
        assert "[fake-llm] aucun scénario" in text_of(answer)
        assert "personne n'a scriptée" in text_of(answer)

    async def test_capture_groups_go_back_into_the_answer(self, fixtures_dir):
        # The one thing a scripted answer cannot hardcode: an id that changes
        # between runs.
        write_scenario(
            fixtures_dir,
            "10-router.yaml",
            {
                "match": {"system_contains": "routeur", "user_matches": 'id="([0-9A-Z]{26})"'},
                "turns": [{"text": '{"context_id": "\\1"}'}],
            },
        )
        client = build_client(fixtures_dir)

        answer = await ask(
            client,
            'Contextes existants :\n- id="01JBQF3KQXW8Z7P4M5R6T9VNCE" label="Courses"\n\nMessage : et demain ?',
            system="Tu es un routeur de contexte.",
        )

        assert json.loads(text_of(answer)) == {"context_id": "01JBQF3KQXW8Z7P4M5R6T9VNCE"}

    async def test_capture_groups_go_into_a_scripted_tool_call(self, fixtures_dir):
        # Same problem one layer down, and it made a whole tool unreachable:
        # `move_to_fallback` takes the ULID of the shop that closed, and a ULID
        # differs on every seed — so no fixture could name one and no journey
        # could exercise the tool (MAG-101). The journey says the id, the
        # scenario captures it, the real MCP call receives it.
        write_scenario(
            fixtures_dir,
            "10-fallback.yaml",
            {
                "match": {"user_matches": "magasin ([0-9A-HJKMNP-TV-Z]{26}) est fermé"},
                "turns": [
                    {
                        "tools": [
                            {
                                "name": "move_to_fallback",
                                # A nested list and a non-string beside it: both
                                # have to survive, the first expanded and the
                                # second untouched.
                                "input": {"storeId": "\\1", "also": ["\\1"], "retries": 2, "dryRun": False},
                            }
                        ]
                    }
                ],
            },
        )
        client = build_client(fixtures_dir)

        answer = await ask(
            client,
            "Le magasin 01JBQF3KQXW8Z7P4M5R6T9VNCE est fermé, déplace ce qu'il me faut ailleurs",
            tools=[{"name": "move_to_fallback"}],
        )

        tool_block = answer.content[0]
        assert tool_block.input == {
            "storeId": "01JBQF3KQXW8Z7P4M5R6T9VNCE",
            "also": ["01JBQF3KQXW8Z7P4M5R6T9VNCE"],
            "retries": 2,
            "dryRun": False,
        }

    async def test_a_tool_input_without_a_capture_group_is_left_alone(self, fixtures_dir):
        # The common case, and the one that must not become collateral damage:
        # a label is a label, whatever the scenario matched on.
        write_scenario(
            fixtures_dir,
            "10-basil.yaml",
            {
                "match": {"user_matches": "ajoute (.+)"},
                "turns": [{"tools": [{"name": "add_grocery_item", "input": {"label": "Basilic", "quantity": 1}}]}],
            },
        )
        client = build_client(fixtures_dir)

        answer = await ask(client, "ajoute du basilic", tools=[{"name": "add_grocery_item"}])

        assert answer.content[0].input == {"label": "Basilic", "quantity": 1}


class TestTurns:
    async def test_a_tool_turn_asks_for_its_tools(self, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "10-agenda.yaml",
            {
                "match": {"user_contains": "agenda"},
                "turns": [
                    {"text": "Je regarde.", "tools": [{"name": "get_upcoming_events", "input": {"days": 7}}]},
                    {"text": "Rien de prévu."},
                ],
            },
        )
        client = build_client(fixtures_dir)

        first = await ask(client, "mon agenda ?", tools=[{"name": "get_upcoming_events"}])

        assert first.stop_reason == "tool_use"
        assert [type(block) for block in first.content] == [FakeTextBlock, FakeToolUseBlock]
        tool_block = first.content[1]
        assert (tool_block.name, tool_block.input) == ("get_upcoming_events", {"days": 7})

    async def test_the_turn_follows_the_tool_rounds_already_done(self, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "10-agenda.yaml",
            {
                "match": {"user_contains": "agenda"},
                "turns": [
                    {"tools": [{"name": "get_upcoming_events", "input": {}}]},
                    {"text": "Rien de prévu."},
                ],
            },
        )
        client = build_client(fixtures_dir)

        second = await client.messages.create(
            model="fake",
            system="Tu es Maggie.",
            messages=[
                {"role": "user", "content": "mon agenda ?"},
                {"role": "assistant", "content": [{"type": "tool_use"}]},
                {"role": "user", "content": [{"type": "tool_result", "content": "[]"}]},
            ],
        )

        assert second.stop_reason == "end_turn"
        assert text_of(second) == "Rien de prévu."

    async def test_running_out_of_turns_says_so(self, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "10-agenda.yaml",
            {"match": {"user_contains": "agenda"}, "turns": [{"tools": [{"name": "get_upcoming_events"}]}]},
        )
        client = build_client(fixtures_dir)

        answer = await client.messages.create(
            model="fake",
            system="Tu es Maggie.",
            messages=[
                {"role": "user", "content": "mon agenda ?"},
                {"role": "assistant", "content": [{"type": "tool_use"}]},
                {"role": "user", "content": [{"type": "tool_result", "content": "[]"}]},
            ],
        )

        assert text_of(answer) == no_turn_message("10-agenda", 2)

    async def test_a_tool_nobody_offered_is_reported(self, fixtures_dir, caplog):
        write_scenario(
            fixtures_dir,
            "10-agenda.yaml",
            {"match": {"user_contains": "agenda"}, "turns": [{"tools": [{"name": "get_upcoming_events"}]}]},
        )
        client = build_client(fixtures_dir)

        with caplog.at_level("ERROR"):
            await ask(client, "mon agenda ?", tools=[{"name": "add_grocery_item"}])

        assert "discovery.scan_dirs" in caplog.text

    async def test_usage_is_reported_so_the_metrics_path_runs(self, fixtures_dir):
        write_scenario(fixtures_dir, "10-a.yaml", {"match": {"user_contains": "salut"}, "turns": [{"text": "Bonjour"}]})
        client = build_client(fixtures_dir)

        answer = await ask(client, "salut")

        assert answer.usage.input_tokens > 0
        assert answer.usage.output_tokens > 0


class TestReload:
    async def test_a_fixture_edited_mid_session_takes_effect(self, fixtures_dir):
        path = write_scenario(
            fixtures_dir, "10-a.yaml", {"match": {"user_contains": "salut"}, "turns": [{"text": "avant"}]}
        )
        client = build_client(fixtures_dir)
        assert text_of(await ask(client, "salut")) == "avant"

        path.write_text(yaml.safe_dump({"match": {"user_contains": "salut"}, "turns": [{"text": "après"}]}))
        # No sleep: the library compares the files' bytes, not their mtimes, so
        # an edit inside one filesystem timestamp tick is still an edit.
        assert text_of(await ask(client, "salut")) == "après"

    async def test_a_deleted_file_stops_matching(self, fixtures_dir):
        path = write_scenario(
            fixtures_dir, "10-a.yaml", {"match": {"user_contains": "salut"}, "turns": [{"text": "Bonjour"}]}
        )
        client = build_client(fixtures_dir)
        assert text_of(await ask(client, "salut")) == "Bonjour"

        path.unlink()

        assert "[fake-llm]" in text_of(await ask(client, "salut"))

    async def test_a_new_file_is_picked_up(self, fixtures_dir):
        client = build_client(fixtures_dir)
        assert "[fake-llm]" in text_of(await ask(client, "salut"))

        write_scenario(fixtures_dir, "10-a.yaml", {"match": {"user_contains": "salut"}, "turns": [{"text": "Bonjour"}]})

        assert text_of(await ask(client, "salut")) == "Bonjour"


class TestTheRealToolLoop:
    """The fake, driving `run_tool_loop` and the real `ToolRouter`."""

    async def test_it_drives_a_tool_round_through_the_router(self, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "10-memory.yaml",
            {
                "match": {"user_contains": "allergique"},
                "turns": [
                    {
                        "tools": [
                            {"name": "store_memory", "input": {"content": "Allergique aux noix", "type": "factual"}}
                        ]
                    },
                    {"text": "C'est noté."},
                ],
            },
        )
        client = build_client(fixtures_dir)

        from app.llm.tools import ToolRouter

        stored = MagicMock()
        stored.to_dict.return_value = {"id": "mem-1", "content": "Allergique aux noix"}
        messages = [{"role": "user", "content": "Je suis allergique aux noix"}]

        with (
            patch("app.llm.tools.memory_repo") as memory_repo,
            patch("app.llm.runner.record_llm_usage"),
        ):
            memory_repo.store = AsyncMock(return_value=stored)
            result = await run_tool_loop(
                "Tu es Maggie.",
                messages,
                [{"name": "store_memory", "input_schema": {}}],
                client=client,
                tool_router=ToolRouter(),
                user_id="user-1",
                model="fake",
            )

        assert result["response"] == "C'est noté."
        assert [call["name"] for call in result["tool_calls"]] == ["store_memory"]
        memory_repo.store.assert_awaited_once_with("user-1", "Allergique aux noix", "factual")
        # The loop appended the assistant turn and the tool results, which means
        # the fake's blocks were consumable by the production code.
        assert [m["role"] for m in messages] == ["user", "assistant", "user"]
        assert messages[-1]["content"][0]["tool_use_id"] == "toolu_fake_0_0"


class TestTheRealStreamingGateway:
    """The fake, driving `StreamingGateway.chat_stream` and the AG-UI events."""

    async def _events(
        self, fixtures_dir, *, question: str = "mon agenda ?", tool_results: list[str] | None = None, saved: list | None = None
    ) -> list[dict]:
        client = build_client(fixtures_dir)

        with (
            patch("app.llm.streaming.settings") as settings,
            patch("app.llm.streaming.message_repo") as message_repo,
            patch("app.llm.history.message_repo") as history_repo,
            patch("app.llm.history.context_repo") as history_contexts,
            patch("app.llm.contexts.message_repo") as routing_repo,
            patch("app.llm.contexts.context_repo") as context_repo,
            patch("app.llm.streaming.record_llm_usage"),
        ):
            settings.anthropic_api_key = ""
            settings.llm_provider = "fake"
            settings.anthropic_model = "fake"
            # The route persists the user message before streaming, so history
            # is where `chat_stream` finds it — returning an empty history here
            # would have the fake answering an empty question.
            persisted = MagicMock()
            persisted.id = "msg-1"
            persisted.role = "user"
            persisted.content = question
            persisted.context_id = "ctx-1"
            persisted.created_at = datetime(2026, 10, 1, 12, tzinfo=UTC)
            history_repo.find_by_context = AsyncMock(return_value=[persisted])
            history_repo.find_recent = AsyncMock(return_value=[persisted])
            history_contexts.find_active = AsyncMock(return_value=[])
            message_repo.create = AsyncMock(side_effect=lambda **kwargs: saved.append(kwargs) if saved is not None else None)
            routing_repo.update_context = AsyncMock()
            context_repo.find_active = AsyncMock(return_value=[])
            context_repo.append_tool_call = AsyncMock()
            created = MagicMock()
            created.id = "ctx-1"
            context_repo.create = AsyncMock(return_value=created)

            gateway = StreamingGateway()
            gateway.client = client
            gateway.personality = MagicMock()
            gateway.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
            gateway.agent_memory = MagicMock()
            gateway.agent_memory.get_memory_context = AsyncMock(return_value="")
            gateway.tool_router = MagicMock()
            gateway.tool_router.get_tool_definitions = AsyncMock(
                return_value=[
                    {"name": "get_upcoming_events", "input_schema": {}},
                    {"name": "create_event", "input_schema": {}},
                ]
            )
            gateway.tool_router.call_tool = AsyncMock(side_effect=tool_results or ['{"events": []}'])

            return [event async for event in gateway.chat_stream(question, "user-1", "msg-1")]

    @pytest.fixture()
    def scripted(self, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "10-router.yaml",
            {
                "match": {"system_contains": "routeur de contexte"},
                "turns": [{"text": '{"context_id": null, "label": "Agenda"}'}],
            },
        )
        write_scenario(
            fixtures_dir,
            "20-agenda.yaml",
            {
                "match": {"user_contains": "agenda"},
                "turns": [
                    {"tools": [{"name": "get_upcoming_events", "input": {"days": 7}}]},
                    {"text": "Vous avez un déjeuner avec Alex aujourd'hui."},
                ],
            },
        )
        return fixtures_dir

    async def test_it_emits_the_whole_ag_ui_sequence(self, scripted):
        events = await self._events(scripted)
        types = [event["type"] for event in events]

        assert types[0] == "RUN_STARTED"
        assert types[-1] == "RUN_FINISHED"
        assert "TOOL_CALL_START" in types
        assert "TOOL_CALL_END" in types
        assert types.index("TOOL_CALL_START") < types.index("TEXT_MESSAGE_START")
        assert types.count("TEXT_MESSAGE_START") == 1
        assert types.count("TEXT_MESSAGE_END") == 1

    async def test_the_deltas_reconstitute_the_scripted_answer(self, scripted):
        events = await self._events(scripted)

        deltas = [e["delta"] for e in events if e["type"] == "TEXT_MESSAGE_CONTENT"]

        assert len(deltas) > 1, "a one-shot delta is not streaming"
        assert "".join(deltas) == "Vous avez un déjeuner avec Alex aujourd'hui."

    async def test_the_context_router_answer_reaches_the_mind_panel(self, scripted):
        events = await self._events(scripted)

        context_updates = [e for e in events if e.get("name") == "context_update"]
        assert context_updates and context_updates[0]["value"]["label"] == "Agenda"

    async def test_the_tool_result_is_reported(self, scripted):
        events = await self._events(scripted)

        results = [e for e in events if e.get("name") == "tool_result"]
        assert results and results[0]["value"] == {
            "toolCallId": "toolu_fake_0_0",
            "toolName": "get_upcoming_events",
            "status": "success",
        }


def the_bubble(events: list[dict]) -> str:
    """What a client shows once the stream is over: a TEXT_MESSAGE_START empties the bubble."""
    text = ""
    for event in events:
        if event["type"] == "TEXT_MESSAGE_START":
            text = ""
        elif event["type"] == "TEXT_MESSAGE_CONTENT":
            text += event["delta"]
    return text


class TestAMultiStepAnswerKeepsOnlyItsLastStep:
    """MAG-229: the text of the steps before the last tool call is not the answer."""

    QUESTION = "note le concert des Black Wizards"
    ANNOUNCE = "Je prends une durée standard de 2 heures."
    EXCUSE = "Il y a un souci technique avec l'identifiant de votre agenda."
    FINAL = "Voilà, c'est noté : concert des Black Wizards le 2 novembre à 19h, pour deux heures."
    FAILURE = '{"error": "unknown calendar"}'

    @pytest.fixture()
    def gateway_tests(self):
        return TestTheRealStreamingGateway()

    @pytest.fixture()
    def retry_scenario(self, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "10-router.yaml",
            {
                "match": {"system_contains": "routeur de contexte"},
                "turns": [{"text": '{"context_id": null, "label": "Concerts"}'}],
            },
        )
        write_scenario(
            fixtures_dir,
            "20-concert.yaml",
            {
                "match": {"user_contains": "black wizards"},
                "turns": [
                    {"text": self.ANNOUNCE, "tools": [{"name": "create_event", "input": {"calendar": "x"}}]},
                    {"text": self.EXCUSE, "tools": [{"name": "create_event", "input": {"calendar": "concerts"}}]},
                    {"text": self.FINAL},
                ],
            },
        )
        return fixtures_dir

    async def _run(self, gateway_tests, fixtures_dir):
        saved: list[dict] = []
        events = await gateway_tests._events(
            fixtures_dir,
            question=self.QUESTION,
            tool_results=[self.FAILURE, '{"id": "evt-1"}'],
            saved=saved,
        )
        return events, saved

    async def test_the_stored_message_is_the_last_step_alone(self, gateway_tests, retry_scenario):
        _, saved = await self._run(gateway_tests, retry_scenario)

        assert [message["content"] for message in saved] == [self.FINAL]

    async def test_the_bubble_ends_up_with_the_last_step_alone(self, gateway_tests, retry_scenario):
        events, _ = await self._run(gateway_tests, retry_scenario)

        assert the_bubble(events) == self.FINAL

    async def test_the_last_step_streams_token_by_token(self, gateway_tests, retry_scenario):
        events, _ = await self._run(gateway_tests, retry_scenario)

        last_start = max(i for i, e in enumerate(events) if e["type"] == "TEXT_MESSAGE_START")
        deltas = [e["delta"] for e in events[last_start:] if e["type"] == "TEXT_MESSAGE_CONTENT"]

        assert len(deltas) > 1
        assert "".join(deltas) == self.FINAL

    async def test_the_message_is_closed_once_and_the_run_finishes(self, gateway_tests, retry_scenario):
        events, _ = await self._run(gateway_tests, retry_scenario)
        types = [event["type"] for event in events]

        assert types.count("TEXT_MESSAGE_END") == 1
        assert types.index("TEXT_MESSAGE_END") > max(i for i, t in enumerate(types) if t == "TEXT_MESSAGE_START")
        assert types[-1] == "RUN_FINISHED"
        assert len({e["messageId"] for e in events if e["type"].startswith("TEXT_MESSAGE")}) == 1

    async def test_a_failure_after_a_first_step_leaves_the_apology_alone(self, gateway_tests, retry_scenario):
        saved: list[dict] = []
        events = await gateway_tests._events(
            retry_scenario,
            question=self.QUESTION,
            tool_results=[RuntimeError("tool crashed")],
            saved=saved,
        )
        apology = "Désolé, une erreur est survenue. Réessaie."

        assert [message["content"] for message in saved] == [apology]
        assert the_bubble(events) == apology
        assert [e["type"] for e in events].count("TEXT_MESSAGE_END") == 1

    async def test_blank_lead_in_is_not_shown(self, gateway_tests, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "10-router.yaml",
            {
                "match": {"system_contains": "routeur de contexte"},
                "turns": [{"text": '{"context_id": null, "label": "Concerts"}'}],
            },
        )
        write_scenario(
            fixtures_dir,
            "20-concert.yaml",
            {"match": {"user_contains": "black wizards"}, "turns": [{"text": "\n\n  " + self.FINAL}]},
        )
        saved: list[dict] = []
        events = await gateway_tests._events(fixtures_dir, question=self.QUESTION, saved=saved)

        assert the_bubble(events) == self.FINAL
        assert [message["content"] for message in saved] == [self.FINAL]

    async def test_the_shipped_retry_scenario_ends_on_its_closing_sentence(self, gateway_tests):
        saved: list[dict] = []
        events = await gateway_tests._events(
            DEFAULT_FIXTURES_DIR,
            question="Note-moi le concert des Black Wizards le 3 novembre 2099 à 19h",
            tool_results=['{"error": "No agenda found."}', '{"success": true}'],
            saved=saved,
        )
        answer = "C'est noté : les Black Wizards en concert le 3 novembre 2099 à 19h, pour deux heures."

        assert [message["content"] for message in saved] == [answer]
        assert the_bubble(events) == answer
        statuses = [e["value"]["status"] for e in events if e.get("name") == "tool_result"]
        assert statuses == ["error", "success"]

    async def test_an_answer_without_a_closing_step_falls_back_to_the_last_text_said(self, gateway_tests, fixtures_dir):
        write_scenario(
            fixtures_dir,
            "10-router.yaml",
            {
                "match": {"system_contains": "routeur de contexte"},
                "turns": [{"text": '{"context_id": null, "label": "Concerts"}'}],
            },
        )
        write_scenario(
            fixtures_dir,
            "20-concert.yaml",
            {
                "match": {"user_contains": "black wizards"},
                "turns": [
                    {"text": self.ANNOUNCE, "tools": [{"name": "create_event", "input": {}}]},
                    {"text": "   "},
                ],
            },
        )
        saved: list[dict] = []
        events = await gateway_tests._events(fixtures_dir, question=self.QUESTION, saved=saved)

        assert [message["content"] for message in saved] == [self.ANNOUNCE]
        assert the_bubble(events) == self.ANNOUNCE


class TestTheProviderSwitch:
    def test_fake_needs_no_api_key(self, monkeypatch):
        monkeypatch.setattr("app.llm.client.settings.llm_provider", "fake")
        monkeypatch.setattr("app.llm.client.settings.anthropic_api_key", "")
        monkeypatch.setattr("app.llm.client.settings.fake_llm_fixtures_dir", "")

        assert llm_configured() is True
        assert isinstance(create_llm_client(), FakeAnthropicClient)

    def test_anthropic_is_the_default_and_still_needs_one(self, monkeypatch):
        monkeypatch.setattr("app.llm.client.settings.llm_provider", "anthropic")
        monkeypatch.setattr("app.llm.client.settings.anthropic_api_key", "")

        assert llm_configured() is False

        monkeypatch.setattr("app.llm.client.settings.anthropic_api_key", "sk-test")
        assert llm_configured() is True
        assert isinstance(create_llm_client(), anthropic.AsyncAnthropic)

    def test_an_unknown_provider_is_refused_at_startup(self):
        # Refused by Settings, not by the call site: with a key present
        # `LLM_PROVIDER=Fake` would reach the real API, and without one the agent
        # would answer "the AI service is not configured" to every journey step
        # while naming nothing. Both have to fail the same way.
        with pytest.raises(ValidationError, match="LLM_PROVIDER must be one of"):
            Settings(llm_provider="Fake")

        assert Settings(llm_provider="fake").llm_provider == "fake"
        assert Settings(llm_provider="anthropic").llm_provider == "anthropic"

    def test_the_fixtures_dir_is_configurable(self, monkeypatch, fixtures_dir):
        monkeypatch.setattr("app.llm.client.settings.llm_provider", "fake")
        monkeypatch.setattr(fake_module.settings, "fake_llm_fixtures_dir", str(fixtures_dir))

        client = create_llm_client()

        assert isinstance(client, FakeAnthropicClient)
        assert client.library.directory == fixtures_dir


class TestTheShippedFixtures:
    """The files the e2e stack actually runs on."""

    def test_they_all_parse(self):
        library = ScenarioLibrary(directory=DEFAULT_FIXTURES_DIR)
        library.load()

        files = sorted(DEFAULT_FIXTURES_DIR.glob("*.yaml"))
        assert files, "the e2e stack has no scenario to run on"
        assert len(library.scenarios) == len(files)

    def test_no_catch_all_ships(self):
        # A default here would turn "nobody scripted this" into a plausible
        # answer, which is the failure mode the [fake-llm] sentence exists to
        # prevent.
        library = ScenarioLibrary(directory=DEFAULT_FIXTURES_DIR)
        library.load()

        assert [s.name for s in library.scenarios if s.is_default] == []

    async def test_the_context_router_opens_then_reuses_a_context(self):
        client = build_client(DEFAULT_FIXTURES_DIR)
        router_system = (
            "Tu es un routeur de contexte. Analyse le message et les contextes existants.\n"
            'Réponds UNIQUEMENT avec un JSON valide : {"context_id": "<id>"}'
        )

        opened = await ask(client, "Contextes existants :\n(aucun)\n\nMessage : salut", system=router_system)
        assert json.loads(text_of(opened)) == {"context_id": None, "label": "Conversation e2e"}

        # A conversation context's id is `uuid4().hex`, not a ULID like the API's
        # entities — see `ConversationContext.id`. Matching the wrong shape costs
        # nothing visible: every message simply opens a context of its own.
        context_id = uuid.uuid4().hex
        assert re.fullmatch(r"[0-9a-f]{32}", context_id)
        assert ConversationContext.id.type.length == len(context_id), "the context id format has moved"

        reused = await ask(
            client,
            f'Contextes existants :\n- id="{context_id}" label="Conversation e2e"\n\nMessage : et demain ?',
            system=router_system,
        )
        assert json.loads(text_of(reused)) == {"context_id": context_id}

    async def test_a_change_of_subject_opens_a_second_context(self):
        # The one message that must *not* join the context already open, so the
        # chat journey has a second one to find in the Mind Panel. It only works
        # because 05-context-router-new-topic.yaml is numbered below
        # 10-context-router-existing.yaml, which would otherwise answer first
        # with the id it was shown — and the symptom of losing that ordering is
        # a journey failing on "matched" with nothing naming the cause.
        client = build_client(DEFAULT_FIXTURES_DIR)
        router_system = "Tu es un routeur de contexte. Analyse le message et les contextes existants."
        context_id = uuid.uuid4().hex

        switched = await ask(
            client,
            f'Contextes existants :\n- id="{context_id}" label="Conversation e2e"\n\n'
            "Message : Parlons de mes finances, où en est mon budget ?",
            system=router_system,
        )

        assert json.loads(text_of(switched)) == {"context_id": None, "label": "Budget e2e"}

        # And the chat side answers that same message, so the switch does not
        # end on "[fake-llm] aucun scénario".
        assert "[fake-llm]" not in text_of(await ask(client, "Parlons de mes finances, où en est mon budget ?"))

    async def test_an_older_thread_can_be_picked_back_up(self):
        """04 + 72, the pair the MAG-13 journey rests on.

        The router has to answer with the id of « Budget e2e » even though it is not the
        first thread in the list — 10-context-router-existing.yaml would answer with the
        first one, and the journey would then assert the history of the wrong thread. And
        the chat side must be unreachable without that thread's own messages, which is what
        makes the journey a proof rather than a wording check.
        """
        client = build_client(DEFAULT_FIXTURES_DIR)
        router_system = "Tu es un routeur de contexte. Analyse le message et les contextes existants."
        current, budget = uuid.uuid4().hex, uuid.uuid4().hex

        routed = await ask(
            client,
            f'Contextes existants :\n- id="{current}" label="Conversation e2e"\n'
            f'- id="{budget}" label="Budget e2e"\n\n'
            "Message : Reprends le fil de mon budget, s'il te plaît",
            system=router_system,
        )
        assert json.loads(text_of(routed)) == {"context_id": budget}

        # Without the thread's messages: no scenario, and a failure that names its cause.
        unaware = await ask(client, "Reprends le fil de mon budget, s'il te plaît")
        assert "[fake-llm]" in text_of(unaware)

        # With them: the scripted answer the journey asserts.
        aware = await ask(
            client,
            "Reprends le fil de mon budget, s'il te plaît",
            history=[
                {"role": "user", "content": "[fil « Budget e2e »] Parlons de mes finances, où en est mon budget ?"},
                {"role": "assistant", "content": "Votre budget tient la route ce mois-ci."},
            ],
        )
        assert "je reprends le fil" in text_of(aware)

    async def test_an_interrupted_dictation_is_acknowledged_not_repeated(self):
        """MAG-223: the 41 trio, in order — only the history differs, so only the history may decide."""
        client = build_client(DEFAULT_FIXTURES_DIR)
        sentence = "Ajoute des tomates à la liste de courses"
        first_answer = [
            {"role": "user", "content": sentence},
            {"role": "assistant", "content": "C'est ajouté : quatre tomates, au primeur du marché."},
        ]
        cut = [
            *first_answer,
            {"role": "user", "content": sentence},
            {
                "role": "assistant",
                "content": "Il était une fois\n\n(Le user t'a coupé la parole : ne reprends pas ton histoire.)",
            },
        ]

        assert (await ask(client, sentence)).stop_reason == "tool_use"
        assert "FIN DE L'HISTOIRE" in text_of(await ask(client, sentence, history=first_answer))
        acknowledged = text_of(await ask(client, sentence, history=cut))
        assert "je ne reprends pas" in acknowledged
        assert "FIN DE L'HISTOIRE" not in acknowledged

    def test_the_long_story_is_slow_enough_to_interrupt(self):
        library = ScenarioLibrary(directory=DEFAULT_FIXTURES_DIR)
        library.load()

        story = next(s for s in library.scenarios if s.name == "dictated-long-story")
        text = story.turns[0].text

        # On a CI emulator Maestro needs over ten seconds to notice the first words, and the
        # story at 400 ms a delta (~12 s) was over before the mic was pressed (main, 6985e58).
        assert story.stream_delay_ms * (len(text) / 24) >= 25000, "a journey needs tens of seconds to press the mic"

    async def test_the_voice_path_cleans_the_stubbed_whisper_sentence(self):
        client = build_client(DEFAULT_FIXTURES_DIR)

        answer = await ask(
            client,
            "Tu es un assistant de transcription. […]\n\nTexte dicté :\n"
            "euh ajoute des tomates à la liste de courses s'il te plaît",
            system="",
        )

        cleaned = text_of(answer)
        assert "euh" not in cleaned
        # And the cleaned sentence is itself scripted, so dictating ends on a
        # real write.
        assert "[fake-llm]" not in text_of(await ask(client, cleaned))

    async def test_asking_for_the_agenda_calls_the_calendar_tool(self):
        client = build_client(DEFAULT_FIXTURES_DIR)

        answer = await ask(client, "Qu'est-ce que j'ai de prévu aujourd'hui ?")

        assert answer.stop_reason == "tool_use"
        assert [block.name for block in answer.content if isinstance(block, FakeToolUseBlock)] == [
            "get_upcoming_events"
        ]

    async def test_booking_an_appointment_calls_the_write_tool(self):
        client = build_client(DEFAULT_FIXTURES_DIR)

        answer = await ask(client, "Note-moi un dentiste le 12 mars 2099 à 14h")

        assert answer.stop_reason == "tool_use"
        booked = next(block for block in answer.content if isinstance(block, FakeToolUseBlock))
        assert booked.name == "create_event"
        # Read by the chat journey to find what Maggie wrote, so the two have to
        # agree: a title edited here and not there fails on an empty collection.
        assert booked.input["title"] == "Dentiste"
        assert booked.input["date"].startswith("2099-")

    async def test_a_proaction_prompt_has_something_to_say(self):
        # `POST /agent/proaction` runs the tool loop with no conversation history
        # of its own, so nothing else in this directory covers that entry point.
        client = build_client(DEFAULT_FIXTURES_DIR)

        answer = await ask(client, "Rappelle-lui de sortir les poubelles")

        assert answer.stop_reason == "end_turn"
        assert "[fake-llm]" not in text_of(answer)

    async def test_a_proaction_needs_the_thread_summaries_in_its_prompt(self):
        """The chat journey's proof that a proaction knows the conversation (MAG-14)."""
        client = build_client(DEFAULT_FIXTURES_DIR)

        without = await ask(client, "Fais le point sur sa semaine")
        assert "[fake-llm]" in text_of(without)

        # The section as `contexts.py` really renders it: a needle matched against a
        # system prompt this test invented would prove only itself.
        thread = MagicMock()
        thread.label = "Conversation e2e"
        thread.summary = "Résumé e2e : l'utilisateur organise sa semaine avec Maggie."
        thread.status = ContextStatus.ACTIVE
        with patch("app.llm.contexts.context_repo") as repo:
            repo.find_active = AsyncMock(return_value=[thread])
            section = await active_contexts_section("user-1")

        with_summary = await ask(client, "Fais le point sur sa semaine", system=f"Tu es Maggie.{section}")
        assert "[fake-llm]" not in text_of(with_summary)

    async def test_a_behaviour_preference_is_filed_as_one(self):
        """The kind decides who ever reads the directive again, so the journey asserts it (MAG-22)."""
        client = build_client(DEFAULT_FIXTURES_DIR)

        answer = await ask(client, "Tutoie-moi et évite les emojis")

        assert answer.stop_reason == "tool_use"
        stored = next(block for block in answer.content if isinstance(block, FakeToolUseBlock))
        assert stored.name == "add_instruction"
        # `planning` here would store the sentence where only the daily planning
        # reads it — the exact bug MAG-22 fixed, and invisible from the answer.
        assert stored.input == {"content": "Tutoie-moi et évite les emojis", "kind": "behavior"}

    async def test_the_voice_she_was_asked_for_needs_the_preference_in_the_prompt(self):
        """The chat journey's proof of the injection, and why it is a proof."""
        client = build_client(DEFAULT_FIXTURES_DIR)

        without = await ask(client, "Dis-moi bonjour", system="Tu es Maggie.")
        assert "[fake-llm]" in text_of(without)

        # The section as `directives.py` really renders it, header included: a needle
        # matched against a system prompt this test invented would prove only itself.
        with_preference = await ask(
            client,
            "Dis-moi bonjour",
            system=f"Tu es Maggie.{HEADER}\n- Tutoie-moi et évite les emojis",
        )
        assert "[fake-llm]" not in text_of(with_preference)
