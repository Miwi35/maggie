import json
import logging

import aio_pika

from app.proactions.api import Proaction
from app.queue.connection import PROACTION_QUEUE, get_channel

logger = logging.getLogger(__name__)


async def publish_proaction(proaction: Proaction) -> None:
    """Publish a claimed proaction to the RabbitMQ queue for async execution.

    The whole proaction travels, not its id: the API already marked it running, and the consumer
    needs nothing more to run it.
    """
    channel = get_channel()
    await channel.default_exchange.publish(
        aio_pika.Message(
            body=json.dumps(proaction.to_dict()).encode(),
            delivery_mode=aio_pika.DeliveryMode.PERSISTENT,
        ),
        routing_key=PROACTION_QUEUE,
    )
    logger.info(f"Published proaction {proaction.id} to queue")
