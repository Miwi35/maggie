"""The picture a turn carries, and what is checked of it (MAG-214)."""

import base64

import pytest
from pydantic import ValidationError

from app.llm.image import MAX_BYTES, ChatImage, with_image

JPEG = base64.b64encode(b"\xff\xd8\xff" + b"x" * 2045).decode()


class TestWhatIsAccepted:
    def test_a_jpeg_png_or_webp(self):
        for media_type in ("image/jpeg", "image/png", "image/webp"):
            assert ChatImage(media_type=media_type, data=JPEG).media_type == media_type

    def test_not_another_type(self):
        with pytest.raises(ValidationError):
            ChatImage(media_type="image/gif", data=JPEG)

    def test_not_something_that_is_not_base64(self):
        with pytest.raises(ValidationError):
            ChatImage(media_type="image/jpeg", data="ceci n'est pas une image")

    def test_not_an_empty_one(self):
        with pytest.raises(ValidationError):
            ChatImage(media_type="image/jpeg", data="")

    def test_not_one_above_the_model_ceiling(self):
        too_big = base64.b64encode(b"x" * (MAX_BYTES + 3)).decode()

        with pytest.raises(ValidationError):
            ChatImage(media_type="image/jpeg", data=too_big)

    def test_one_at_the_ceiling(self):
        assert ChatImage(media_type="image/jpeg", data=base64.b64encode(b"x" * MAX_BYTES).decode()).size == MAX_BYTES


class TestWhatTheModelAndTheLogGet:
    def test_the_turn_is_the_picture_then_the_question(self):
        image = ChatImage(media_type="image/jpeg", data=JPEG)

        assert with_image("c'est quoi ?", image) == [
            {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": JPEG}},
            {"type": "text", "text": "c'est quoi ?"},
        ]

    def test_the_log_gets_a_type_and_a_size_never_the_bytes(self):
        described = ChatImage(media_type="image/jpeg", data=JPEG).describe()

        assert described == "image/jpeg, 2 KB"
        assert JPEG[:16] not in described
