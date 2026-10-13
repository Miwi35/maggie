from datetime import UTC, datetime
from pathlib import Path

import pytest

from app.memory import paths
from app.memory.paths import UnsafePath
from tests.memory.conftest import USER_A, USER_B


class TestCleanRelative:
    @pytest.mark.parametrize(
        "rel",
        [
            "../x.md",
            "a/../../x.md",
            "a/./b.md",
            "/etc/passwd.md",
            "a\\b.md",
            "a//b.md",
            ".cache/x.md",
            "a/.secret.md",
            "a/ b.md",
            "a\x00b.md",
            "",
            "..",
            "a/b/c/d/e/f/g.md",
        ],
    )
    def test_refuses_what_leaves_or_hides(self, rel):
        with pytest.raises(UnsafePath):
            paths.clean_relative(rel)

    def test_keeps_accents_and_normalises(self):
        assert paths.clean_relative("Famille/Françoise n'aime pas les roses.md") == (
            "Famille/Françoise n'aime pas les roses.md"
        )
        decomposed = "é.md"
        assert paths.clean_relative(decomposed) == "é.md"

    def test_refuses_a_name_over_the_byte_limit(self):
        with pytest.raises(UnsafePath):
            paths.clean_relative("é" * 200 + ".md")


class TestUserPrefix:
    @pytest.mark.parametrize("user", ["../x", "a/b", "", "user", "01J9Z3K5Q8W2X4Y6A7B8C9D0E1/..", " " * 26])
    def test_only_a_ulid_is_a_prefix(self, user):
        with pytest.raises(UnsafePath):
            paths.bucket_prefix(user)

    def test_key_round_trips(self):
        key = paths.bucket_key(USER_A, "Famille/Cadeau.md")
        assert key == f"{USER_A}/Mémoire/Famille/Cadeau.md"
        assert paths.relative_from_key(USER_A, key) == "Famille/Cadeau.md"
        assert paths.user_of_key(key) == USER_A

    def test_another_users_key_is_not_mine(self):
        assert paths.relative_from_key(USER_B, f"{USER_A}/Mémoire/x.md") is None
        assert paths.user_of_key("skills/x.md") is None
        assert paths.user_of_key(f"{USER_A}/Autre/x.md") is None

    def test_a_key_that_steps_out_is_ignored(self):
        assert paths.relative_from_key(USER_A, f"{USER_A}/Mémoire/../{USER_B}/Mémoire/x.md") is None


class TestLocalPath:
    def test_stays_inside_the_users_folder(self, tmp_path: Path):
        target = paths.local_path(tmp_path, USER_A, "Famille/Cadeau.md")
        assert target.is_relative_to((tmp_path / USER_A / "Mémoire").resolve())

    def test_a_symlink_out_of_the_folder_is_refused(self, tmp_path: Path):
        base = tmp_path / USER_A / "Mémoire"
        base.mkdir(parents=True)
        outside = tmp_path / "outside"
        outside.mkdir()
        (base / "lien").symlink_to(outside)
        with pytest.raises(UnsafePath):
            paths.local_path(tmp_path, USER_A, "lien/x.md")

    def test_a_filename_with_dots_and_separators_cannot_escape(self, tmp_path: Path):
        stem = paths.filename_for("../../etc/passwd")
        assert "/" not in stem and not stem.startswith(".")
        target = paths.local_path(tmp_path, USER_A, stem + ".md")
        assert target.parent == (tmp_path / USER_A / "Mémoire").resolve()


class TestFilenames:
    def test_only_separators_and_controls_go(self):
        assert paths.filename_for("Françoise: fleurs/cadeau\\ok\x07") == "Françoise fleurscadeauok"

    def test_empty_becomes_untitled_and_leading_dots_go(self):
        assert paths.filename_for("///") == paths.UNTITLED
        assert paths.filename_for("..caché") == "caché"

    def test_length_is_bounded_in_bytes(self):
        assert len(paths.filename_for("é" * 500).encode()) <= paths.MAX_STEM_BYTES

    def test_collisions_get_a_suffix_and_case_counts(self):
        assert paths.unique_relative("A", "Fleurs", ["A/fleurs.md"]) == "A/Fleurs-2.md"
        assert paths.unique_relative("A", "Fleurs", ["A/fleurs.md", "A/Fleurs-2.md"]) == "A/Fleurs-3.md"

    def test_a_root_fiche_cannot_take_a_reserved_name(self):
        assert paths.unique_relative("", "journal", []) == "journal-2.md"
        assert paths.unique_relative("", "Conflits", []) == "Conflits-2.md"

    def test_fiche_in_a_reserved_folder_is_refused(self):
        with pytest.raises(UnsafePath):
            paths.fiche_relative(["Conversations"], "x", [])

    def test_conversation_and_conflict_names(self):
        when = datetime(2026, 10, 4, 18, 14, tzinfo=UTC)
        assert paths.conversation_relative(when, "Cadeau fête des mères", []) == (
            "Conversations/2026/10/2026-10-04 18h14 — Cadeau fête des mères.md"
        )
        assert paths.conflict_relative("Famille/Roses.md", when, []) == (
            "Conflits/Roses — version de Maggie 2026-10-04 18h14.md"
        )


class TestKinds:
    def test_what_the_index_reads(self):
        assert paths.is_indexable("Famille/Roses.md")
        assert paths.is_indexable("Conversations/2026/10/x.md")
        assert not paths.is_indexable("sommaire.md")
        assert not paths.is_indexable("Conflits/x.md")
        assert not paths.is_syncable("notes.txt")
        assert paths.kind_of("Conversations/2026/10/x.md") == "conversation"
        assert paths.kind_of("Famille/x.md") == "fiche"

    def test_write_rules(self):
        assert paths.check_writable("Famille/x.md") == "Famille/x.md"
        for rel in ("journal.md", "LISEZMOI.md", "Conflits/x.md", "Conversations/2026/10/x.md", "x.txt", "journal.md"):
            with pytest.raises(UnsafePath):
                paths.check_writable(rel)
        assert paths.check_writable("Conversations/2026/10/x.md", conversation=True)
        assert paths.check_writable("journal.md", generated=True)
        with pytest.raises(UnsafePath):
            paths.check_writable("Conversations.md")
