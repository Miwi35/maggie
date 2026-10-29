"""Maggie's voice messages (MAG-685): a text said in the user's voice, kept in the bucket, joined to a message.

The model decides to send one — a directive, or the user asking for a vocal — by calling
`send_voice_message`. The MP3 goes to the memory bucket under `audio/<userId>/<messageId>.mp3`,
the message to the thread with its attachment, and the clients play it through a short signed URL
(`GET /agent/messages/{id}/audio`), never a public one.
"""

import json
import logging
import uuid
from datetime import UTC, datetime, timedelta

from app.config import settings
from app.db.message_repository import message_repo
from app.db.models import AUDIO_TYPE, Message
from app.db.user_setting_repository import user_setting_repo
from app.memory.bucket import Bucket, BucketError, BucketUnavailable
from app.memory.service import memory_service
from app.tts.providers import ProviderFailedError
from app.tts.synthesis import DEFAULT_VOICE, SpeechUnavailableError, synthesize_file
from app.tts.text import strip_delivery_tags, to_spoken_text

logger = logging.getLogger(__name__)

AUDIO_PREFIX = "audio/"

SEND_VOICE_MESSAGE_TOOL = {
    "name": "send_voice_message",
    "description": (
        "Send the user a voice message: the text is spoken in their chosen voice, recorded, and lands in "
        "the thread as an audio message they can play now or later. Use it when the user asks for a vocal "
        "(« envoie-moi un vocal… ») or a directive asks for one (a morning digest « en vocal »). Write the "
        "text for the ear: plain sentences, no Markdown, no list, no table, no URL. The message is in the "
        "thread once the tool answers: do not repeat its text in your reply, a short word is enough."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "text": {"type": "string", "description": "What Maggie says, written to be heard"},
        },
        "required": ["text"],
    },
}

# What the model reads back when the vocal cannot be made. French: it relays them to the user.
STORAGE_OFF = "Les messages vocaux sont indisponibles : le stockage de la mémoire n'est pas configuré."
STORAGE_DOWN = "Le vocal n'a pas pu être enregistré : le stockage ne répond pas. Réessaie plus tard."
SPEECH_DOWN = "Le vocal n'a pas pu être enregistré : la synthèse vocale est indisponible."
NOTHING_TO_SAY = "Le texte du vocal est vide : il n'y a rien à dire."


class VoiceMessageError(Exception):
    """The vocal could not be made; the message is what the model is told."""


def audio_key(user_id: str, message_id: str) -> str:
    return f"{AUDIO_PREFIX}{user_id}/{message_id}.mp3"


def _bucket() -> Bucket | None:
    return memory_service.bucket


# MPEG audio, Layer III: kbps by bitrate index, for MPEG-1 and for MPEG-2/2.5.
_BITRATES_V1 = (0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
_BITRATES_V2 = (0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)


def mp3_duration_ms(data: bytes) -> int:
    """How long an MP3 plays, from its first frame's bitrate: every provider here sends a constant one."""
    start = 0
    if data[:3] == b"ID3" and len(data) >= 10:
        start = 10 + ((data[6] & 0x7F) << 21 | (data[7] & 0x7F) << 14 | (data[8] & 0x7F) << 7 | (data[9] & 0x7F))
    for offset in range(start, len(data) - 3):
        if data[offset] != 0xFF or data[offset + 1] & 0xE0 != 0xE0:
            continue
        version = (data[offset + 1] >> 3) & 0x03  # 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5, 1 reserved
        index = data[offset + 2] >> 4
        if version == 1 or index in (0, 15):
            continue
        kbps = (_BITRATES_V1 if version == 3 else _BITRATES_V2)[index]
        return round((len(data) - offset) * 8 / kbps)
    return 0


async def _voice_of(user_id: str) -> str:
    setting = await user_setting_repo.get(user_id)
    return setting.tts_voice if setting and setting.tts_voice else DEFAULT_VOICE


async def send_voice_message(user_id: str, text: str, context_id: str | None = None) -> Message:
    """Say `text` in the user's voice, keep the MP3 in the bucket, and post the message that carries it."""
    bucket = _bucket()
    if bucket is None:
        raise VoiceMessageError(STORAGE_OFF)
    spoken = to_spoken_text(text or "")[: settings.voice_message_max_chars]
    transcript = strip_delivery_tags(spoken)
    if not transcript:
        raise VoiceMessageError(NOTHING_TO_SAY)

    try:
        audio = await synthesize_file(spoken, await _voice_of(user_id))
    except (SpeechUnavailableError, ProviderFailedError) as e:
        logger.warning("Voice message for %s: synthesis failed (%s)", user_id, e)
        raise VoiceMessageError(SPEECH_DOWN) from e
    if not audio:
        raise VoiceMessageError(SPEECH_DOWN)

    message_id = uuid.uuid4().hex[:26]
    try:
        await bucket.put(audio_key(user_id, message_id), audio, content_type=AUDIO_TYPE)
    except BucketError as e:
        logger.warning("Voice message for %s: the bucket refused the audio (%s)", user_id, e)
        raise VoiceMessageError(STORAGE_DOWN) from e

    attachment = {
        "type": AUDIO_TYPE,
        "durationMs": mp3_duration_ms(audio),
        "size": len(audio),
        "transcript": transcript,
    }
    return await message_repo.create(
        user_id, "assistant", transcript, context_id, message_id=message_id, audio=attachment
    )


async def handle_send_voice_message(arguments: dict, user_id: str, context_id: str | None = None) -> str:
    """The tool: the message sent, or the reason it could not be — never an exception the turn would stop on."""
    try:
        message = await send_voice_message(user_id, str(arguments.get("text") or ""), context_id)
    except VoiceMessageError as e:
        return json.dumps({"error": str(e)}, ensure_ascii=False)
    audio = message.audio or {}
    return json.dumps(
        {"sent": True, "messageId": message.id, "durationMs": audio.get("durationMs", 0)}, ensure_ascii=False
    )


async def signed_audio_url(message: Message) -> tuple[str, datetime]:
    """A short-lived URL to play the message's audio, and when it stops; `VoiceMessageError` without a bucket."""
    bucket = _bucket()
    if bucket is None:
        raise VoiceMessageError(STORAGE_OFF)
    ttl = settings.voice_message_url_ttl_seconds
    try:
        url = await bucket.signed_url(audio_key(message.user_id, message.id), ttl)
    except BucketUnavailable as e:
        raise VoiceMessageError(STORAGE_DOWN) from e
    return url, datetime.now(UTC) + timedelta(seconds=ttl)


async def purge_voice_messages(now: datetime | None = None) -> dict[str, int]:
    """Remove the audio older than the retention, from the bucket and from the messages that pointed at it.

    Read from the bucket rather than the table, so an MP3 whose message was deleted goes too.
    """
    bucket = _bucket()
    if bucket is None:
        return {"objects": 0, "messages": 0}
    before = (now or datetime.now(UTC)) - timedelta(days=settings.voice_message_retention_days)
    messages = await message_repo.drop_audio_before(before)
    objects = 0
    for obj in await bucket.list_objects(AUDIO_PREFIX):
        if obj.last_modified < before:
            await bucket.delete(obj.key)
            objects += 1
    if objects or messages:
        logger.info("Voice messages purged: %d audio files, %d attachments", objects, messages)
    return {"objects": objects, "messages": messages}
