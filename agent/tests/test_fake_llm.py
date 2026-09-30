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
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import anthropic
import pytest
import yaml

from app.db.context_model import ConversationContext
from app.llm import fake as fake_module
from app.llm.client import create_llm_client, llm_configured
from app.llm.fake import (
    DEFAULT_FIXTURES_DIR,
    FakeAnthropicClient,
    FakeTextBlock,
    FakeToolUseBlock,
    ScenarioLibrary,
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


async def ask(client: FakeAnthropicClient, message: str, *, system: str = "Tu es Maggie.", tools=None):
    return await client.messages.create(
        model="fake",
        max_tokens=1024,
        system=system,
        messages=[{"role": "user", "content": message}],
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

    async def _events(self, fixtures_dir) -> list[dict]:
        client = build_client(fixtures_dir)

        with (
            patch("app.llm.streaming.settings") as settings,
            patch("app.llm.streaming.message_repo") as message_repo,
            patch("app.llm.streaming.context_repo") as context_repo,
            patch("app.llm.streaming.record_llm_usage"),
        ):
            settings.anthropic_api_key = ""
            settings.llm_provider = "fake"
            settings.anthropic_model = "fake"
            settings.max_conversation_history = 10
            # The route persists the user message before streaming, so history
            # is where `chat_stream` finds it — returning an empty history here
            # would have the fake answering an empty question.
            persisted = MagicMock()
            persisted.role = "user"
            persisted.content = "mon agenda ?"
            message_repo.find_recent = AsyncMock(return_value=[persisted])
            message_repo.create = AsyncMock()
            message_repo.update_context = AsyncMock()
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
                return_value=[{"name": "get_upcoming_events", "input_schema": {}}]
            )
            gateway.tool_router.call_tool = AsyncMock(return_value='{"events": []}')

            return [event async for event in gateway.chat_stream("mon agenda ?", "user-1", "msg-1")]

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

    def test_an_unknown_provider_is_refused(self, monkeypatch):
        # Not treated as "not fake": `LLM_PROVIDER=Fake` would otherwise reach
        # the real API with whatever key is around, and the only clue would be a
        # journey that got slow and stopped being deterministic.
        monkeypatch.setattr("app.llm.client.settings.llm_provider", "Fake")

        with pytest.raises(ValueError, match="is not one of"):
            create_llm_client()

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
