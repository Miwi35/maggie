"""The single system prompt builder (MAG-952): the aggregate's order, the cache blocks, the imports."""

import ast
from datetime import date, datetime
from pathlib import Path
from types import SimpleNamespace
from zoneinfo import ZoneInfo

import pytest

import app
from app.llm.prompt_cache import EPHEMERAL, EPHEMERAL_1H
from app.runtime.system_prompt import SystemPrompt
from tests.prompt_helpers import system_prompt_with

APP = Path(app.__file__).parent
PARIS = ZoneInfo("Europe/Paris")


class FakeSources:
    def __init__(self, *, date_line="DATE 10h", recalled="RECALLED a", notice="NOTICE"):
        self.date_line, self.recalled, self.notice = date_line, recalled, notice

    async def base(self, user_id, capabilities):
        return f"BASE[{capabilities}]"

    async def skills_index(self):
        return "|INDEX"

    def moment_skills(self, moment):
        return f"MOMENT {moment}"

    async def timezone(self, user_id):
        return PARIS

    async def memory(self, user_id, about, *, source, context_id, today: date, planned=False):
        self.planned = planned
        return SimpleNamespace(notice=self.notice, profile="PROFILE", recalled=self.recalled)

    async def directives(self, user_id):
        return "|DIRECTIVES"

    async def contexts(self, user_id, current_context_id, contexts):
        return "|THREADS"

    async def last_exchange(self, user_id, *, exclude_message_id, tz):
        return "|LAST"

    async def work_hours(self, user_id, now: datetime):
        return "WORK"

    async def day_context(self, user_id, day: date):
        return "DAY"

    def datetime_line(self, tz):
        return self.date_line

    def spoken_rules(self):
        return "SPOKEN"

    def driving_rules(self):
        return "DRIVING"

    def warm_up(self, user_id):
        pass


def _cached(blocks):
    return [block for block in blocks if "cache_control" in block]


async def test_the_cached_blocks_are_identical_between_two_turns():
    first = await SystemPrompt(FakeSources()).build("u", moment="chat", about="un message")
    second = await SystemPrompt(FakeSources(date_line="DATE 11h", recalled="RECALLED b", notice="OTHER")).build(
        "u", moment="chat", about="un autre message", preamble="PREAMBLE"
    )

    assert _cached(first) == _cached(second)
    assert [b.get("cache_control") for b in first[:3]] == [EPHEMERAL_1H, EPHEMERAL, EPHEMERAL]
    assert first[3]["text"] == "RECALLED a" and second[3]["text"] == "RECALLED b"


@pytest.mark.parametrize(
    ("options", "planned"),
    [
        ({"exclude_message_id": "m-1"}, True),
        ({"exclude_message_id": "m-1", "spoken": True}, False),
        ({"exclude_message_id": "m-1", "in_car": True}, False),
        ({}, False),
        ({"exclude_message_id": "m-1", "source": "proaction"}, False),
        ({"exclude_message_id": "m-1", "source": "planning"}, False),
    ],
)
async def test_only_a_written_turn_of_the_owner_plans_its_recall(options, planned):
    sources = FakeSources()

    await SystemPrompt(sources).build("u", moment="chat", **options)

    assert sources.planned is planned


async def test_no_volatile_part_enters_a_cached_block():
    blocks = await SystemPrompt(FakeSources()).build(
        "u", moment="chat", preamble="PREAMBLE", spoken=True, in_car=True
    )

    cached = " ".join(block["text"] for block in _cached(blocks))
    for volatile in ("DATE", "THREADS", "RECALLED", "PREAMBLE", "NOTICE", "DIRECTIVES", "LAST", "WORK", "DAY", "SPOKEN", "DRIVING"):
        assert volatile not in cached


async def test_the_volatile_part_follows_the_order_of_the_aggregate():
    blocks = await SystemPrompt(FakeSources()).build(
        "u", moment="chat", preamble="|PREAMBLE", spoken=True, in_car=True
    )

    volatile = blocks[-1]["text"]
    order = ["NOTICE", "DIRECTIVES", "THREADS", "DATE 10h", "WORK", "DAY", "LAST", "PREAMBLE", "SPOKEN", "DRIVING"]
    assert [volatile.index(marker) for marker in order] == sorted(volatile.index(marker) for marker in order)


async def test_the_socle_is_the_yaml_template_filled_with_the_owner_settings():
    from unittest.mock import AsyncMock, patch

    from app.db.personality_repository import personality_repo
    from app.personality.engine import PersonalityEngine

    row = SimpleNamespace(name="Jeanne", language="en", backstory="Née à Rennes.")
    engine = PersonalityEngine()
    with patch.object(personality_repo, "get", AsyncMock(return_value=row)):
        base = await engine.get_system_prompt("u", capabilities="CAPS")

    assert "Jeanne" in base and "Née à Rennes." in base and "CAPS" in base
    assert base == engine._yaml["system_prompt"].format(
        name="Jeanne", language="en", backstory="Née à Rennes.", capabilities="CAPS"
    )
    blocks = await system_prompt_with(personality=SimpleNamespace(get_system_prompt=AsyncMock(return_value=base))).thread_free(
        "u"
    )
    assert blocks.base.startswith(base)


async def test_spoken_and_driving_rules_never_enter_the_cached_prefix():
    written = await SystemPrompt(FakeSources()).build("u", moment="chat")
    in_car = await SystemPrompt(FakeSources()).build("u", moment="chat", spoken=True, in_car=True)

    assert _cached(written) == _cached(in_car)
    assert "SPOKEN" not in written[-1]["text"] and "DRIVING" not in written[-1]["text"]
    assert "SPOKEN" in in_car[-1]["text"] and "DRIVING" in in_car[-1]["text"]


def _imports(path: Path) -> set[str]:
    found: set[str] = set()
    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, ast.Import):
            found |= {alias.name for alias in node.names}
        elif isinstance(node, ast.ImportFrom) and node.module:
            found.add(node.module)
            found |= {f"{node.module}.{alias.name}" for alias in node.names}
    return found


def test_the_builder_imports_no_domain_module():
    forbidden = (
        "app.personality",
        "app.memory",
        "app.skills",
        "app.llm.contexts",
        "app.llm.directives",
        "app.llm.last_exchange",
        "app.day_context",
        "app.user_timezone",
        "app.work_hours",
        "app.db",
    )

    offenders = {
        module
        for module in _imports(APP / "runtime" / "system_prompt.py")
        if any(module == name or module.startswith(name + ".") for name in forbidden)
    }

    assert not offenders


def test_no_build_system_prompt_is_left_in_app():
    names = {"_build_system_prompt", "_thread_free_parts"}
    offenders = set()
    for path in APP.rglob("*.py"):
        for node in ast.walk(ast.parse(path.read_text())):
            defined = isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef) and node.name in names
            used = isinstance(node, ast.Attribute) and node.attr in names
            if defined or used:
                offenders.add(path.relative_to(APP).as_posix())

    assert not offenders


def test_the_personality_routes_read_and_write_the_owner_settings(authed_client):
    from unittest.mock import AsyncMock, patch

    from app.db.personality_repository import personality_repo

    row = SimpleNamespace(name="Jeanne", language="en", backstory="Née à Rennes.")
    with (
        patch.object(personality_repo, "get", AsyncMock(return_value=row)),
        patch.object(personality_repo, "upsert", AsyncMock(return_value=SimpleNamespace(name="Jo", language="en", backstory="Née à Rennes."))),
    ):
        got = authed_client.get("/personality")
        put = authed_client.put("/personality", json={"name": "Jo"})

    assert got.status_code == 200 and got.json()["name"] == "Jeanne"
    assert put.status_code == 200 and put.json()["name"] == "Jo"
