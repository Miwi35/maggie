import logging

import aio_pika

from app.db.message_repository import message_repo
from app.db.proaction_repository import proaction_repo
from app.llm.context_summary import context_summarizer
from app.llm.gateway import LLMGateway
from app.queue.connection import PROACTION_QUEUE, get_channel

logger = logging.getLogger(__name__)


async def start_consumer() -> None:
    """Start consuming proaction messages from the queue.

    Each message contains a proaction ID. The consumer:
    1. Marks the proaction as running
    2. Calls LLMGateway.proaction() with the proaction's prompt
    3. Stores what came out in the conversation thread it belongs to (MAG-14)
    4. Marks it as completed (with response) or failed (with error)
    """
    channel = get_channel()
    queue = await channel.declare_queue(PROACTION_QUEUE, durable=True)
    gateway = LLMGateway()

    async def on_message(message: aio_pika.abc.AbstractIncomingMessage) -> None:
        async with message.process():
            proaction_id = message.body.decode()
            logger.info(f"Consuming proaction {proaction_id}")

            proaction = await proaction_repo.get(proaction_id)
            if proaction is None:
                logger.warning(f"Proaction {proaction_id} not found, skipping")
                return

            await proaction_repo.mark_running(proaction_id)

            try:
                result = await gateway.proaction(proaction.prompt, proaction.user_id)
                await proaction_repo.mark_completed(proaction_id, result["response"])
                if result["response"]:
                    # Stored in the thread the gateway routed it to. Without a thread the
                    # message is an orphan: the reply it invites is routed against a
                    # conversation that never mentions it, and the summary of the thread
                    # it belonged to never learns Maggie spoke (MAG-14).
                    context_id = result.get("context_id")
                    await message_repo.create(
                        user_id=proaction.user_id,
                        role="assistant",
                        content=result["response"],
                        context_id=context_id,
                    )
                    # Awaited, unlike on the streamed path: nobody is holding a stream
                    # open here, and the reply to a reminder is the next thing to be
                    # routed against this thread's summary.
                    if context_id:
                        await context_summarizer.maybe_summarize(context_id)
                logger.info(f"Proaction {proaction_id} completed")
            except Exception as e:
                error_msg = str(e)
                logger.error(f"Proaction {proaction_id} failed: {error_msg}")
                await proaction_repo.mark_failed(proaction_id, error_msg)

    await queue.consume(on_message)
    logger.info("Proaction consumer started")
