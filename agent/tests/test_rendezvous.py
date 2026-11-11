"""The morning digest and the evening review laid down by code from the user's settings (MAG-787),
against the simulated API: created, moved, cancelled, never doubled, never for a technical account."""

from datetime import UTC, datetime
from unittest.mock import AsyncMock

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app import e2e, rendezvous
from app.auth import RECETTE_ACCOUNT_EMAIL, SMOKE_ACCOUNT_EMAIL
from app.bilan import BILAN_PROMPT, BILAN_RELANCE_SUMMARY, BILAN_SUMMARY
from app.proactions import api as proactions_api_module
from app.rendezvous import DIGEST_PROMPT, DIGEST_SUMMARY, sync_rendezvous
from tests.fake_proaction_api import FakeProactionApi

USER = "01HZX3Q8R5T6V7W8X9Y0Z1A2B3"
# 14 Nov. 2030, 05:00 in Paris.
EARLY = datetime(2030, 11, 14, 4, 0, tzinfo=UTC)


def settings(**changes) -> dict:
    """A user who never opened the settings: the API's defaults."""
    return {
        "userId": USER,
        "email": "owner@example.com",
        "timezone": "Europe/Paris",
        "morningDigestEnabled": True,
        "morningDigestTime": "07:00",
        "eveningReviewEnabled": True,
        "eveningReviewTime": "21:30",
    } | changes


@pytest.fixture()
def api(monkeypatch):
    fake = FakeProactionApi(now=EARLY)
    fake.rendezvous = [settings()]
    monkeypatch.setattr(proactions_api_module, "mcp_client", fake)
    return fake


def day_of(api: FakeProactionApi) -> list[tuple[str, str]]:
    return [(p["summary"], p["scheduledAt"]) for p in api.of(USER)]


async def at(api: FakeProactionApi, now: datetime) -> dict:
    api.now = now
    return await sync_rendezvous(now)


class TestTheDefaults:
    async def test_a_user_without_any_instruction_gets_the_digest_and_both_review_proactions(self, api):
        await at(api, EARLY)

        assert day_of(api) == [
            (DIGEST_SUMMARY, "2030-11-14T07:00:00+01:00"),
            (BILAN_SUMMARY, "2030-11-14T21:30:00+01:00"),
            (BILAN_RELANCE_SUMMARY, "2030-11-14T22:00:00+01:00"),
        ]
        prompts = {p["summary"]: p["prompt"] for p in api.of(USER)}
        assert prompts[DIGEST_SUMMARY] == DIGEST_PROMPT == "Fais le digest du matin (compétence digest)."
        assert prompts[BILAN_SUMMARY] == prompts[BILAN_RELANCE_SUMMARY] == BILAN_PROMPT

    async def test_past_the_digest_s_hour_only_the_review_is_laid_down(self, api):
        await at(api, datetime(2030, 11, 14, 9, 0, tzinfo=UTC))

        assert [summary for summary, _ in day_of(api)] == [BILAN_SUMMARY, BILAN_RELANCE_SUMMARY]

    async def test_the_hours_are_the_user_s_wall_clock(self, api):
        api.rendezvous = [settings(timezone="America/New_York")]

        await at(api, datetime(2030, 11, 14, 10, 0, tzinfo=UTC))

        assert day_of(api)[0] == (DIGEST_SUMMARY, "2030-11-14T07:00:00-05:00")


class TestIdempotence:
    async def test_two_cycles_in_a_row_create_nothing_more(self, api):
        first = await at(api, EARLY)
        before = api.snapshot()

        second = await at(api, datetime(2030, 11, 14, 4, 15, tzinfo=UTC))

        assert first == {"scheduled": 3, "cancelled": 0}
        assert second == {"scheduled": 0, "cancelled": 0}
        assert api.snapshot() == before

    async def test_a_rendezvous_that_already_ran_today_is_not_laid_down_again(self, api):
        await at(api, EARLY)
        digest = next(p for p in api.of(USER) if p["summary"] == DIGEST_SUMMARY)
        digest["status"] = "completed"
        api.rendezvous = [settings(morningDigestTime="08:00")]

        await at(api, datetime(2030, 11, 14, 6, 30, tzinfo=UTC))

        assert [p["status"] for p in api.of(USER) if p["summary"] == DIGEST_SUMMARY] == ["completed"]


class TestAChangedHour:
    async def test_the_review_set_to_22_15_moves_with_its_nudge(self, api):
        await at(api, EARLY)
        api.rendezvous = [settings(eveningReviewTime="22:15")]

        done = await at(api, datetime(2030, 11, 14, 20, 0, tzinfo=UTC))

        assert done == {"scheduled": 2, "cancelled": 2}
        assert day_of(api)[1:] == [
            (BILAN_SUMMARY, "2030-11-14T22:15:00+01:00"),
            (BILAN_RELANCE_SUMMARY, "2030-11-14T22:45:00+01:00"),
        ]

    async def test_a_nudge_after_midnight_still_belongs_to_the_evening(self, api):
        api.rendezvous = [settings(eveningReviewTime="23:45")]

        await at(api, EARLY)
        again = await at(api, datetime(2030, 11, 14, 12, 0, tzinfo=UTC))

        assert (BILAN_RELANCE_SUMMARY, "2030-11-15T00:15:00+01:00") in day_of(api)
        assert again == {"scheduled": 0, "cancelled": 0}

    async def test_a_digest_the_model_laid_down_from_an_instruction_is_moved_not_doubled(self, api):
        api.seed(USER, prompt="Digest", at="2030-11-14T07:02:00+01:00", summary=DIGEST_SUMMARY)

        await at(api, EARLY)

        assert [at for summary, at in day_of(api) if summary == DIGEST_SUMMARY] == ["2030-11-14T07:00:00+01:00"]


class TestATurnedOffRendezvous:
    async def test_the_digest_turned_off_cancels_today_s_and_lays_none_the_next_days(self, api):
        await at(api, EARLY)
        api.rendezvous = [settings(morningDigestEnabled=False)]

        await at(api, datetime(2030, 11, 14, 5, 0, tzinfo=UTC))
        await at(api, datetime(2030, 11, 15, 4, 0, tzinfo=UTC))

        assert all(summary != DIGEST_SUMMARY for summary, _ in day_of(api))

    async def test_the_review_turned_off_cancels_both_proactions(self, api):
        await at(api, EARLY)
        api.rendezvous = [settings(eveningReviewEnabled=False)]

        await at(api, datetime(2030, 11, 14, 5, 0, tzinfo=UTC))

        assert [summary for summary, _ in day_of(api)] == [DIGEST_SUMMARY]


class TestWhoGetsThem:
    @pytest.mark.parametrize("email", [SMOKE_ACCOUNT_EMAIL, RECETTE_ACCOUNT_EMAIL])
    async def test_a_technical_account_gets_nothing(self, api, email):
        api.rendezvous = [settings(email=email)]

        await at(api, EARLY)

        assert api.of(USER) == []

    async def test_one_user_that_fails_does_not_stop_the_others(self, api, monkeypatch):
        other = "01HZX3Q8R5T6V7W8X9Y0Z1A2B4"
        api.rendezvous = [settings(userId="not-a-user"), settings(userId=other)]
        real = api._list_proactions
        monkeypatch.setattr(
            api, "_list_proactions", lambda args, uid: {"error": "boom"} if uid == "not-a-user" else real(args, uid)
        )

        await at(api, EARLY)

        assert len(api.of(other)) == 3

    async def test_an_api_that_does_not_answer_raises_nothing(self, api):
        api.down = True

        assert await at(api, EARLY) == {"scheduled": 0, "cancelled": 0}


class TestOnDemand:
    async def test_one_user_alone_is_laid_down(self, api):
        other = "01HZX3Q8R5T6V7W8X9Y0Z1A2B4"
        api.rendezvous = [settings(), settings(userId=other)]

        await sync_rendezvous(EARLY, USER)

        assert len(api.of(USER)) == 3
        assert api.of(other) == []
        assert ("list_daily_rendezvous", {}, USER) in api.calls

    def test_the_e2e_route_lays_down_the_user_s_rendezvous(self, api, monkeypatch):
        monkeypatch.setattr("app.e2e.settings.tts_provider", "fake")
        monkeypatch.setattr("app.e2e.settings.e2e_login_token", "token")
        monkeypatch.setattr(e2e, "sync_rendezvous", AsyncMock(return_value={"scheduled": 3, "cancelled": 0}))
        app = FastAPI()
        e2e.setup_e2e(app)

        response = TestClient(app).post(f"/e2e/rendezvous/{USER}/sync", headers={"X-E2E-Token": "token"})

        assert response.json() == {"scheduled": 3, "cancelled": 0}
        assert e2e.sync_rendezvous.await_args.args[1] == USER


def test_an_unreadable_hour_falls_back_to_the_default():
    [digest, review, _] = rendezvous.wanted_rendezvous(
        settings(morningDigestTime="7h", eveningReviewTime=None), EARLY.date(), rendezvous._timezone("Europe/Paris")
    )

    assert (digest.at.hour, digest.at.minute, review.at.hour, review.at.minute) == (7, 0, 21, 30)
