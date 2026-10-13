"""User ids are ULIDs: the API keys its entities that way and the JWT `sub` carries one."""

import re

ULID_PATTERN = re.compile(r"^[0-7][0-9A-HJKMNP-TV-Z]{25}$", re.IGNORECASE)


def is_ulid(value: object) -> bool:
    return isinstance(value, str) and ULID_PATTERN.fullmatch(value) is not None
