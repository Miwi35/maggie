"""Replay the prompt-lab scenarios against the real model (MAG-95).

The counterpart of the fake: journeys run with `LLM_PROVIDER=fake` and assert on
plumbing, this asserts on judgement — does she pick the right tool, does she keep
the thread, does she sound like Maggie. Only the real model can be asked that, so
this runs nightly or on demand rather than on every PR, and it gates nothing.

It drives the agent over HTTP, from inside the e2e stack: real MCP, real tool
loop, seeded data, and the test login for a token. What is real is the model;
what is fixed is everything else, which is what makes a failure readable — it is
the prompt or the model, never the fixtures.

    task e2e:eval                      # all of them
    task e2e:eval -- --only agenda     # one, by name fragment

Usage inside the agent container:

    python -m evals.runner [--scenarios DIR] [--only NAME] [--report FILE]
"""

import argparse
import asyncio
import json
import logging
import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

import anthropic
import httpx
import yaml
from sqlalchemy import text

from app.config import settings
from app.db.agent_engine import agent_engine
from app.llm.client import FAKE, create_llm_client

logger = logging.getLogger("evals")

DEFAULT_SCENARIOS_DIR = Path("/scripts/prompt-lab/scenarios")
DEFAULT_API_URL = "http://nginx"
DEFAULT_AGENT_URL = "http://localhost:8001"
REQUEST_TIMEOUT = 180.0

JUDGE_SYSTEM = (
    "Tu évalues la réponse d'un assistant contre un critère, sans complaisance. "
    'Réponds UNIQUEMENT par un JSON valide : {"pass": true|false, "reason": "<une phrase>"}. '
    "Le critère décrit ce que la réponse DOIT faire. Si un seul de ses points manque, c'est false. "
    "N'évalue que le critère — pas ton goût personnel, pas la longueur."
)


# ---------------------------------------------------------------------------
# Scenarios
# ---------------------------------------------------------------------------

STEP_KEYS = (
    "user_message",
    "expected_tools",
    "forbidden_tools",
    "expected_contains",
    "expected_absent",
    "expected_context",
    "expected_behavior",
)

# `mock_tool_results` belongs to /prompt-lab, which replays a scenario through a
# Claude Code subagent and can stub a tool's answer. This runner drives the real
# stack, where the tools really run — so it accepts the key and ignores it rather
# than rejecting a file the other consumer needs.
SCENARIO_KEYS = ("name", "description", "channel", "steps", "mock_tool_results", *STEP_KEYS)


@dataclass
class Step:
    user_message: str
    expected_tools: list[str] = field(default_factory=list)
    forbidden_tools: list[str] = field(default_factory=list)
    expected_contains: list[str] = field(default_factory=list)
    expected_absent: list[str] = field(default_factory=list)
    expected_context: str | None = None
    expected_behavior: str | None = None


@dataclass
class Scenario:
    name: str
    steps: list[Step]
    channel: str = "chat"
    description: str = ""


def plural(count: int, word: str) -> str:
    return word if count == 1 else f"{word}s"


def _as_list(value) -> list[str]:
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    return [str(item) for item in value]


def parse_step(raw: dict, source: str) -> Step:
    message = raw.get("user_message")
    if not message:
        raise ValueError(f"{source}: a step needs a 'user_message'")
    unknown = set(raw) - set(STEP_KEYS)
    if unknown:
        # A typo in an expectation key is silent otherwise: the step passes
        # because nothing was asserted.
        raise ValueError(f"{source}: unknown key(s) {sorted(unknown)}")
    context = raw.get("expected_context")
    if context not in (None, "created", "matched"):
        raise ValueError(f"{source}: expected_context must be 'created' or 'matched', not {context!r}")
    return Step(
        user_message=str(message),
        expected_tools=_as_list(raw.get("expected_tools")),
        forbidden_tools=_as_list(raw.get("forbidden_tools")),
        expected_contains=_as_list(raw.get("expected_contains")),
        expected_absent=_as_list(raw.get("expected_absent")),
        expected_context=context,
        expected_behavior=raw.get("expected_behavior"),
    )


def parse_scenario(raw: dict, source: str) -> Scenario:
    if not isinstance(raw, dict):
        raise ValueError(f"{source}: a scenario file must hold a mapping")

    # Checked here as well as per step: a single-turn scenario carries its
    # expectations at the top level, and a typo there would be dropped on the
    # floor — leaving a step that runs and asserts nothing, which reads as a pass.
    unknown = set(raw) - set(SCENARIO_KEYS)
    if unknown:
        raise ValueError(f"{source}: unknown key(s) {sorted(unknown)}")

    channel = raw.get("channel", "chat")
    if channel not in ("chat", "stream"):
        raise ValueError(f"{source}: channel must be 'chat' or 'stream', not {channel!r}")

    raw_steps = raw.get("steps")
    if raw_steps:
        # One shape or the other, never half of each: a top-level expectation
        # beside `steps` is read by nobody, so accepting it would leave a
        # scenario asserting less than its author wrote.
        stray = set(raw) & set(STEP_KEYS)
        if stray:
            raise ValueError(f"{source}: {sorted(stray)} sit beside 'steps' — move them into a step")
    else:
        # The single-turn shape /prompt-lab documents: the expectations sit at
        # the top level.
        raw_steps = [{key: raw[key] for key in STEP_KEYS if key in raw}]

    steps = [parse_step(step, f"{source} step {i}") for i, step in enumerate(raw_steps, start=1)]
    if any(step.expected_context for step in steps) and channel != "stream":
        raise ValueError(f"{source}: expected_context needs 'channel: stream' — only that path has contexts")

    return Scenario(
        name=str(raw.get("name") or Path(source).stem),
        steps=steps,
        channel=channel,
        description=str(raw.get("description") or ""),
    )


def load_scenarios(directory: Path, only: str | None = None) -> list[Scenario]:
    scenarios = []
    for path in sorted(directory.glob("*.yaml")):
        scenario = parse_scenario(yaml.safe_load(path.read_text()) or {}, source=path.name)
        if only is None or only.lower() in scenario.name.lower():
            scenarios.append(scenario)
    return scenarios


# ---------------------------------------------------------------------------
# Talking to the stack
# ---------------------------------------------------------------------------


@dataclass
class Answer:
    text: str
    tools: list[str]
    context_action: str | None = None


class Stack:
    """The running e2e stack, seen from inside it."""

    def __init__(self, api_url: str, agent_url: str, client: httpx.AsyncClient):
        self.api_url = api_url.rstrip("/")
        self.agent_url = agent_url.rstrip("/")
        self.http = client
        self.token = ""

    async def login(self) -> None:
        response = await self.http.post(
            f"{self.api_url}/api/auth/e2e/login",
            headers={"X-E2E-Token": os.environ.get("E2E_LOGIN_TOKEN", "e2e-login-token")},
            json={"email": os.environ.get("E2E_SEED_EMAIL", "e2e@maggie.local")},
        )
        response.raise_for_status()
        self.token = response.json()["token"]

    @property
    def _auth(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}

    async def reset_agent_state(self) -> None:
        """Empty the agent's database, so every scenario starts from nothing.

        Everything in it is agent state — conversations, contexts, memory,
        directives, the personality override. Left in place, scenario N reads
        scenario N-1's history and the eval stops being reproducible.
        """
        async with agent_engine.begin() as connection:
            await connection.execute(
                text(
                    "DO $$ DECLARE t text; BEGIN "
                    "FOR t IN SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                    "LOOP EXECUTE format('TRUNCATE TABLE %I CASCADE', t); END LOOP; END $$;"
                )
            )

    async def chat(self, message: str) -> Answer:
        response = await self.http.post(
            f"{self.agent_url}/chat", headers=self._auth, json={"message": message}, timeout=REQUEST_TIMEOUT
        )
        response.raise_for_status()
        body = response.json()
        return Answer(text=body["response"], tools=[call["name"] for call in body.get("tool_calls", [])])

    async def stream(self, message: str) -> Answer:
        deltas: list[str] = []
        tools: list[str] = []
        context_action = None

        async with self.http.stream(
            "POST",
            f"{self.agent_url}/chat/stream",
            headers={**self._auth, "Accept": "text/event-stream"},
            json={"message": message},
            timeout=REQUEST_TIMEOUT,
        ) as response:
            response.raise_for_status()
            async for line in response.aiter_lines():
                if not line.startswith("data: "):
                    continue
                event = json.loads(line[len("data: ") :])
                if event["type"] == "TEXT_MESSAGE_CONTENT":
                    deltas.append(event["delta"])
                elif event["type"] == "TOOL_CALL_START":
                    tools.append(event["toolName"])
                elif event.get("name") == "context_update":
                    context_action = event["value"].get("action")

        return Answer(text="".join(deltas), tools=tools, context_action=context_action)


# ---------------------------------------------------------------------------
# Judging
# ---------------------------------------------------------------------------


class ModelUnreachable(Exception):
    """The suite could not run, as opposed to a prompt that regressed.

    Kept apart because the two call for opposite reactions: a bad key is
    somebody's to fix now, and reporting it as six failing scenarios is how a
    nightly stops being read.
    """


class Judge:
    def __init__(self, model: str):
        self.client = create_llm_client()
        self.model = model

    async def verdict(self, criterion: str, answer: Answer) -> tuple[bool, str]:
        prompt = (
            f"Critère :\n{criterion.strip()}\n\n"
            f"Outils appelés : {', '.join(answer.tools) or 'aucun'}\n\n"
            f"Réponse de l'assistant :\n{answer.text}"
        )
        try:
            response = await self.client.messages.create(
                model=self.model,
                max_tokens=300,
                system=JUDGE_SYSTEM,
                messages=[{"role": "user", "content": prompt}],
            )
        except (anthropic.AuthenticationError, anthropic.PermissionDeniedError) as exc:
            raise ModelUnreachable(f"the judge was refused by the API: {exc}") from exc
        except anthropic.APIConnectionError as exc:
            # Nothing reached the API at all — every remaining scenario would
            # fail the same way, which is the report `ModelUnreachable` exists
            # to prevent.
            raise ModelUnreachable(f"the API could not be reached at all: {exc}") from exc
        except anthropic.NotFoundError as exc:
            # An unknown model id. Same category: it applies to every scenario,
            # and "the judge could not be reached" six times reads like a prompt
            # regression rather than like EVAL_JUDGE_MODEL being wrong.
            raise ModelUnreachable(f"the judge model {self.model!r} does not exist: {exc}") from exc
        except anthropic.APIError as exc:
            # Rate limits, overloads, a malformed request: this one step could
            # not be verified. Never a pass by default.
            return False, f"the judge could not be reached: {exc}"

        raw = "".join(block.text for block in response.content if hasattr(block, "text")).strip()
        if raw.startswith("```"):
            raw = raw.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
        try:
            parsed = json.loads(raw)
        except json.JSONDecodeError:
            return False, f"the judge did not answer JSON: {raw[:200]}"
        return bool(parsed.get("pass")), str(parsed.get("reason", ""))


# ---------------------------------------------------------------------------
# Running
# ---------------------------------------------------------------------------


@dataclass
class Failure:
    scenario: str
    step: int
    message: str


def check_step(step: Step, answer: Answer) -> list[str]:
    """Everything a regex can settle. The judge takes the rest."""
    problems = []

    missing = [tool for tool in step.expected_tools if tool not in answer.tools]
    if missing:
        problems.append(f"tools not called: {', '.join(missing)} (called: {', '.join(answer.tools) or 'none'})")

    forbidden = [tool for tool in step.forbidden_tools if tool in answer.tools]
    if forbidden:
        problems.append(f"tools called that must not be: {', '.join(forbidden)}")

    lowered = answer.text.lower()
    absent = [needle for needle in step.expected_contains if needle.lower() not in lowered]
    if absent:
        problems.append(f"missing from the answer: {', '.join(repr(n) for n in absent)}")

    present = [needle for needle in step.expected_absent if needle.lower() in lowered]
    if present:
        problems.append(f"present in the answer and must not be: {', '.join(repr(n) for n in present)}")

    if step.expected_context and answer.context_action != step.expected_context:
        problems.append(f"context {answer.context_action or 'none'}, expected {step.expected_context}")

    return problems


async def run_scenario(scenario: Scenario, stack: Stack, judge: Judge) -> list[Failure]:
    await stack.reset_agent_state()
    failures: list[Failure] = []

    for position, step in enumerate(scenario.steps, start=1):
        ask = stack.stream if scenario.channel == "stream" else stack.chat
        try:
            answer = await ask(step.user_message)
        except httpx.HTTPError as exc:
            failures.append(Failure(scenario.name, position, f"the agent call failed: {exc}"))
            break

        for problem in check_step(step, answer):
            failures.append(Failure(scenario.name, position, problem))

        if step.expected_behavior:
            passed, reason = await judge.verdict(step.expected_behavior, answer)
            if not passed:
                failures.append(Failure(scenario.name, position, f"judged failing: {reason}"))

        called = ", ".join(answer.tools) or "none"
        print(f"    {position}. « {step.user_message[:60]} » → {len(answer.text)} chars, tools: {called}")

    return failures


def report(scenarios: list[Scenario], failures: list[Failure], model: str) -> str:
    by_scenario: dict[str, list[Failure]] = {}
    for failure in failures:
        by_scenario.setdefault(failure.scenario, []).append(failure)

    lines = [
        "## Eval suite",
        "",
        f"`{model}` · {len(scenarios)} {plural(len(scenarios), 'scenario')} · "
        f"{len(scenarios) - len(by_scenario)} passed, {len(by_scenario)} failed",
        "",
        "| Scenario | Steps | Result |",
        "|---|---|---|",
    ]
    for scenario in scenarios:
        own = by_scenario.get(scenario.name, [])
        verdict = "✅ pass" if not own else f"❌ {len(own)} problem(s)"
        lines.append(f"| `{scenario.name}` | {len(scenario.steps)} | {verdict} |")

    if failures:
        lines += ["", "### What failed", ""]
        for failure in failures:
            lines.append(f"- **{failure.scenario}**, step {failure.step} — {failure.message}")

    return "\n".join(lines) + "\n"


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenarios", type=Path, default=DEFAULT_SCENARIOS_DIR)
    parser.add_argument("--only", help="run the scenarios whose name contains this")
    parser.add_argument("--api-url", default=os.environ.get("E2E_API_URL", DEFAULT_API_URL))
    parser.add_argument("--agent-url", default=os.environ.get("E2E_AGENT_URL", DEFAULT_AGENT_URL))
    # The judge defaults to the model under test: a grader weaker than the
    # subject is worse than none, and this one is at least its equal.
    # `EVAL_JUDGE_MODEL` pins a different one when a criterion needs it.
    parser.add_argument("--judge-model", default=os.environ.get("EVAL_JUDGE_MODEL", settings.anthropic_model))
    parser.add_argument("--report", type=Path, help="write the markdown report here as well")
    parser.add_argument(
        "--check",
        action="store_true",
        help="parse the scenarios and print the plan, without calling the model",
    )
    args = parser.parse_args()

    if not args.scenarios.is_dir():
        print(f"no scenario directory at {args.scenarios}")
        return 2

    if args.check:
        try:
            scenarios = load_scenarios(args.scenarios, only=args.only)
        except (ValueError, yaml.YAMLError) as exc:
            print(f"a scenario file is not valid: {exc}")
            return 2
        for scenario in scenarios:
            count = len(scenario.steps)
            print(f"{scenario.name} ({scenario.channel}, {count} {plural(count, 'step')})")
            for position, step in enumerate(scenario.steps, start=1):
                judged = "judged" if step.expected_behavior else "unjudged"
                print(f"  {position}. « {step.user_message[:60]} » — {judged}")
        print(f"\n{len(scenarios)} scenarios parse.")
        return 0 if scenarios else 2

    def cannot_run(reason: str) -> int:
        """Every way the suite fails to start, reported the same way.

        The step summary is what gets read in the morning, so it has to say "could
        not run" rather than stay empty — an empty summary looks like a suite
        nobody ran, and a red nightly nobody can explain is a nightly nobody
        reads.
        """
        print(f"\n{reason}\nThe eval suite could not run — this is not a prompt regression.")
        if args.report:
            args.report.write_text(f"## Eval suite\n\n**Could not run.** {reason}\n")
        return 2

    # The whole point is the real model, so refuse rather than produce a green
    # run that proved nothing.
    if settings.llm_provider == FAKE:
        return cannot_run("LLM_PROVIDER=fake: the eval suite needs the real model. Run it through `task e2e:eval`.")
    if not settings.anthropic_api_key:
        return cannot_run("ANTHROPIC_API_KEY is empty: the eval suite has no model to call.")

    logging.basicConfig(level=logging.WARNING, format="%(levelname)s:%(name)s: %(message)s")

    try:
        scenarios = load_scenarios(args.scenarios, only=args.only)
    except (ValueError, yaml.YAMLError) as exc:
        return cannot_run(f"a scenario file is not valid: {exc}")
    if not scenarios:
        return cannot_run(f"no scenario matched in {args.scenarios}")

    print(
        f"Eval suite: {len(scenarios)} {plural(len(scenarios), 'scenario')} "
        f"on {settings.anthropic_model}, judged by {args.judge_model}\n"
    )

    failures: list[Failure] = []
    async with httpx.AsyncClient(timeout=30.0) as http:
        stack = Stack(args.api_url, args.agent_url, http)
        try:
            await stack.login()
        except httpx.HTTPError as exc:
            return cannot_run(f"the test login failed — is the stack up and seeded? {exc}")
        judge = Judge(args.judge_model)

        for scenario in scenarios:
            print(f"  {scenario.name} ({scenario.channel})")
            try:
                failures.extend(await run_scenario(scenario, stack, judge))
            except ModelUnreachable as exc:
                return cannot_run(str(exc))

    markdown = report(scenarios, failures, settings.anthropic_model)
    print("\n" + markdown)
    if args.report:
        args.report.write_text(markdown)

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
