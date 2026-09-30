"""Tests for the eval suite's runner (MAG-95).

The suite itself calls the real model, so it cannot run here. What can, and what
matters most, is everything around that call: a scenario file has to be read the
way its author meant, an expectation has to be able to fail, and a typo in an
expectation key must not turn into a step that quietly asserts nothing.
"""

import json
from unittest.mock import AsyncMock, MagicMock

import anthropic
import httpx
import pytest
import yaml

from evals.runner import (
    Answer,
    Failure,
    Judge,
    ModelUnreachable,
    Scenario,
    Stack,
    Step,
    check_step,
    load_scenarios,
    parse_scenario,
    report,
)


class TestParsingScenarios:
    def test_the_single_turn_shape_prompt_lab_documents(self):
        scenario = parse_scenario(
            {
                "name": "agenda",
                "user_message": "mon agenda ?",
                "expected_tools": ["get_upcoming_events"],
                "expected_behavior": "Elle lit l'agenda.",
            },
            source="agenda.yaml",
        )

        assert scenario.channel == "chat"
        assert len(scenario.steps) == 1
        assert scenario.steps[0].expected_tools == ["get_upcoming_events"]

    def test_the_multi_turn_shape(self):
        scenario = parse_scenario(
            {
                "name": "suite",
                "channel": "stream",
                "steps": [
                    {"user_message": "un", "expected_context": "created"},
                    {"user_message": "deux", "expected_context": "matched"},
                ],
            },
            source="suite.yaml",
        )

        assert [step.expected_context for step in scenario.steps] == ["created", "matched"]

    def test_a_typo_in_an_expectation_key_is_refused(self):
        # Otherwise the step runs and asserts nothing, which reads as a pass.
        with pytest.raises(ValueError, match="unknown key"):
            parse_scenario(
                {"name": "x", "user_message": "salut", "expected_tool": ["get_tasks"]},
                source="x.yaml",
            )

    def test_a_typo_at_the_top_level_is_refused(self):
        # The single-turn shape reads its expectations from the top level, so a
        # typo there is dropped on the floor rather than caught per step.
        with pytest.raises(ValueError, match="unknown key"):
            parse_scenario({"name": "x", "user_message": "salut", "expected_absents": ["a"]}, source="x.yaml")

    def test_an_expectation_beside_steps_is_refused(self):
        # Read by nobody: with `steps` present the top level is ignored, so
        # accepting this would leave a scenario asserting less than it says.
        with pytest.raises(ValueError, match="move them into a step"):
            parse_scenario(
                {
                    "name": "x",
                    "expected_tools": ["get_tasks"],
                    "steps": [{"user_message": "salut"}],
                },
                source="x.yaml",
            )

    def test_prompt_labs_own_key_is_accepted_and_ignored(self):
        # /prompt-lab can stub a tool's answer; this runner drives the real
        # stack. Rejecting the key would break the other consumer's files.
        scenario = parse_scenario(
            {"name": "x", "user_message": "salut", "mock_tool_results": {"get_tasks": "[]"}},
            source="x.yaml",
        )

        assert len(scenario.steps) == 1

    def test_a_step_needs_a_message(self):
        with pytest.raises(ValueError, match="needs a 'user_message'"):
            parse_scenario({"name": "x", "steps": [{"expected_tools": ["a"]}]}, source="x.yaml")

    def test_an_unknown_channel_is_refused(self):
        with pytest.raises(ValueError, match="channel must be"):
            parse_scenario({"name": "x", "channel": "voice", "user_message": "salut"}, source="x.yaml")

    def test_an_unknown_context_expectation_is_refused(self):
        with pytest.raises(ValueError, match="expected_context must be"):
            parse_scenario(
                {"name": "x", "channel": "stream", "user_message": "salut", "expected_context": "reused"},
                source="x.yaml",
            )

    def test_expecting_a_context_needs_the_streamed_channel(self):
        # Contexts only exist on `/chat/stream`; asserting on one over `/chat`
        # would fail for a reason that has nothing to do with the prompt.
        with pytest.raises(ValueError, match="channel: stream"):
            parse_scenario(
                {"name": "x", "user_message": "salut", "expected_context": "created"},
                source="x.yaml",
            )

    def test_only_filters_by_name_fragment(self, tmp_path):
        for name in ("10-agenda.yaml", "20-courses.yaml"):
            (tmp_path / name).write_text(
                yaml.safe_dump({"name": name.removesuffix(".yaml"), "user_message": "salut"})
            )

        assert [s.name for s in load_scenarios(tmp_path)] == ["10-agenda", "20-courses"]
        assert [s.name for s in load_scenarios(tmp_path, only="courses")] == ["20-courses"]


class TestCheckingAStep:
    def test_a_step_expecting_nothing_passes(self):
        assert check_step(Step(user_message="salut"), Answer(text="Bonjour", tools=[])) == []

    def test_a_tool_that_was_not_called(self):
        problems = check_step(
            Step(user_message="mon agenda ?", expected_tools=["get_upcoming_events"]),
            Answer(text="Vous avez un déjeuner.", tools=[]),
        )

        assert len(problems) == 1
        assert "get_upcoming_events" in problems[0]
        # The worst failure Maggie can have: confirming without acting. The
        # message has to say what she did call.
        assert "none" in problems[0]

    def test_a_tool_that_must_not_be_called(self):
        problems = check_step(
            Step(user_message="tous les matins", forbidden_tools=["schedule_proaction"]),
            Answer(text="C'est noté.", tools=["add_instruction", "schedule_proaction"]),
        )

        assert len(problems) == 1
        assert "schedule_proaction" in problems[0]

    def test_substrings_are_matched_case_insensitively(self):
        step = Step(user_message="x", expected_contains=["ALEX"], expected_absent=["Pas D'accès"])

        assert check_step(step, Answer(text="Déjeuner avec Alex", tools=[])) == []
        assert len(check_step(step, Answer(text="je n'ai pas d'accès", tools=[]))) == 2

    def test_a_context_that_was_not_reused(self):
        problems = check_step(
            Step(user_message="et demain ?", expected_context="matched"),
            Answer(text="…", tools=[], context_action="created"),
        )

        assert problems == ["context created, expected matched"]

    def test_every_problem_of_a_step_is_reported_not_just_the_first(self):
        problems = check_step(
            Step(user_message="x", expected_tools=["a"], expected_contains=["b"], expected_absent=["c"]),
            Answer(text="c", tools=[]),
        )

        assert len(problems) == 3


class TestTheJudge:
    def _judge(self, *, raw: str | None = None, raises: Exception | None = None) -> Judge:
        judge = Judge.__new__(Judge)
        judge.model = "claude-test"
        judge.client = MagicMock()
        judge.client.messages = MagicMock()
        if raises is not None:
            judge.client.messages.create = AsyncMock(side_effect=raises)
        else:
            block = MagicMock()
            block.text = raw
            response = MagicMock()
            response.content = [block]
            judge.client.messages.create = AsyncMock(return_value=response)
        return judge

    async def _verdict(self, raw: str) -> tuple[bool, str]:
        return await self._judge(raw=raw).verdict("Elle répond en français.", Answer(text="Bonjour", tools=[]))

    async def test_a_pass(self):
        assert await self._verdict('{"pass": true, "reason": "en français"}') == (True, "en français")

    async def test_a_fail(self):
        passed, reason = await self._verdict('{"pass": false, "reason": "en anglais"}')
        assert (passed, reason) == (False, "en anglais")

    async def test_markdown_fenced_json_is_unwrapped(self):
        passed, _ = await self._verdict('```json\n{"pass": true, "reason": "ok"}\n```')
        assert passed is True

    async def test_a_judge_that_does_not_answer_json_fails_the_step(self):
        # Never a pass by default: an unreadable verdict is a failure to verify,
        # and treating it as success is how a suite goes quietly green.
        passed, reason = await self._verdict("Oui, la réponse est correcte.")
        assert passed is False
        assert "did not answer JSON" in reason

    async def test_a_rate_limit_fails_the_step_it_could_not_verify(self):
        error = anthropic.RateLimitError(
            "slow down",
            response=httpx.Response(429, request=httpx.Request("POST", "https://api.anthropic.com")),
            body=None,
        )

        passed, reason = await self._judge(raises=error).verdict("x", Answer(text="y", tools=[]))

        assert passed is False
        assert "could not be reached" in reason

    async def test_an_unknown_judge_model_stops_the_suite(self):
        # EVAL_JUDGE_MODEL being wrong applies to every scenario, so reporting it
        # per step would read as a prompt regression.
        error = anthropic.NotFoundError(
            "model not found",
            response=httpx.Response(404, request=httpx.Request("POST", "https://api.anthropic.com")),
            body=None,
        )

        with pytest.raises(ModelUnreachable, match="does not exist"):
            await self._judge(raises=error).verdict("x", Answer(text="y", tools=[]))

    async def test_an_unreachable_api_stops_the_suite(self):
        # Every remaining scenario would fail the same way, so a report full of
        # "judged failing" would say the prompt regressed when nothing did.
        error = anthropic.APIConnectionError(request=httpx.Request("POST", "https://api.anthropic.com"))

        with pytest.raises(ModelUnreachable, match="could not be reached at all"):
            await self._judge(raises=error).verdict("x", Answer(text="y", tools=[]))

    async def test_a_refused_key_stops_the_suite_instead_of_failing_every_scenario(self):
        # A bad key is not a prompt regression, and reporting it as six failing
        # scenarios is how a nightly stops being read.
        error = anthropic.AuthenticationError(
            "API key is invalid.",
            response=httpx.Response(401, request=httpx.Request("POST", "https://api.anthropic.com")),
            body=None,
        )

        with pytest.raises(ModelUnreachable, match="refused by the API"):
            await self._judge(raises=error).verdict("x", Answer(text="y", tools=[]))


class TestDrivingTheAgent:
    """`Stack` against a stubbed transport: the shapes it reads off the agent."""

    def _stack(self, handler) -> Stack:
        client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        stack = Stack("http://api", "http://agent", client)
        stack.token = "jwt"
        return stack

    async def test_login_keeps_the_token(self):
        def handler(request: httpx.Request) -> httpx.Response:
            assert request.headers["X-E2E-Token"]
            assert json.loads(request.content)["email"].endswith("@maggie.local")
            return httpx.Response(200, json={"token": "a-jwt"})

        stack = self._stack(handler)
        stack.token = ""
        await stack.login()

        assert stack.token == "a-jwt"

    async def test_chat_reads_the_answer_and_the_tools(self):
        def handler(request: httpx.Request) -> httpx.Response:
            assert request.headers["Authorization"] == "Bearer jwt"
            return httpx.Response(
                200,
                json={
                    "response": "Vous avez un déjeuner.",
                    "tool_calls": [{"name": "get_upcoming_events", "input": {}, "result": "[]"}],
                },
            )

        answer = await self._stack(handler).chat("mon agenda ?")

        assert answer.text == "Vous avez un déjeuner."
        assert answer.tools == ["get_upcoming_events"]
        assert answer.context_action is None

    async def test_the_stream_is_reassembled_from_its_events(self):
        events = [
            {"type": "RUN_STARTED", "runId": "r1"},
            {"type": "CUSTOM", "name": "context_update", "value": {"action": "matched", "id": "c1"}},
            {"type": "TOOL_CALL_START", "toolCallId": "t1", "toolName": "get_upcoming_events"},
            {"type": "TOOL_CALL_END", "toolCallId": "t1", "toolName": "get_upcoming_events"},
            {"type": "TEXT_MESSAGE_START", "messageId": "m1", "role": "assistant"},
            {"type": "TEXT_MESSAGE_CONTENT", "messageId": "m1", "delta": "Vous avez "},
            {"type": "TEXT_MESSAGE_CONTENT", "messageId": "m1", "delta": "un déjeuner."},
            {"type": "TEXT_MESSAGE_END", "messageId": "m1"},
            {"type": "RUN_FINISHED", "runId": "r1"},
        ]
        body = "".join(f"data: {json.dumps(event)}\n\n" for event in events)

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, text=body, headers={"Content-Type": "text/event-stream"})

        answer = await self._stack(handler).stream("mon agenda ?")

        assert answer.text == "Vous avez un déjeuner."
        assert answer.tools == ["get_upcoming_events"]
        assert answer.context_action == "matched"


class TestTheReport:
    def _scenarios(self) -> list[Scenario]:
        return [
            Scenario(name="agenda", steps=[Step(user_message="a"), Step(user_message="b")]),
            Scenario(name="courses", steps=[Step(user_message="c")]),
        ]

    def test_all_green(self):
        markdown = report(self._scenarios(), [], "claude-test")

        assert "2 scenarios · 2 passed, 0 failed" in markdown
        assert "What failed" not in markdown

    def test_a_failure_names_its_scenario_and_step(self):
        failures = [Failure("courses", 1, "tools not called: add_grocery_item (called: none)")]

        markdown = report(self._scenarios(), failures, "claude-test")

        assert "1 passed, 1 failed" in markdown
        assert "`agenda` | 2 | ✅ pass" in markdown
        assert "`courses` | 1 | ❌ 1 problem(s)" in markdown
        assert "**courses**, step 1 — tools not called: add_grocery_item (called: none)" in markdown
