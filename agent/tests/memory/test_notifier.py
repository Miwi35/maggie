import logging

from app.memory.index import Change
from app.memory.notifier import MemoryNotifier
from tests.memory.conftest import RecordingPublisher


def change(kind: str = "created", user: str = "u1", path: str = "u1/a.md") -> Change:
    return Change(kind=kind, user_id=user, note_id="N1", path=path, title="A", status="active")


class TestMercureMemoryStream:
    async def test_a_change_is_published_on_the_users_memory_topic_with_the_contract_payload(self):
        publisher = RecordingPublisher()

        await MemoryNotifier(publisher=publisher).publish([change()])  # type: ignore[arg-type]

        assert publisher.published == [
            ("/memory/u1", {"type": "created", "noteId": "N1", "path": "u1/a.md", "title": "A", "status": "active"})
        ]

    async def test_each_user_gets_their_own_topic(self):
        publisher = RecordingPublisher()

        await MemoryNotifier(publisher=publisher).publish(  # type: ignore[arg-type]
            [change(user="u1"), change(user="u2", path="u2/a.md"), change("deleted", user="u1")]
        )

        assert [t for t, _ in publisher.published] == ["/memory/u1", "/memory/u2", "/memory/u1"]
        assert publisher.kinds("u1") == ["created", "deleted"]

    async def test_nothing_to_say_publishes_nothing(self):
        publisher = RecordingPublisher()

        await MemoryNotifier(publisher=publisher).publish([])  # type: ignore[arg-type]

        assert publisher.published == []

    async def test_a_hub_that_is_down_never_fails_the_sync_and_the_rest_still_goes_out(self, caplog):
        class Flaky(RecordingPublisher):
            async def publish(self, topic: str, data: dict) -> None:
                if data["path"] == "u1/bad.md":
                    raise RuntimeError("hub is down")
                await super().publish(topic, data)

        publisher = Flaky()

        with caplog.at_level(logging.WARNING):
            await MemoryNotifier(publisher=publisher).publish(  # type: ignore[arg-type]
                [change(path="u1/bad.md"), change(path="u1/good.md")]
            )

        assert [d["path"] for _, d in publisher.published] == ["u1/good.md"]
        assert "u1/bad.md" in caplog.text
