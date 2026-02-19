import logging

import aio_pika

from app.queue.connection import PROACTION_QUEUE, get_channel

logger = logging.getLogger(__name__)


async def publish_proaction(proaction_id: str) -> None:
    """Publish a proaction ID to the RabbitMQ queue for async execution."""
    channel = get_channel()
    await channel.default_exchange.publish(
        aio_pika.Message(
            body=proaction_id.encode(),
            delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
        ),
        routing_key=PROACTION_QUEUE,
    )
    logger.info(f"Published proaction {proaction_id} to queue")
