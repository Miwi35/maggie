"""Whether the user is at work, from the working hours of Réglages › Maggie (MAG-790)."""

from datetime import datetime
from zoneinfo import ZoneInfo

import pytest

from app.work_hours import IN_WORK_HOURS_LINE, OUT_OF_WORK_HOURS_LINE, in_work_hours, work_hours_line

PARIS = ZoneInfo("Europe/Paris")
NEW_YORK = ZoneInfo("America/New_York")
DEFAULTS = {"workStartTime": "09:00", "workEndTime": "18:00", "workDays": [1, 2, 3, 4, 5]}

# 2026-11-10 is a Tuesday, 2026-11-14 a Saturday.
TUESDAY_10 = datetime(2026, 11, 10, 10, 0, tzinfo=PARIS)


class TestInside:
    def test_a_tuesday_at_ten_is_at_work(self):
        assert in_work_hours(DEFAULTS, TUESDAY_10)

    def test_the_start_minute_is_at_work(self):
        assert in_work_hours(DEFAULTS, datetime(2026, 11, 10, 9, 0, tzinfo=PARIS))

    def test_nothing_read_takes_the_defaults(self):
        assert in_work_hours({}, TUESDAY_10)

    def test_the_line_says_en_cours(self):
        assert work_hours_line(DEFAULTS, TUESDAY_10) == IN_WORK_HOURS_LINE
        assert "Horaires de travail : en cours" in IN_WORK_HOURS_LINE


class TestOutside:
    @pytest.mark.parametrize(
        "now",
        [
            datetime(2026, 11, 10, 19, 0, tzinfo=PARIS),
            datetime(2026, 11, 10, 18, 0, tzinfo=PARIS),
            datetime(2026, 11, 10, 8, 59, tzinfo=PARIS),
        ],
        ids=["tuesday 19:00", "the end minute", "before the start"],
    )
    def test_outside_the_hours(self, now):
        assert not in_work_hours(DEFAULTS, now)

    def test_a_saturday_is_off(self):
        assert not in_work_hours(DEFAULTS, datetime(2026, 11, 14, 10, 0, tzinfo=PARIS))

    def test_the_line_says_hors_horaires(self):
        line = work_hours_line(DEFAULTS, datetime(2026, 11, 14, 10, 0, tzinfo=PARIS))
        assert line == OUT_OF_WORK_HOURS_LINE
        assert "hors horaires de travail" in line


class TestTheDays:
    def test_a_day_left_out_of_the_list_is_off(self):
        friday_10 = datetime(2026, 11, 13, 10, 0, tzinfo=PARIS)
        assert not in_work_hours({**DEFAULTS, "workDays": [1, 2, 3, 4]}, friday_10)

    def test_a_sunday_in_the_list_is_at_work(self):
        sunday_10 = datetime(2026, 11, 15, 10, 0, tzinfo=PARIS)
        assert in_work_hours({**DEFAULTS, "workDays": [7]}, sunday_10)

    def test_no_day_at_all_is_never_at_work(self):
        assert not in_work_hours({**DEFAULTS, "workDays": []}, TUESDAY_10)


class TestTheTimezone:
    def test_the_hours_are_read_in_the_users_timezone(self):
        """16:00 in Paris is 10:00 in New York: at work there, whatever the server's clock."""
        paris_16 = datetime(2026, 11, 10, 16, 0, tzinfo=PARIS)
        assert in_work_hours(DEFAULTS, paris_16.astimezone(NEW_YORK))
        paris_9 = datetime(2026, 11, 10, 9, 30, tzinfo=PARIS)
        assert not in_work_hours(DEFAULTS, paris_9.astimezone(NEW_YORK))


class TestTheStreamedChatSaysIt:
    """The app's chat (AG-UI) builds its own prompt: the line is in its volatile part too, not in the prefix."""

    async def test_the_line_is_next_to_the_date(self):
        from unittest.mock import AsyncMock, MagicMock, patch

        from app.llm.streaming import StreamingGateway
        from app.memory.agent_memory import MemoryBlocks

        gateway = StreamingGateway()
        gateway.personality = MagicMock()
        gateway.personality.get_system_prompt = AsyncMock(return_value="Tu es Maggie.")
        gateway.agent_memory = MagicMock()
        gateway.agent_memory.blocks = AsyncMock(return_value=MemoryBlocks())
        clock = MagicMock(wraps=datetime)
        clock.now = MagicMock(return_value=datetime(2026, 11, 14, 10, 0, tzinfo=PARIS))
        with (
            patch("app.llm.contexts.context_repo", MagicMock(find_active=AsyncMock(return_value=[]))),
            patch("app.llm.streaming.skill_index") as skills,
            patch("app.llm.streaming.datetime", clock),
        ):
            skills.get_skills_index.return_value = ""
            skills.skills_for_moment.return_value = ""
            skills.refresh = AsyncMock()
            blocks = await gateway._build_system_prompt("user-1")

        assert OUT_OF_WORK_HOURS_LINE in blocks[-1]["text"]
        assert "Horaires de travail" not in blocks[0]["text"]
