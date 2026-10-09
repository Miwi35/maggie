"""Coverage per e2e journey (spec « Sélection e2e par couverture », part A).

What has to hold for the nightly's map to be right: the journey of a request reaches every line
it runs — the chat turn's background task included — never another journey's, travels on to the
API, and comes out in the raw file format the map is built from. And with `E2E_COVERAGE` unset,
nothing of it exists.
"""

import asyncio
import json
import os
import subprocess
import sys
import textwrap
from pathlib import Path

import httpx
import pytest
import respx
from coverage import CoverageData
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.e2e_coverage import HEADER, JourneyMiddleware, current_journey, journey_headers, setup_journey_coverage, slug
from app.e2e_coverage.export import collect, main, write
from app.mcp.client import McpClient

CHAT = "e2e/web/tests/chat.spec.ts"
GROCERY = "e2e/mobile/flows/20-grocery.yaml"
AGENT_DIR = Path(__file__).resolve().parent.parent


def recording_app() -> tuple[FastAPI, dict]:
    """An app whose endpoint reports the journey it sees, and the one its background task sees."""
    seen: dict = {}
    app = FastAPI()

    @app.get("/probe")
    async def probe() -> dict:
        async def turn() -> None:
            await asyncio.sleep(0)
            seen["task"] = current_journey.get()

        await asyncio.create_task(turn())
        seen["request"] = current_journey.get()
        return {}

    app.add_middleware(JourneyMiddleware)
    return app, seen


class TestJourneyMiddleware:
    def test_the_header_reaches_the_request_and_the_tasks_it_starts(self):
        app, seen = recording_app()

        TestClient(app).get("/probe", headers={HEADER: CHAT})

        assert seen == {"request": CHAT, "task": CHAT}

    def test_no_header_means_no_journey(self):
        app, seen = recording_app()

        TestClient(app).get("/probe")

        assert seen == {"request": None, "task": None}

    @pytest.mark.parametrize("value", ["../../etc/passwd", "chat spec", "e2e/web/<script>", "x" * 201])
    def test_a_value_that_is_not_a_repo_path_is_ignored(self, value: str):
        app, seen = recording_app()

        TestClient(app).get("/probe", headers={HEADER: value})

        assert seen["request"] is None

    def test_the_journey_does_not_leak_past_the_request(self):
        app, _ = recording_app()

        TestClient(app).get("/probe", headers={HEADER: CHAT})

        assert current_journey.get() is None


class TestSetup:
    def test_nothing_is_installed_without_e2e_coverage(self, monkeypatch):
        monkeypatch.delenv("E2E_COVERAGE", raising=False)
        app = FastAPI()

        setup_journey_coverage(app)

        assert app.user_middleware == []

    def test_the_middleware_is_installed_with_e2e_coverage(self, monkeypatch):
        monkeypatch.setenv("E2E_COVERAGE", "1")
        app = FastAPI()

        setup_journey_coverage(app)

        assert [m.cls for m in app.user_middleware] == [JourneyMiddleware]


class TestPropagationToTheApi:
    def test_journey_headers_is_empty_outside_a_journey(self):
        assert journey_headers() == {}

    @respx.mock
    async def test_a_tool_call_carries_the_journey(self):
        route = respx.post("http://nginx/_mcp").mock(
            return_value=httpx.Response(200, json={"jsonrpc": "2.0", "id": 1, "result": {"content": []}})
        )
        client = McpClient()
        client.server_url = "http://nginx/_mcp"
        client._http_client = httpx.AsyncClient()

        token = current_journey.set(CHAT)
        try:
            await client.call_tool("list_events", {}, user_id="u1")
        finally:
            current_journey.reset(token)
        await client.call_tool("list_events", {}, user_id="u1")

        assert route.calls[0].request.headers[HEADER] == CHAT
        assert HEADER not in route.calls[1].request.headers


class TestExport:
    def test_slug_follows_the_contract(self):
        assert slug(CHAT) == "e2e_web_tests_chat_spec_ts"

    def test_lines_are_grouped_by_journey_with_repo_paths(self, tmp_path: Path):
        data_dir = tmp_path / "data"
        data_dir.mkdir()
        for suffix, journey, lines in (("a", CHAT, [3, 1, 2]), ("b", CHAT, [2, 9]), ("c", GROCERY, [5])):
            data = CoverageData(basename=str(data_dir / f".coverage.{suffix}"))
            data.set_context(journey)
            data.add_lines({"/app/app/api/routes.py": lines})
            data.write()
        # Start-up, outside any journey, and a file outside the agent: neither belongs anywhere.
        other = CoverageData(basename=str(data_dir / ".coverage.d"))
        other.set_context("")
        other.add_lines({"/app/app/main.py": [1]})
        other.set_context(CHAT)
        other.add_lines({"/usr/local/lib/python3.12/site-packages/fastapi/applications.py": [10]})
        other.write()

        out = tmp_path / "raw"
        (out).mkdir()
        (out / "renamed_journey.json").write_text("{}")
        main(["--data-dir", str(data_dir), "--out", str(out), "--root", "/app", "--prefix", "agent"])

        assert sorted(p.name for p in out.iterdir()) == ["e2e_mobile_flows_20-grocery_yaml.json", "e2e_web_tests_chat_spec_ts.json"]
        assert json.loads((out / "e2e_web_tests_chat_spec_ts.json").read_text()) == {
            "journey": CHAT,
            "files": {"agent/app/api/routes.py": [1, 2, 3, 9]},
        }
        assert json.loads((out / "e2e_mobile_flows_20-grocery_yaml.json").read_text()) == {
            "journey": GROCERY,
            "files": {"agent/app/api/routes.py": [5]},
        }

    def test_a_partial_data_file_does_not_stop_the_others(self, tmp_path: Path):
        (tmp_path / ".coverage.broken").write_text("not a database")
        data = CoverageData(basename=str(tmp_path / ".coverage.ok"))
        data.set_context(CHAT)
        data.add_lines({"/app/app/x.py": [4]})
        data.write()

        assert write(collect(tmp_path, "/app", "agent"), tmp_path / "raw") == 1


# Two journeys at once, as Playwright runs them: each task sleeps between its two lines, so the
# other one runs in between. A global context would label the second line of one with the other.
CONCURRENT_JOURNEYS = textwrap.dedent(
    """
    import asyncio
    import coverage

    cov = coverage.Coverage(data_file={data_file!r}, source=[{source!r}], config_file=False)
    cov.set_option("run:plugins", ["app.e2e_coverage.plugin"])
    cov.start()

    from app.e2e_coverage import current_journey
    import work

    async def journey(name, fn):
        current_journey.set(name)
        await asyncio.create_task(fn())

    async def run():
        await asyncio.gather(journey({chat!r}, work.chat), journey({grocery!r}, work.grocery))
        await work.untracked()

    asyncio.run(run())
    cov.stop()
    cov.save()
    """
)

WORK = textwrap.dedent(
    """
    import asyncio


    async def chat():
        a = 1
        await asyncio.sleep(0.01)
        return a


    async def grocery():
        b = 2
        await asyncio.sleep(0.01)
        return b


    async def untracked():
        return 3
    """
)


def test_coverage_attributes_each_task_to_its_own_journey(tmp_path: Path):
    source = tmp_path / "src"
    source.mkdir()
    (source / "work.py").write_text(WORK)
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    script = tmp_path / "run.py"
    script.write_text(
        CONCURRENT_JOURNEYS.format(
            data_file=str(data_dir / ".coverage"), source=str(source), chat=CHAT, grocery=GROCERY
        )
    )
    env = {k: v for k, v in os.environ.items() if not k.startswith("COV_")}
    env.update(COVERAGE_CORE="ctrace", PYTHONPATH=f"{source}{os.pathsep}{AGENT_DIR}")

    subprocess.run([sys.executable, str(script)], check=True, env=env, cwd=AGENT_DIR, timeout=60)

    journeys = collect(data_dir, str(tmp_path), "agent")
    # Lines 6–8 are chat's body, 12–14 grocery's (WORK starts with a blank line); 18 runs outside both.
    assert journeys[CHAT] == {"agent/src/work.py": {6, 7, 8}}
    assert journeys[GROCERY] == {"agent/src/work.py": {12, 13, 14}}
