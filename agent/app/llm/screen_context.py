"""The screen the assistant was summoned from, kept out of the conversation (MAG-30).

Android hands the assistant what the screen behind the overlay was showing — the app, the
page, the visible text. The model needs it to answer « c'est quoi ce produit ? »; the
conversation must not keep it.

MAG-30 first shipped it as a block prefixed to the message, because the chat routes take a
single string. The block was therefore *stored* as the user's message, and every reader of
the history showed it: the mobile chat on reload, the Mercure echo, the web chat. The
recette was refused on exactly that — a bubble holding a shop page, tracking parameters
and all, above the question that was actually asked.

So the block travels in its own field (`screen_context`), what is stored and published is
what was said, and `build_history` puts the block back on the one turn it belongs to.

[split] is the bridge for a client that still glues the block to the message: an installed
app keeps sending one string until it is updated, and its exchanges must not dirty the
history either.
"""

HEADER = "[Contexte de l'écran]"
"""The first line the mobile app writes, and the marker [split] recognises."""

MAX_CHARS = 4000
"""What one invocation may add to the prompt.

The app caps its own collection well below this (40 lines, 1500 characters), so this is
the ceiling on a client that does not — truncated rather than refused, because a 422 would
cost the user the whole turn to save the tail of a page.
"""


def split(message: str, screen_context: str | None = None) -> tuple[str, str | None]:
    """`(what was said, the screen block)` out of what a client sent.

    `screen_context` is the field, and it wins: a message that merely looks like a block is
    then the user's own text. Without it, a message *starting* with [HEADER] is one of the
    glued ones and is cut at the first blank line — which works because every line of the
    block is single-spaced, the app collapsing the whitespace of each entry it collects.

    A block with nothing said after it is left whole: better an ugly bubble than an empty
    one, and better a question kept than a question lost.
    """
    bounded = _bounded(screen_context)
    if bounded:
        return message, bounded

    if not message.startswith(HEADER):
        return message, None

    block, _, said = message.partition("\n\n")
    if not said.strip():
        return message, None

    return said, _bounded(block)


def attach(content: str, screen_context: str | None) -> str:
    """The turn as the model reads it: the screen first, then what was said about it."""
    bounded = _bounded(screen_context)
    return f"{bounded}\n\n{content}" if bounded else content


def _bounded(block: str | None) -> str | None:
    if not block or not block.strip():
        return None
    return block[:MAX_CHARS]
