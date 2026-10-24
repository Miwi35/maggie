"""Maggie structures a recipe — read, pasted or dictated — and creates it only after the « oui » (MAG-598).

Whether a real model cuts a recipe well is judged on the real model; what this proves is the run
with the shipped scenarios: the proposal comes first and writes nothing, the « oui » makes one
`create_recipe` call carrying everything explicitly, and a correction creates nothing.
"""

import json
from unittest.mock import patch

import pytest

from app.llm.fake import DEFAULT_FIXTURES_DIR, FakeAnthropicClient
from app.llm.runner import run_tool_loop
from app.llm.tools import ToolRouter

OFFERED = [
    {"name": name, "input_schema": {}}
    for name in ("read_web_page", "search_ingredients", "create_recipe", "manage_utensils")
]
URL = "http://wiremock:8080/pages/marmiton/boeuf-bourguignon"
SCREEN = f"\n\n[Contexte de l'écran]\nApplication : Chrome (com.android.chrome)\nPage : {URL}"

PASTED = """Pâtes aux légumes pour 4 personnes
Ingrédients : 200 g de pâtes, 1 courgette, 1 poivron.
1. Mettre l'eau à bouillir, 10 minutes.
2. Couper les légumes.
3. Cuire les pâtes 12 minutes une fois l'eau bouillante.
4. Faire revenir les légumes 8 minutes.
5. Égoutter les pâtes et mélanger."""
DICTATED = (
    "alors c'est mes pâtes aux légumes pour quatre personnes, deux cents grammes de pâtes, une courgette, "
    "un poivron, je mets l'eau à bouillir dix minutes, pendant ce temps je coupe les légumes, ensuite je "
    "cuis les pâtes douze minutes, je fais revenir les légumes huit minutes et j'égoutte le tout"
)


class FakeApi:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    async def ensure_connected(self) -> None:
        return None

    async def list_tools(self) -> list[dict]:
        return []

    async def call_tool(self, name: str, arguments: dict, user_id: str | None = None) -> str:
        self.calls.append((name, dict(arguments)))
        return json.dumps({"title": "page", "text": "", "structured": [], "success": True})


@pytest.fixture()
def api():
    fake = FakeApi()
    with patch("app.llm.tools.mcp_client", fake):
        yield fake


async def talk(messages: list[dict]) -> dict:
    with patch("app.llm.runner.record_llm_usage"):
        return await run_tool_loop(
            "Tu es Maggie.",
            messages,
            OFFERED,
            client=FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR),
            tool_router=ToolRouter(),
            user_id="user-1",
            model="fake",
            source="chat",
        )


async def propose_then_answer(first: str, answer: str) -> tuple[dict, dict]:
    proposal = await talk([{"role": "user", "content": first}])
    reply = await talk(
        [
            {"role": "user", "content": first},
            {"role": "assistant", "content": proposal["response"]},
            {"role": "user", "content": answer},
        ]
    )
    return proposal, reply


def created(api: FakeApi) -> list[dict]:
    return [arguments for name, arguments in api.calls if name == "create_recipe"]


async def test_a_page_recipe_is_proposed_then_created_with_everything_after_the_yes(api):
    proposal, _ = await propose_then_answer("Ajoute cette recette" + SCREEN, "Oui")

    assert api.calls[0] == ("read_web_page", {"url": URL})
    assert proposal["response"].rstrip().endswith("?")
    [recipe] = created(api)
    assert recipe["name"] == "Boeuf bourguignon : la vraie recette"
    assert recipe["servings"] == 6
    assert recipe["notes"] == f"Source : {URL}"
    assert json.loads(recipe["utensils"]) == ["Cocotte"]
    assert len(json.loads(recipe["ingredients"])) == 6
    steps = json.loads(recipe["steps"])
    assert [step.get("waitsFor") for step in steps] == [None, [1], [2]]
    assert steps[2]["durationMinutes"] == 180
    assert steps[2]["background"] is True


async def test_nothing_is_created_before_the_yes(api):
    await talk([{"role": "user", "content": "Ajoute cette recette" + SCREEN}])

    assert created(api) == []


async def test_a_correction_remakes_the_proposal_and_creates_nothing(api):
    _, corrected = await propose_then_answer("Ajoute cette recette" + SCREEN, "Plutôt pour 12 personnes")

    assert created(api) == []
    assert "12 personnes" in corrected["response"]
    assert corrected["response"].rstrip().endswith("?")


@pytest.mark.parametrize("source", [PASTED, DICTATED], ids=["pasted", "dictated"])
async def test_pasted_and_dictated_recipes_are_proposed_without_writing(api, source):
    proposal = await talk([{"role": "user", "content": source}])

    assert created(api) == []
    assert "Pâtes aux légumes" in proposal["response"]
    assert proposal["response"].rstrip().endswith("?")


async def test_pasted_and_dictated_recipes_give_the_same_recipe(api):
    await propose_then_answer(PASTED, "oui")
    pasted = created(api)
    api.calls.clear()
    await propose_then_answer(DICTATED, "oui")

    assert len(pasted) == 1
    assert created(api) == pasted
    steps = json.loads(pasted[0]["steps"])
    assert len(steps) == 5
    assert [step.get("durationMinutes") for step in steps] == [10, None, 12, 8, None]
    assert [step.get("background", False) for step in steps] == [True, False, True, False, False]
    assert steps[2]["waitsFor"] == [1]
    assert steps[4]["waitsFor"] == [3, 4]
    assert json.loads(pasted[0]["utensils"]) == ["Casserole", "Poêle"]
