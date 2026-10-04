"""The agent's Mercure topics are a published contract (MAG-138).

`agent/contract/mercure-topics.json` is written from `app.mercure.topics`; the
admin (`useMercure.contract.test.ts`) and the mobile app
(`MercureTopicsContractTest`) read it back. A client subscribing to a topic the
agent does not publish connects, stays connected and receives nothing — so the
two sides are compared through the file, not believed.

Regenerate after a deliberate change: `UPDATE_CONTRACT=1 task wt:test:agent -- tests/test_mercure_topics_contract.py`
"""

import json
import os
import re
from pathlib import Path
from unittest.mock import AsyncMock, patch

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.db.message_repository import MessageRepository
from app.db.models import Message
from app.mercure import topics
from tests.conftest import _SyncSessionAsAsync

AGENT_ROOT = Path(__file__).resolve().parents[1]
CONTRACT_FILE = AGENT_ROOT / "contract" / "mercure-topics.json"


def _rendered() -> str:
    return json.dumps(topics.SUBSCRIPTION_PATTERNS, indent=4, ensure_ascii=False) + "\n"


def test_contract_file_matches_the_topics_the_agent_publishes():
    if os.environ.get("UPDATE_CONTRACT"):
        CONTRACT_FILE.parent.mkdir(parents=True, exist_ok=True)
        CONTRACT_FILE.write_text(_rendered())

    assert CONTRACT_FILE.is_file(), (
        f"{CONTRACT_FILE} is missing. Generate it: UPDATE_CONTRACT=1 task wt:test:agent -- {Path(__file__).name}"
    )
    assert CONTRACT_FILE.read_text() == _rendered(), (
        "agent/contract/mercure-topics.json is out of date with app/mercure/topics.py. "
        "If the change is deliberate: UPDATE_CONTRACT=1, commit the diff, and check the admin and mobile suites."
    )


def test_every_pattern_is_keyed_by_the_user_id():
    for stream, pattern in topics.SUBSCRIPTION_PATTERNS.items():
        assert pattern == f"/{stream}/{{userId}}"
        assert topics.for_user(stream, "01HXYZ") == f"/{stream}/01HXYZ"


def test_unknown_stream_is_refused():
    with pytest.raises(ValueError):
        topics.for_user("approvals", "01HXYZ")


def test_no_publish_call_spells_a_topic_by_hand():
    """A literal topic in a publish() call is how three spellings of one stream got in."""
    offenders = []
    for source in (AGENT_ROOT / "app").rglob("*.py"):
        if source.name == "topics.py":
            continue
        for match in re.finditer(r"\.publish\(\s*f?[\"']/", source.read_text()):
            offenders.append(f"{source.relative_to(AGENT_ROOT)}: {match.group(0)}")

    assert offenders == [], "These publish() calls bypass app.mercure.topics:\n" + "\n".join(offenders)


async def test_a_created_message_is_published_on_the_users_chat_topic():
    engine = create_engine("sqlite://", poolclass=StaticPool, connect_args={"check_same_thread": False})
    Message.__table__.create(engine)
    factory = sessionmaker(engine, expire_on_commit=False)

    repo = MessageRepository()
    with (
        patch("app.db.message_repository.agent_session", lambda: _SyncSessionAsAsync(factory())),
        patch.object(repo.publisher, "publish", new=AsyncMock()) as publish,
    ):
        message = await repo.create(user_id="01HXYZ", role="user", content="Bonjour")

    publish.assert_awaited_once()
    assert publish.await_args.args[0] == "/chat/01HXYZ"
    assert publish.await_args.args[1]["id"] == message.id
    engine.dispose()


def test_the_memory_stream_is_a_published_topic():
    assert topics.MEMORY == "memory" and topics.MEMORY in topics.STREAMS
    assert topics.SUBSCRIPTION_PATTERNS["memory"] == "/memory/{userId}"
    assert topics.for_user(topics.MEMORY, "01HXYZ") == "/memory/01HXYZ"
    assert json.loads(CONTRACT_FILE.read_text())["memory"] == "/memory/{userId}"
