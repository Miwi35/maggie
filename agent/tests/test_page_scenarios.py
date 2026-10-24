"""Maggie reads a page before acting on it, and proposes instead of creating (MAG-597).

Whether a real model follows the rule is judged on the real model; what this proves is the run
with the shipped scenarios: `read_web_page` is called with the address of the screen context, and
no tool that writes is ever asked for before the user's « oui ».
"""

import json
from unittest.mock import patch

import pytest

from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter

OFFERED = [{"name": name, "input_schema": {}} for name in ("read_web_page", "create_recipe", "create_event", "add_grocery_item")]
WRITES = {"create_recipe", "create_event", "add_grocery_item"}
BASE = "http://wiremock:8080/pages"


class FakeApi:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    async def ensure_connected(self) -> None:
        return None

    async def list_tools(self) -> list[dict]:
        return []

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        self.calls.append((name, dict(arguments)))
        return json.dumps({"title": "page", "text": "", "structured": []})


@pytest.fixture()
def api():
    fake = FakeApi()
    with patch("app.llm.tools.mcp_client", fake):
        yield fake


async def ask(message: str, address: str) -> dict:
    content = f"{message}\n\n[Contexte de l'écran]\nApplication : Chrome (com.android.chrome)\nPage : {address}"
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.",
            [{"role": "user", "content": content}],
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id="user-1",
            model="fake",
            source="chat",
        )


@pytest.mark.parametrize(
    ("message", "path", "proposal"),
    [
        ("Ajoute cette recette", "marmiton/boeuf-bourguignon", "6 ingrédients"),
        ("Mets ça dans mon agenda", "events/concert-jazz", "14 novembre 2026"),
        ("Ajoute ça à mes courses", "products/cafe-arabica", "14,90 €"),
    ],
)
async def test_the_page_is_read_at_its_address_then_proposed_and_nothing_is_written(api, message, path, proposal):
    result = await ask(message, f"{BASE}/{path}")

    assert api.calls == [("read_web_page", {"url": f"{BASE}/{path}"})]
    assert not WRITES & {call["name"] for call in result["tool_calls"]}
    assert proposal in result["response"]
    assert result["response"].rstrip().endswith("?"), "a proposal waits for the yes"
