"""ULIDs for note ids: the API keys its entities the same way, and the file's `id` is the note's identity."""

import os
import re
import time

_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
ULID_PATTERN = re.compile(r"^[0-7][0-9A-HJKMNP-TV-Z]{25}$", re.IGNORECASE)


def new_ulid() -> str:
    """48 bits of millisecond time then 80 random bits, in Crockford base32."""
    value = (int(time.time() * 1000) << 80) | int.from_bytes(os.urandom(10), "big")
    return "".join(_ALPHABET[(value >> shift) & 31] for shift in range(125, -1, -5))


def is_ulid(value: object) -> bool:
    return isinstance(value, str) and ULID_PATTERN.fullmatch(value) is not None
