"""Storing a chat message, then telling Mercure and the conversation's memory (MAG-1050).

The repository only does the SQL; what a stored message sets off lives here.
"""

import logging
from datetime import datetime

from app.db.message_repository import message_repo
from app.db.models import Message
from app.memory import conversations
from app.mercure import topics
from app.mercure.publisher import MercurePublisher

logger = logging.getLogger(__name__)


class MessageService:
    """Every created message is published to Mercure on the user's chat topic
    (app.mercure.topics), the streamed ones included: a second tab or the phone
    only learns of an exchange that way. The clients recognise their own echo.
    """

    def __init__(self):
        self.publisher = MercurePublisher()

    async def create(
        self,
        user_id: str,
        role: str,
        content: str,
        context_id: str | None = None,
        *,
        message_id: str | None = None,
        client_key: str | None = None,
        turn_lease_until: datetime | None = None,
        turn_screen_context: str | None = None,
        blocks: list[dict] | None = None,
        interrupted: bool = False,
        has_image: bool = False,
        decision: dict | None = None,
        audio: dict | None = None,
        screen: str | None = None,
        ui_blocks: list[dict] | None = None,
        announce: bool = True,
    ) -> Message:
        """Store a message and publish it on the user's chat topic.

        `announce=False` stores only: the caller then calls `announce(msg)` when it chooses (MAG-850).
        """
        msg = await message_repo.create(
            user_id,
            role,
            content,
            context_id,
            message_id=message_id,
            client_key=client_key,
            turn_lease_until=turn_lease_until,
            turn_screen_context=turn_screen_context,
            blocks=blocks,
            interrupted=interrupted,
            has_image=has_image,
            decision=decision,
            audio=audio,
            screen=screen,
            ui_blocks=ui_blocks,
        )
        if announce:
            await self.announce(msg)
        return msg

    async def announce(self, msg: Message) -> None:
        """Publish a stored message on Mercure, then write it into the conversation's memory file."""
        try:
            await self.publisher.publish(topics.for_user(topics.CHAT, msg.user_id), msg.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish message to Mercure: {e}")

        await conversations.record(msg)

    async def answer_question(
        self, user_id: str, question_id: str, choices: list[str]
    ) -> tuple[str, Message | None, dict | None]:
        """Settle a question block once, then publish the updated message: every client replaces the card (MAG-717)."""
        status, msg, block = await message_repo.answer_question(user_id, question_id, choices)
        if status == "ok":
            try:
                await self.publisher.publish(topics.for_user(topics.CHAT, user_id), msg.to_dict())
            except Exception as e:
                logger.warning(f"Failed to publish answered question to Mercure: {e}")
        return status, msg, block

    async def mark_digest_opened(self, user_id: str, message_id: str) -> str:
        """Set `openedAt` on a digest block once, and publish the message on `ok`: the Home tile goes away (MAG-808)."""
        status, msg = await message_repo.mark_digest_opened(user_id, message_id)
        if status == "ok":
            try:
                await self.publisher.publish(topics.for_user(topics.CHAT, user_id), msg.to_dict())
            except Exception as e:
                logger.warning(f"Failed to publish the opened digest to Mercure: {e}")
        return status

    async def mark_interrupted(self, message_id: str, content: str) -> Message | None:
        """Cut a stored answer down to what was said, and publish it: other windows replace the full text (MAG-223)."""
        msg = await message_repo.mark_interrupted(message_id, content)
        if msg is None:
            return None
        try:
            await self.publisher.publish(topics.for_user(topics.CHAT, msg.user_id), msg.to_dict())
        except Exception as e:
            logger.warning(f"Failed to publish message to Mercure: {e}")
        return msg

    async def delete_for_user(self, message_id: str, user_id: str) -> bool:
        """Remove one of the user's messages, tell the chat stream; `False` if not theirs or absent (MAG-342)."""
        if not await message_repo.delete_for_user(message_id, user_id):
            return False
        await self._publish_deleted(user_id, [message_id])
        return True

    async def delete_many_for_user(self, message_ids: list[str], user_id: str) -> list[str]:
        """Remove some of the user's messages in one go and tell the chat stream (MAG-320)."""
        deleted = await message_repo.delete_many_for_user(message_ids, user_id)
        if deleted:
            await self._publish_deleted(user_id, deleted)
        return deleted

    async def update_context(self, message_id: str, context_id: str) -> None:
        """Retroactively tag a message with a context ID."""
        user_id = await message_repo.update_context(message_id, context_id)
        if user_id:
            # The question is on file as soon as its thread is known, before the turn that answers it runs.
            await conversations.record_thread(user_id, context_id)

    async def _publish_deleted(self, user_id: str, message_ids: list[str]) -> None:
        try:
            await self.publisher.publish(
                topics.for_user(topics.CHAT, user_id), {"deleted": True, "contextId": None, "messageIds": message_ids}
            )
        except Exception as e:
            logger.warning(f"Failed to publish message deletion to Mercure: {e}")


message_service = MessageService()
