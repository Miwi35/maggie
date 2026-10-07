"""The picture a turn carries — the screen the assistant was summoned from (MAG-214).

The policy is in `agent-os/specs/2026-10-07-0900-capture-ecran-au-modele/shape.md`, and
this module is the agent's half of it: an image is a field of the chat request, checked
here, put on the turn being answered by `build_history`, and **never stored**. When the
request ends, it is gone; the message keeps a `has_image` flag and nothing else. A
screenshot can show anything — a bank, a conversation, a prescription — and « nothing is
kept » is the one retention rule no bug, backup or crash can break.

Nor is it logged: [ChatImage.describe] is what the log gets, a type and a size.
"""

import base64
import binascii
from typing import Literal

from pydantic import BaseModel, Field, field_validator

MAX_BYTES = 5 * 1024 * 1024
"""The Anthropic API's own ceiling per image. The app sends ~200 KB (1568 px, JPEG 80)."""

GONE_MARKER = "[capture d'écran jointe à ce message, plus disponible]"
"""What an earlier turn that carried a picture reads as: the question was about something."""


class ChatImage(BaseModel):
    media_type: Literal["image/jpeg", "image/png", "image/webp"]
    # Bounded before decoding: base64 is 4 characters per 3 bytes, so a string longer than
    # this cannot decode under [MAX_BYTES] and is refused without being read.
    data: str = Field(min_length=1, max_length=(MAX_BYTES + 2) // 3 * 4)

    @field_validator("data")
    @classmethod
    def _decodes(cls, data: str) -> str:
        try:
            decoded = base64.b64decode(data, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise ValueError("data is not valid base64") from exc
        if not decoded:
            raise ValueError("data is empty")
        return data

    @property
    def size(self) -> int:
        return len(self.data) * 3 // 4 - self.data.count("=")

    def block(self) -> dict:
        """The image as a Claude content block."""
        return {"type": "image", "source": {"type": "base64", "media_type": self.media_type, "data": self.data}}

    def describe(self) -> str:
        """What the log may say about it: never the bytes."""
        return f"{self.media_type}, {self.size // 1024} KB"


def with_image(content: str, image: ChatImage) -> list[dict]:
    """The turn as the model reads it: the picture first, then what was said about it."""
    return [image.block(), {"type": "text", "text": content}]
