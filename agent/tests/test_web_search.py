"""Anthropic's server-side web search and the sources it cites (MAG-384)."""

from types import SimpleNamespace
from zoneinfo import ZoneInfo
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.agents.delegate import handle_delegate
from app.agents.registry import SubagentDefinition, SubagentRegistry, parse_subagent
from app.config import settings
from app.llm.fake import FakeAnthropicClient, FakeCitation, FakeCitedTextBlock, parse_scenario
from app.llm.runner import run_tool_loop
from app.llm.web_search import (
    MAX_SOURCES,
    answer_text,
    cited_sources,
    missing_sources,
    search_failed,
    sources_line,
    web_search_tool,
    with_sources,
    with_web_search,
)

PAGE = "https://www.education.gouv.fr/le-calendrier-scolaire-2026-2027"
OTHER = "https://www.service-public.fr/particuliers/vosdroits/F1"


def _cited(text: str, *urls: str) -> dict:
    return {
        "type": "text",
        "text": text,
        "citations": [{"type": "web_search_result_location", "url": url, "title": "t"} for url in urls],
    }


def _searched_content(*cited_blocks: dict) -> list[dict]:
    return [
        {"type": "text", "text": "Je cherche. "},
        {"type": "server_tool_use", "id": "srvtoolu_1", "name": "web_search", "input": {"query": "q"}},
        {"type": "web_search_tool_result", "tool_use_id": "srvtoolu_1", "content": []},
        *cited_blocks,
    ]


class TestTheTool:
    def test_it_is_anthropics_server_tool_with_a_ceiling(self):
        tool = web_search_tool(ZoneInfo("Europe/Paris"))

        assert tool == {
            "type": "web_search_20250305",
            "name": "web_search",
            "max_uses": settings.web_search_max_uses,
            "user_location": {"type": "approximate", "country": "FR", "timezone": "Europe/Paris"},
        }

    def test_the_location_needs_no_timezone(self):
        assert web_search_tool()["user_location"] == {"type": "approximate", "country": "FR"}

    def test_zero_uses_switches_the_web_off(self):
        with patch.object(settings, "web_search_max_uses", 0):
            assert web_search_tool() is None
            assert with_web_search([{"name": "search"}]) == [{"name": "search"}]

    def test_it_comes_first_so_the_cache_breakpoint_stays_on_an_ordinary_tool(self):
        tools = with_web_search([{"name": "a"}, {"name": "b"}])

        assert [t["name"] for t in tools] == ["web_search", "a", "b"]


class TestTheAnswerAndItsSources:
    def test_the_answer_is_the_text_after_the_last_search(self):
        content = _searched_content(_cited("Du 17 octobre ", PAGE), _cited("au 1er novembre.", PAGE))

        assert answer_text(content) == "Du 17 octobre au 1er novembre."

    def test_without_a_search_the_answer_is_all_of_the_text(self):
        assert answer_text([{"type": "text", "text": "Bonjour "}, {"type": "text", "text": "!"}]) == "Bonjour !"

    def test_sources_are_listed_once_with_their_domain_in_citation_order(self):
        content = _searched_content(_cited("a", PAGE, OTHER), _cited("b", PAGE))

        assert cited_sources(content) == [("education.gouv.fr", PAGE), ("service-public.fr", OTHER)]

    def test_the_line_is_one_line_with_domain_and_link(self):
        content = _searched_content(_cited("a", PAGE, OTHER))

        assert sources_line(content) == f"Sources : education.gouv.fr — {PAGE} · service-public.fr — {OTHER}"

    def test_one_source_is_singular(self):
        assert sources_line(_searched_content(_cited("a", PAGE))) == f"Source : education.gouv.fr — {PAGE}"

    def test_the_line_stays_short(self):
        urls = [f"https://site{n}.fr/page" for n in range(MAX_SOURCES + 3)]

        assert len(cited_sources([_cited("a", *urls)])) == MAX_SOURCES

    def test_no_citation_no_line(self):
        content = _searched_content({"type": "text", "text": "Rien de fiable."})

        assert sources_line(content) == ""
        assert with_sources("Rien de fiable.", content) == "Rien de fiable."

    def test_the_answer_is_closed_by_the_line(self):
        content = _searched_content(_cited("Du 17 octobre.", PAGE))

        assert with_sources("Du 17 octobre.\n", content) == f"Du 17 octobre.\n\nSource : education.gouv.fr — {PAGE}"

    @pytest.mark.parametrize("written", ["Source : education.gouv.fr — https://a.fr/x", "Sources : a.fr — https://a.fr/x · b.fr — https://b.fr/y\n"])
    def test_a_line_the_model_wrote_or_took_from_the_researcher_is_not_added_twice(self, written):
        content = _searched_content(_cited("a", PAGE))
        text = f"Du 17 octobre.\n\n{written}"

        assert with_sources(text, content) == text
        assert missing_sources(text, content) == ""

    def test_a_source_mentioned_inside_the_text_does_not_count_as_the_line(self):
        content = _searched_content(_cited("a", PAGE))

        assert with_sources("La Source : un film.\nPuis la suite.", content).endswith(f"Source : education.gouv.fr — {PAGE}")

    def test_it_reads_the_sdk_objects_as_well_as_plain_dicts(self):
        citation = SimpleNamespace(type="web_search_result_location", url=PAGE, title="t")
        block = SimpleNamespace(type="text", text="a", citations=[citation])

        assert cited_sources([block]) == [("education.gouv.fr", PAGE)]

    def test_a_search_error_is_read_off_its_result_block(self):
        error = {"type": "web_search_tool_result", "content": {"type": "web_search_tool_result_error"}}

        assert search_failed(error)
        assert not search_failed({"type": "web_search_tool_result", "content": []})


class TestTheToolLoop:
    @staticmethod
    def _response(content, stop_reason="end_turn"):
        response = MagicMock()
        response.content = content
        response.stop_reason = stop_reason
        response.usage.input_tokens = 1
        response.usage.output_tokens = 1
        return response

    async def _run(self, responses, tools=None):
        client = MagicMock()
        client.messages.create = AsyncMock(side_effect=responses)
        with patch("app.llm.runner.record_llm_usage"):
            result = await run_tool_loop(
                "system",
                [{"role": "user", "content": "Toussaint ?"}],
                tools,
                client=client,
                tool_router=MagicMock(),
                user_id="user-1",
                model="claude-test",
            )
        return result, client

    async def test_the_request_carries_the_web_search_with_max_uses(self):
        _, client = await self._run([self._response([{"type": "text", "text": "ok"}])], with_web_search([{"name": "search"}]))

        sent = client.messages.create.await_args.kwargs["tools"]
        assert sent[0]["name"] == "web_search"
        assert sent[0]["max_uses"] == settings.web_search_max_uses

    async def test_the_answer_cites_its_sources(self):
        content = _searched_content(_cited("Du 17 octobre au 1er novembre.", PAGE))

        result, _ = await self._run([self._response(content)])

        assert result["response"] == f"Du 17 octobre au 1er novembre.\n\nSource : education.gouv.fr — {PAGE}"

    async def test_a_paused_search_is_sent_back_to_go_on(self):
        paused = self._response([_searched_content()[1]], stop_reason="pause_turn")
        done = self._response(_searched_content(_cited("Voilà.", PAGE)))

        result, client = await self._run([paused, done])

        assert client.messages.create.await_count == 2
        second = client.messages.create.await_args.kwargs["messages"]
        assert second[-1] == {"role": "assistant", "content": paused.content}
        assert result["response"].startswith("Voilà.")


class TestTheResearcher:
    def test_the_shipped_researcher_has_the_web_and_says_which_tool_is_which(self):
        from pathlib import Path

        definition = parse_subagent(Path(__file__).resolve().parent.parent / "data" / "agents" / "researcher.md")

        assert "web_search" in definition.tools
        assert "search*" in definition.tools
        assert "Internet" in definition.prompt
        assert "données de Maggie" in definition.prompt
        assert "ajoutée à ta réponse automatiquement" in definition.prompt

    async def test_the_subagent_loop_is_sent_the_web_search_with_max_uses(self):
        researcher = SubagentDefinition(
            name="researcher",
            description="Recherche",
            model="haiku",
            tools=["search*", "web_search"],
            prompt="Tu cherches.",
        )
        registry = SubagentRegistry(agents={"researcher": researcher})
        router = MagicMock()
        router.get_tool_definitions = AsyncMock(
            return_value=[{"name": "search", "description": "", "input_schema": {"type": "object"}}]
        )
        result = {"response": "Du 17 octobre.", "tool_calls": []}
        with (
            patch("app.agents.delegate.subagent_registry", registry),
            patch("app.agents.delegate.ToolRouter", return_value=router),
            patch("app.agents.delegate.run_tool_loop", AsyncMock(return_value=result)) as loop,
            patch("app.agents.delegate._llm_client", return_value="client"),
            patch("app.agents.delegate.llm_configured", return_value=True),
            patch("app.agents.delegate.resolve_user_timezone", AsyncMock(return_value=ZoneInfo("Europe/Paris"))),
            patch("app.agents.delegate.AgentMemory") as memory,
        ):
            memory.return_value.get_memory_context = AsyncMock(return_value="")
            await handle_delegate({"agent": "researcher", "task": "Vacances de la Toussaint"}, "user-1")

        tools = loop.await_args.args[2]
        assert [t["name"] for t in tools] == ["web_search", "search"]
        assert tools[0]["max_uses"] == settings.web_search_max_uses


class TestTheFake:
    def test_a_scenario_scripts_a_search_and_what_it_cites(self):
        scenario = parse_scenario(
            {
                "match": {"user_contains": "x"},
                "turns": [
                    {
                        "text": "Réponse.",
                        "web_search": {"query": "q", "results": [{"title": "T", "url": PAGE}]},
                    }
                ],
            },
            "s.yaml",
        )

        search = scenario.turns[0].web_search
        assert search.query == "q"
        assert search.cited == (("T", PAGE),)

    def test_a_search_needs_a_query(self):
        with pytest.raises(ValueError, match="query"):
            parse_scenario({"turns": [{"text": "a", "web_search": {"results": []}}]}, "s.yaml")

    async def test_the_shipped_scenario_drives_the_real_loop_to_an_answer_with_its_source(self):
        client = FakeAnthropicClient()
        with patch("app.llm.runner.record_llm_usage"):
            result = await run_tool_loop(
                "system",
                [{"role": "user", "content": "Quelles sont les dates des vacances de la Toussaint 2026 à Rennes ?"}],
                with_web_search([]),
                client=client,
                tool_router=MagicMock(),
                user_id="user-1",
                model="fake",
            )

        assert "17 octobre" in result["response"]
        assert result["response"].splitlines()[-1].startswith("Source : education.gouv.fr — https://")

    async def test_a_search_that_finds_nothing_says_so_without_a_source(self):
        client = FakeAnthropicClient()
        with patch("app.llm.runner.record_llm_usage"):
            result = await run_tool_loop(
                "system",
                [{"role": "user", "content": "horaires de la fanfare introuvable ?"}],
                with_web_search([]),
                client=client,
                tool_router=MagicMock(),
                user_id="user-1",
                model="fake",
            )

        assert "rien trouvé de fiable" in result["response"]
        assert "Source" not in result["response"]
        assert "accès" not in result["response"]

    def test_the_cited_block_carries_its_pages(self):
        block = FakeCitedTextBlock(text="a", citations=[FakeCitation(url=PAGE, title="t")])

        assert cited_sources([block]) == [("education.gouv.fr", PAGE)]
