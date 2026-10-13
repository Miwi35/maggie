from unittest.mock import AsyncMock, patch

import pytest

from app.memory.agent_memory import AgentMemory
from app.memory.bucket import BucketUnavailable
from tests.memory.conftest import USER_A, fiche


@pytest.fixture()
def memory(env):
    with (
        patch("app.memory.agent_memory.memory_service", env.service),
        patch("app.memory.agent_memory.memory_repo.find_by_user", AsyncMock(return_value=[])),
    ):
        yield AgentMemory()


async def test_the_prompt_carries_nothing_when_the_folder_is_up_to_date(memory, env):
    env.service.turn_timeout_seconds = 1
    assert await memory.get_memory_context(USER_A) == ""


async def test_the_prompt_says_the_memory_may_be_late_during_an_outage(memory, env):
    env.service.turn_timeout_seconds = 1
    await env.service.write(USER_A, "Roses.md", fiche("x"))
    env.bucket.down = True

    context = await memory.get_memory_context(USER_A)

    assert "peut-être en retard" in context


async def test_a_failure_of_the_folder_never_fails_the_turn(memory, env):
    env.service.before_turn = AsyncMock(side_effect=RuntimeError("boom"))
    assert await memory.get_memory_context(USER_A) == ""


async def test_the_unavailable_error_stays_inside_the_service(memory, env):
    env.bucket.down = True
    env.service.turn_timeout_seconds = 1
    with pytest.raises(BucketUnavailable):
        await env.service.sync_user(USER_A)
    assert "peut-être en retard" in await memory.get_memory_context(USER_A)
