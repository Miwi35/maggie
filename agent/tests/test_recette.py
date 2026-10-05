"""The Recette account's triggers (MAG-249): permission, real execution, dry run, reset."""

import json
from datetime import UTC, datetime
from unittest.mock import AsyncMock, MagicMock, patch

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi import FastAPI
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.api.routes import router
from app.auth import PROACTION_TRIGGER_ROLE
from app.config import settings
from app.db.context_model import ConversationContext
from app.db.instruction_model import Instruction
from app.db.memory_model import Memory
from app.db.models import Message
from app.db.personality_model import PersonalityConfig
from app.db.proaction_model import AgentBase, Proaction, ProactionStatus
from app.db.skill_model import Skill
from app.db.user_setting_model import UserSetting
from app.llm.dry_run import SIMULATED_RESULT, DryRunToolRouter, is_read_only
from app.llm.gateway import LLMGateway
from app.queue.scheduler import DAILY_PLANNING_PROMPT

from tests.conftest import _SyncSessionAsAsync

_private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_PUBLIC_PEM = (
    _private_key.public_key()
    .public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    .decode()
)
_PRIVATE_PEM = _private_key.private_bytes(
    serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()
).decode()

RECETTE = "recette-user"
OTHER = "other-user"
PLUMBER = {"prompt": "Rappelle-moi d'appeler le plombier", "scheduled_at": "2026-10-06T09:00:00+02:00"}


def bearer(user_id: str = RECETTE, roles: list[str] | None = None) -> dict:
    claims: dict = {"sub": user_id}
    if roles is not None:
        claims["roles"] = roles
    return {"Authorization": f"Bearer {jwt.encode(claims, _PRIVATE_PEM, algorithm='RS256')}"}


TRIGGER = ["ROLE_USER", PROACTION_TRIGGER_ROLE]


class TestDryRunToolRouter:
    @pytest.fixture()
    def router(self):
        inner = MagicMock()
        inner.call_tool = AsyncMock(return_value='{"ok": true}')
        inner.get_tool_definitions = AsyncMock(return_value=[{"name": "x"}])
        return DryRunToolRouter(inner), inner

    @pytest.mark.parametrize(
        ("name", "arguments"),
        [
            ("list_proactions", {}),
            ("search_memory", {"query": "x"}),
            ("get_events_by_date", {"date": "2026-10-06"}),
            ("manage_tasks", {"action": "list"}),
            ("manage_events", {"action": "get", "id": "1"}),
            ("monthly_review", {"action": "review"}),
        ],
    )
    async def test_reading_tools_run_for_real(self, router, name, arguments):
        dry, inner = router

        assert await dry.call_tool(name, arguments, user_id=RECETTE, source="proaction") == '{"ok": true}'
        inner.call_tool.assert_awaited_once_with(name, arguments, user_id=RECETTE, source="proaction")
        assert dry.simulated == []

    @pytest.mark.parametrize(
        ("name", "arguments"),
        [
            ("schedule_proaction", PLUMBER),
            ("store_memory", {"content": "x"}),
            ("get_grocery_list", {}),
            ("manage_tasks", {"action": "create", "title": "x"}),
            ("manage_tasks", {"action": ["list"]}),
            ("manage_tasks", {}),
            ("monthly_review", {"action": "close"}),
            ("a_tool_nobody_classified", {}),
        ],
    )
    async def test_writing_or_unknown_tools_are_simulated_and_reported(self, router, name, arguments):
        dry, inner = router

        result = json.loads(await dry.call_tool(name, arguments, user_id=RECETTE))

        assert result == SIMULATED_RESULT
        inner.call_tool.assert_not_awaited()
        assert dry.simulated == [{"name": name, "input": arguments}]

    async def test_the_model_sees_the_same_tools(self, router):
        dry, _ = router

        assert await dry.get_tool_definitions(include_native=True) == [{"name": "x"}]

    def test_is_read_only_does_not_trust_a_bare_name_prefix(self):
        assert not is_read_only("delete_everything", {"action": "list"})


@pytest.fixture()
def recette_db():
    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
    AgentBase.metadata.create_all(engine)
    factory = sessionmaker(engine, expire_on_commit=False)

    def open_session():
        return _SyncSessionAsAsync(factory())

    with (
        patch("app.db.proaction_repository.agent_session", open_session),
        patch("app.db.message_repository.agent_session", open_session),
        patch("app.db.user_data.agent_session", open_session),
        patch("app.db.proaction_repository.proaction_repo.publisher.publish", new=AsyncMock()),
        patch("app.db.message_repository.message_repo.publisher.publish", new=AsyncMock()),
        patch("app.queue.proaction_consumer.context_summarizer.maybe_summarize", new=AsyncMock()),
        patch("app.api.routes.context_summarizer.maybe_summarize", new=AsyncMock()),
    ):
        yield factory
    engine.dispose()


def snapshot(factory) -> dict:
    """Every row of every agent table, to compare the database before and after."""
    with factory() as session:
        return {t.name: sorted(map(tuple, session.execute(select(t)).all()), key=repr) for t in AgentBase.metadata.sorted_tables}


def seed_proaction(factory, user_id: str = RECETTE, status: ProactionStatus = ProactionStatus.PENDING) -> str:
    with factory() as session:
        proaction = Proaction(
            user_id=user_id,
            prompt="Rappelle-moi le plombier",
            status=status,
            scheduled_at=datetime(2026, 10, 6, 7, 0, tzinfo=UTC),
        )
        session.add(proaction)
        session.commit()
        return proaction.id


@pytest.fixture()
def model_script():
    """What the model does during a run: what it asks of the tools, and what it answers."""
    return {"calls": [("list_proactions", {}), ("schedule_proaction", PLUMBER)], "answer": "Pense au plombier."}


@pytest.fixture()
def api(recette_db, model_script):
    """The agent's routes, with a real gateway whose model is scripted and whose tools write to SQLite."""
    gateway = LLMGateway()
    gateway.client = object()
    gateway.tool_router.get_tool_definitions = AsyncMock(return_value=[])
    gateway._build_system_prompt = AsyncMock(return_value="system")
    gateway._resolve_proaction_context = AsyncMock(return_value="ctx-1")
    prompts: list[str] = []

    async def scripted_loop(system, messages, tools, *, tool_router, user_id, **kwargs):
        prompts.append(messages[0]["content"])
        for name, arguments in model_script["calls"]:
            await tool_router.call_tool(name, arguments, user_id=user_id, source="proaction")
        return {"response": model_script["answer"], "tool_calls": [{"name": n} for n, _ in model_script["calls"]]}

    app = FastAPI()
    app.include_router(router)
    with (
        patch("app.api.routes.llm_gateway", gateway),
        patch("app.llm.gateway.run_tool_loop", scripted_loop),
        patch("app.auth._get_public_key", return_value=_PUBLIC_PEM),
        TestClient(app) as client,
    ):
        client.prompts = prompts
        client.db = recette_db
        yield client


class TestPermission:
    @pytest.mark.parametrize("path", ["/proactions/generate", "/proactions/abc/execute"])
    @pytest.mark.parametrize("query", ["", "?dry_run=true"])
    def test_without_the_role_both_routes_answer_403_and_touch_nothing(self, api, path, query):
        seed_proaction(api.db)
        before = snapshot(api.db)

        for headers in (bearer(roles=["ROLE_USER"]), bearer()):
            assert api.post(path + query, headers=headers).status_code == 403

        assert snapshot(api.db) == before
        assert api.prompts == []

    @pytest.mark.parametrize("path", ["/proactions/generate", "/proactions/abc/execute"])
    def test_without_a_token_it_is_refused(self, api, path):
        assert api.post(path).status_code in (401, 403)

    def test_the_service_token_is_not_a_user_and_has_no_permission(self, api):
        with patch.object(settings, "service_token", "s3cret"):
            response = api.post("/proactions/generate", headers={"Authorization": "Bearer s3cret"})

        assert response.status_code == 401

    def test_the_role_is_read_from_the_token_not_from_the_request(self, api):
        response = api.post("/proactions/generate?roles=ROLE_PROACTION_TRIGGER", headers=bearer(roles=["ROLE_USER"]))

        assert response.status_code == 403


class TestGenerate:
    def test_it_runs_the_planner_prompt_and_returns_what_it_planned(self, api):
        response = api.post("/proactions/generate", headers=bearer(roles=TRIGGER))

        assert response.status_code == 200
        body = response.json()
        assert api.prompts == [DAILY_PLANNING_PROMPT]
        assert body["dryRun"] is False
        assert body["response"] == "Pense au plombier."
        assert [(p["prompt"], p["status"], p["userId"]) for p in body["planned"]] == [
            (PLUMBER["prompt"], "pending", RECETTE)
        ]

    def test_what_was_planned_is_listed_with_its_rule_its_due_date_and_its_status(self, api):
        api.post("/proactions/generate", headers=bearer(roles=TRIGGER))

        listed = api.get("/proactions", headers=bearer(roles=["ROLE_USER"])).json()

        assert len(listed) == 1
        assert listed[0]["prompt"] == PLUMBER["prompt"]
        assert listed[0]["scheduledAt"].startswith("2026-10-06T")
        assert listed[0]["status"] == "pending"

    def test_only_what_this_run_planned_is_returned(self, api):
        seed_proaction(api.db)

        body = api.post("/proactions/generate", headers=bearer(roles=TRIGGER)).json()

        assert len(body["planned"]) == 1

    def test_dry_run_records_nothing_and_says_what_it_would_have_planned(self, api):
        seed_proaction(api.db)
        before = snapshot(api.db)

        response = api.post("/proactions/generate?dry_run=true", headers=bearer(roles=TRIGGER))

        assert response.status_code == 200
        body = response.json()
        assert body["dryRun"] is True
        assert body["planned"] == [
            {"prompt": PLUMBER["prompt"], "scheduledAt": PLUMBER["scheduled_at"], "simulated": True}
        ]
        assert [t["name"] for t in body["simulatedTools"]] == ["schedule_proaction"]
        assert snapshot(api.db) == before

    def test_a_failing_run_is_a_502_not_a_silent_success(self, api):
        with patch("app.api.routes.generate_proactions", AsyncMock(side_effect=RuntimeError("boom"))):
            response = api.post("/proactions/generate", headers=bearer(roles=TRIGGER))

        assert response.status_code == 502


class TestExecute:
    def test_it_runs_the_proaction_the_way_the_consumer_does_and_returns_the_message(self, api):
        proaction_id = seed_proaction(api.db)

        response = api.post(f"/proactions/{proaction_id}/execute", headers=bearer(roles=TRIGGER))

        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "completed"
        assert body["message"] == "Pense au plombier."
        assert body["dryRun"] is False
        with api.db() as session:
            proaction = session.get(Proaction, proaction_id)
            messages = session.execute(select(Message)).scalars().all()
        assert proaction.status == ProactionStatus.COMPLETED
        assert proaction.response == "Pense au plombier."
        assert [(m.user_id, m.role, m.content, m.context_id) for m in messages] == [
            (RECETTE, "assistant", "Pense au plombier.", "ctx-1")
        ]

    def test_a_proaction_already_taken_is_not_run_twice(self, api):
        proaction_id = seed_proaction(api.db)
        api.post(f"/proactions/{proaction_id}/execute", headers=bearer(roles=TRIGGER))
        prompts_after_first = list(api.prompts)

        response = api.post(f"/proactions/{proaction_id}/execute", headers=bearer(roles=TRIGGER))

        assert response.status_code == 409
        assert api.prompts == prompts_after_first

    def test_a_proaction_of_someone_else_is_not_found(self, api):
        proaction_id = seed_proaction(api.db, user_id=OTHER)
        before = snapshot(api.db)

        response = api.post(f"/proactions/{proaction_id}/execute", headers=bearer(roles=TRIGGER))

        assert response.status_code == 404
        assert snapshot(api.db) == before

    def test_an_unknown_proaction_is_not_found(self, api):
        assert api.post("/proactions/nope/execute", headers=bearer(roles=TRIGGER)).status_code == 404

    def test_a_failing_run_marks_the_proaction_failed_like_the_consumer(self, api, model_script):
        proaction_id = seed_proaction(api.db)

        async def boom(*args, **kwargs):
            raise RuntimeError("model down")

        with patch("app.llm.gateway.run_tool_loop", boom):
            body = api.post(f"/proactions/{proaction_id}/execute", headers=bearer(roles=TRIGGER)).json()

        assert body["status"] == "failed"
        assert body["error"] == "model down"
        with api.db() as session:
            assert session.get(Proaction, proaction_id).status == ProactionStatus.FAILED

    def test_dry_run_leaves_the_database_exactly_as_it_was(self, api):
        proaction_id = seed_proaction(api.db)
        before = snapshot(api.db)

        response = api.post(f"/proactions/{proaction_id}/execute?dry_run=true", headers=bearer(roles=TRIGGER))

        assert response.status_code == 200
        body = response.json()
        assert body["dryRun"] is True
        assert body["message"] == "Pense au plombier."
        assert body["status"] == "pending"
        assert [t["name"] for t in body["simulatedTools"]] == ["schedule_proaction"]
        assert snapshot(api.db) == before

    def test_dry_run_can_be_repeated_to_calibrate(self, api):
        proaction_id = seed_proaction(api.db)

        statuses = [
            api.post(f"/proactions/{proaction_id}/execute?dry_run=true", headers=bearer(roles=TRIGGER)).status_code
            for _ in range(2)
        ]

        assert statuses == [200, 200]

    def test_dry_run_publishes_nothing(self, api):
        proaction_id = seed_proaction(api.db)
        from app.db.message_repository import message_repo
        from app.db.proaction_repository import proaction_repo

        api.post(f"/proactions/{proaction_id}/execute?dry_run=true", headers=bearer(roles=TRIGGER))

        proaction_repo.publisher.publish.assert_not_awaited()
        message_repo.publisher.publish.assert_not_awaited()


def seed_everything(factory, user_id: str) -> None:
    with factory() as session:
        session.add_all(
            [
                Proaction(user_id=user_id, prompt="p", scheduled_at=datetime(2026, 10, 6, tzinfo=UTC)),
                Message(user_id=user_id, role="user", content="salut"),
                ConversationContext(user_id=user_id, label="plomberie"),
                PersonalityConfig(user_id=user_id),
                UserSetting(user_id=user_id),
                Memory(user_id=user_id, content="aime le café"),
                Instruction(user_id=user_id, content="planifie le matin"),
            ]
        )
        session.commit()


def rows_of(factory, user_id: str) -> dict:
    with factory() as session:
        return {
            t.name: session.execute(select(t).where(t.c.user_id == user_id)).all()
            for t in AgentBase.metadata.sorted_tables
            if "user_id" in t.c
        }


class TestReset:
    @pytest.fixture(autouse=True)
    def service_token(self):
        with patch.object(settings, "service_token", "s3cret"):
            yield

    def reset(self, api, *, dry_run: bool = False, headers: dict | None = None):
        return api.post(
            "/internal/recette/reset",
            json={"userId": RECETTE, "dryRun": dry_run},
            headers=headers if headers is not None else {"Authorization": "Bearer s3cret"},
        )

    def test_it_deletes_every_row_of_the_account_and_only_those(self, api):
        seed_everything(api.db, RECETTE)
        seed_everything(api.db, OTHER)
        with api.db() as session:
            session.add(Skill(name="cuisine", description="d", tags=[], content="c"))
            session.commit()
        others = rows_of(api.db, OTHER)

        response = self.reset(api)

        assert response.status_code == 200
        deleted = response.json()["deleted"]
        assert deleted["proaction"] == deleted["agent_message"] == deleted["memory"] == 1
        assert all(not rows for rows in rows_of(api.db, RECETTE).values())
        assert rows_of(api.db, OTHER) == others
        with api.db() as session:
            assert session.execute(select(Skill)).scalars().all() != []

    def test_every_agent_table_that_holds_a_user_is_covered(self, api):
        seed_everything(api.db, RECETTE)

        deleted = self.reset(api).json()["deleted"]

        assert {t.name for t in AgentBase.metadata.sorted_tables if "user_id" in t.c} == set(deleted)
        assert "skill" not in deleted

    def test_dry_run_counts_and_deletes_nothing(self, api):
        seed_everything(api.db, RECETTE)
        before = snapshot(api.db)

        response = self.reset(api, dry_run=True)

        assert response.json()["deleted"]["proaction"] == 1
        assert snapshot(api.db) == before

    @pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer wrong"}])
    def test_it_is_closed_without_the_service_token(self, api, headers):
        seed_everything(api.db, RECETTE)
        before = snapshot(api.db)

        assert self.reset(api, headers=headers).status_code in (401, 403)
        assert snapshot(api.db) == before

    def test_a_user_token_is_not_the_service_token(self, api):
        assert self.reset(api, headers=bearer(roles=TRIGGER)).status_code == 401

    def test_it_is_closed_when_no_service_token_is_configured(self, api):
        with patch.object(settings, "service_token", ""):
            assert self.reset(api, headers={"Authorization": "Bearer "}).status_code in (401, 403)

    def test_it_needs_a_user(self, api):
        response = api.post("/internal/recette/reset", json={"userId": ""}, headers={"Authorization": "Bearer s3cret"})

        assert response.status_code == 422
