from datetime import UTC, datetime

import pytest

from app.memory.paths import (
    MAX_SLUG_LENGTH,
    OwnedKey,
    UnsafePath,
    archive_key,
    check_user_id,
    classify_key,
    conflict_key,
    key_belongs_to,
    note_key,
    slugify,
    stem,
    summary_key,
    with_suffix,
)
from app.memory.ulid import is_ulid, new_ulid

USER = new_ulid()


class TestUlid:
    def test_a_new_ulid_is_valid_and_unique(self):
        first, second = new_ulid(), new_ulid()

        assert is_ulid(first) and len(first) == 26
        assert first != second

    @pytest.mark.parametrize("value", ["", "abc", "../x", None, 12, "U" * 26, "0" * 25])
    def test_what_is_not_a_ulid(self, value):
        assert not is_ulid(value)


class TestSlugify:
    def test_accents_are_folded_and_case_and_spaces_stay(self):
        assert slugify("Allergies à l'arachide été") == "Allergies a l'arachide ete"

    @pytest.mark.parametrize("title", ["", "   ", "...", "///", "\x00\x01"])
    def test_an_empty_title_still_has_a_name(self, title):
        assert slugify(title) == "note"

    def test_it_is_bounded(self):
        slug = slugify("a" * 500)

        assert len(slug) == MAX_SLUG_LENGTH

    def test_separators_and_dots_cannot_leave_the_prefix(self):
        assert slugify("../../etc/passwd") == "etcpasswd"
        assert slugify("a\\b/c") == "abc"
        assert "/" not in slugify("x/y") and not slugify(".hidden").startswith(".")

    def test_control_characters_and_runs_of_spaces_go(self):
        assert slugify("a\tb \n c\x07") == "a b c"

    @pytest.mark.parametrize("title", ["SOMMAIRE", "sommaire", "Sommaire"])
    def test_the_generated_listing_name_is_reserved(self, title):
        assert slugify(title).endswith("-2")

    def test_a_trailing_dot_or_space_after_truncation_is_removed(self):
        assert not slugify("a" * (MAX_SLUG_LENGTH - 1) + ". tail").endswith((" ", "."))

    def test_collisions_get_a_numeric_suffix(self):
        base = slugify("Café")

        assert [with_suffix(base, n) for n in (0, 1, 2, 3)] == ["Cafe", "Cafe", "Cafe-2", "Cafe-3"]


class TestKeyBuilders:
    def test_the_keys(self):
        at = datetime(2026, 3, 4, 5, 6, 7, 8, tzinfo=UTC)

        assert note_key(USER, "a") == f"{USER}/a.md"
        assert archive_key(USER, "a") == f"{USER}/archive/a.md"
        assert summary_key(USER) == f"{USER}/SOMMAIRE.md"
        assert conflict_key(USER, "a", at) == f"{USER}/conflits/a-20260304T050607000008Z.md"

    @pytest.mark.parametrize("user_id", ["..", "a/b", "/abs", "", "../" + "0" * 23, " " + "0" * 25, "x.y"])
    def test_a_user_id_that_could_escape_is_refused(self, user_id):
        with pytest.raises(UnsafePath):
            check_user_id(user_id)
        for build in (lambda: note_key(user_id, "a"), lambda: archive_key(user_id, "a"), lambda: summary_key(user_id)):
            with pytest.raises(UnsafePath):
                build()
        with pytest.raises(UnsafePath):
            conflict_key(user_id, "a", datetime.now(UTC))

    def test_a_ulid_user_id_is_accepted(self):
        assert check_user_id(USER) == USER

    def test_stem(self):
        assert stem(f"{USER}/archive/Allergies.md") == "Allergies"

    def test_key_belongs_to_checks_the_prefix_and_dotdot(self):
        assert key_belongs_to(USER, f"{USER}/a.md")
        assert not key_belongs_to(USER, f"{new_ulid()}/a.md")
        assert not key_belongs_to(USER, f"{USER}/../x.md")
        with pytest.raises(UnsafePath):
            key_belongs_to("..", "../a.md")


class TestClassifyKey:
    def test_an_active_note(self):
        assert classify_key(f"{USER}/a.md") == OwnedKey(USER, "active", f"{USER}/a.md")

    def test_an_archived_note(self):
        assert classify_key(f"{USER}/archive/a.md") == OwnedKey(USER, "archived", f"{USER}/archive/a.md")

    @pytest.mark.parametrize(
        "suffix",
        [
            "SOMMAIRE.md",
            "sommaire.md",
            "conflits/a-20260101T000000000000Z.md",
            "archive/deeper/a.md",
            "a/b/c.md",
            "note.txt",
            "note",
            "archive/note.txt",
            "archive/",
            ".md",
            "archive/.md",
            "",
            "./a.md",
            "../a.md",
            "archive/../a.md",
            "other/a.md",
        ],
    )
    def test_everything_else_is_left_alone(self, suffix):
        assert classify_key(f"{USER}/{suffix}") is None

    @pytest.mark.parametrize(
        "key",
        ["competences/skill.md", "competences/x/SKILL.md", "/a.md", "a.md", "", "not-a-user/a.md", f"/{USER}/a.md"],
    )
    def test_a_first_segment_that_is_not_a_user_is_not_ours(self, key):
        assert classify_key(key) is None
