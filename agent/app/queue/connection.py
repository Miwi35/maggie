import logging

import aio_pika

from app.config import settings

logger = logging.getLogger(__name__)

PROACTION_QUEUE = "agent.proaction.execute"

_connection: aio_pika.abc.AbstractRobustConnection | None = None
_channel: aio_pika.abc.AbstractChannel | None = None


async def connect() -> None:
    """Establish a robust connection to RabbitMQ and declare the proaction queue."""
    global _connection, _channel
    _connection = await aio_pika.connect_robust(settings.rabbitmq_url)
    _channel = await _connection.channel()
    await _channel.declare_queue(PROACTION_QUEUE, durable=True)
    logger.info("Connected to RabbitMQ")


async def disconnect() -> None:
    """Close the RabbitMQ connection."""
    global _connection, _channel
    if _connection and not _connection.is_closed:
        await _connection.close()
    _connection = None
    _channel = None
    logger.info("Disconnected from RabbitMQ")


def get_channel() -> aio_pika.abc.AbstractChannel:
    if _channel is None:
        raise RuntimeError("RabbitMQ not connected")
    return _channel
