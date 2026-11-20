"""The « data » envelope: text that comes from outside the prompt's author reaches the model wrapped as data.

Memory (profile, recalled cartes) uses it first; tool results, web pages and bank data take `wrap_data` as is.
"""

import re

DATA_TAG = "donnees_externes"

_SOURCE = re.compile(r"^[a-z_]+$")
_TAG = re.compile(rf"<(/?)\s*{DATA_TAG}", re.IGNORECASE)


def wrap_data(text: str, source: str) -> str:
    assert _SOURCE.match(source), f"source of the envelope: lowercase letters and _ only, got {source!r}"
    inside = _TAG.sub(lambda m: f"\u2039{m.group(1)}{DATA_TAG}", text)
    return f'<{DATA_TAG} source="{source}">\n{inside}\n</{DATA_TAG}>'
