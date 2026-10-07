"""The screen the assistant was summoned from, kept out of the conversation (MAG-30).

Recette refused the first delivery: the block travelled *inside* the user's message, so
the stored content was the block — and every reader of the history showed it, the mobile
chat on reload, the Mercure echo and the web chat alike. These tests pin the split that
keeps the model's copy and the conversation's copy apart.
"""

from app.llm.screen_context import HEADER, MAX_CHARS, attach, split

BLOCK = f"{HEADER}\nApplication : Chrome (com.android.chrome)\nPage : https://dice.fm/event/x?utm=1"
SAID = "De quoi parle cette page ?"


class TestSplit:
    def test_a_context_in_its_own_field_leaves_the_message_alone(self):
        assert split(SAID, BLOCK) == (SAID, BLOCK)

    def test_no_context_at_all(self):
        assert split(SAID, None) == (SAID, None)
        assert split(SAID, "   ") == (SAID, None)

    def test_a_client_still_glueing_the_block_to_the_message_is_split(self):
        """The installed app sends one string; what gets stored is the question anyway."""
        assert split(f"{BLOCK}\n\n{SAID}", None) == (SAID, BLOCK)

    def test_the_field_wins_over_a_message_that_looks_like_a_block(self):
        assert split(f"{HEADER}\nPage : https://a.b\n\n{SAID}", "Page : https://c.d") == (
            f"{HEADER}\nPage : https://a.b\n\n{SAID}",
            "Page : https://c.d",
        )

    def test_a_block_with_nothing_said_after_it_is_left_whole(self):
        """Better an ugly bubble than an empty one."""
        glued = f"{BLOCK}\n\n   "
        assert split(glued, None) == (glued, None)
        assert split(BLOCK, None) == (BLOCK, None)

    def test_a_message_that_merely_contains_the_header_is_not_a_block(self):
        said = f"explique-moi ce que veut dire {HEADER}\n\ndans tes logs"
        assert split(said, None) == (said, None)

    def test_an_oversized_context_is_bounded_rather_than_refused(self):
        """Losing the user's turn to a 422 would cost more than losing the tail of a page."""
        said, block = split(SAID, "x" * (MAX_CHARS + 500))
        assert said == SAID
        assert block is not None
        assert len(block) == MAX_CHARS


class TestAttach:
    def test_the_block_comes_back_in_front_of_what_was_said(self):
        assert attach(SAID, BLOCK) == f"{BLOCK}\n\n{SAID}"

    def test_nothing_to_attach(self):
        assert attach(SAID, None) == SAID
        assert attach(SAID, "  ") == SAID
