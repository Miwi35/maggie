from typing import Literal

from pydantic import BaseModel, Field

from app.llm.image import ChatImage
from app.llm.message_reply import ReplyTo


class ChatRequest(BaseModel):
    # Non-empty: an empty message has nothing to route, nothing to answer, and the model
    # refuses a conversation whose only turn is an empty string — a 422 naming the field
    # beats « Désolé, une erreur est survenue » (MAG-13).
    message: str = Field(min_length=1)

    # What the screen behind the assistant overlay was showing (MAG-30). Its own field,
    # not a block glued to `message`: what is stored is what is displayed, by every
    # client, so a block inside the message is a bubble full of the page it was about.
    screen_context: str | None = None

    # One per message the client composes, kept for every retry of it (MAG-344): a request
    # that failed on the way back may have been received, and sending it again must not
    # make a second message — or a second answer.
    idempotency_key: str | None = Field(default=None, min_length=1, max_length=64)

    # The screenshot the assistant was summoned over (MAG-214): read by the model on this
    # turn, never stored — `app/llm/image.py` holds the policy.
    image: ChatImage | None = None

    # The id of the user's last message, when this one is its correction (MAG-320): that
    # message and its answer are taken back, from the thread and from the model's history.
    replaces: str | None = Field(default=None, min_length=1, max_length=26)

    # The answer will be read aloud (the voice overlay, ADR-014): Maggie writes it for the ear, with
    # the voice's delivery tags. A turn taken up again after a restart is answered as written text.
    spoken: bool = False

    # The message of Maggie this one answers (MAG-683): dictated on a notification in the car. The model
    # reads it as it would the phone's button, or as an instruction; what is stored is what was said.
    in_reply_to: ReplyTo | None = None

    # The phone is projecting to Android Auto, or the answer was dictated from the car (MAG-684): the
    # answer is asked short and made for the ear. A turn taken up again after a restart loses it, like `spoken`.
    in_car: bool = False


class ChatResponse(BaseModel):
    response: str
    tool_calls: list[dict] = []
    messages: list[dict] = []
    # `True` when our code failed the turn, `provider_unavailable` when the model's provider did,
    # with `provider_reason` saying why — what the smoke test warns on instead of failing (MAG-448).
    error: bool | Literal["provider_unavailable"] | None = None
    provider_reason: str | None = None
