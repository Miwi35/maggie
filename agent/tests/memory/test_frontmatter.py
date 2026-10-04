from datetime import UTC, datetime

import pytest

from app.memory.frontmatter import NoteDoc, UnreadableNote, body_hash, parse_note, render_note
from app.memory.ulid import new_ulid


def full_doc() -> NoteDoc:
    return NoteDoc(
        id=new_ulid(),
        title="Allergies : arachide",
        body="Allergique aux arachides.\n\n- pas de sauce satay",
        summary="Allergie alimentaire",
        tags=["santé", "cuisine"],
        links=["Recettes", "Courses"],
        sources=["conversation 2026-01-01"],
        pinned=True,
        importance=3,
        confirmed_at=datetime(2026, 1, 2, 3, 4, 5, tzinfo=UTC),
        forget_after=datetime(2027, 1, 2, tzinfo=UTC),
        created_at=datetime(2025, 12, 1, tzinfo=UTC),
        archived_at=datetime(2026, 2, 1, tzinfo=UTC),
        archive_reason="obsolète",
        extra={"couleur": "rouge", "niveau": {"a": 1}},
    )


class TestRoundTrip:
    def test_every_field_survives(self):
        doc = full_doc()

        assert parse_note(render_note(doc), "fallback") == doc

    def test_a_minimal_note_renders_only_what_it_has(self):
        text = render_note(NoteDoc(id=new_ulid(), title="T", body="B"))

        assert "tags" not in text and "pinned" not in text and "importance" not in text
        assert text.endswith("---\n\nB\n")

    def test_naive_datetimes_are_written_as_utc(self):
        doc = NoteDoc(id=new_ulid(), title="T", confirmed_at=datetime(2026, 1, 1, 10, 0))

        parsed = parse_note(render_note(doc), "x")

        assert parsed.confirmed_at == datetime(2026, 1, 1, 10, 0, tzinfo=UTC)

    def test_owner_keys_are_kept_and_rewritten(self):
        note_id = new_ulid()
        text = f"---\nid: {note_id}\ntitle: T\nauteur: Meven\ncustom:\n  - 1\n---\n\nBody\n"

        parsed = parse_note(text, "x")
        assert parsed.extra == {"auteur": "Meven", "custom": [1]}

        again = parse_note(render_note(parsed), "x")
        assert again.extra == parsed.extra

    def test_a_body_with_a_fence_like_line_is_kept(self):
        doc = NoteDoc(id=new_ulid(), title="T", body="before\n---\nafter\n---x")

        assert parse_note(render_note(doc), "x").body == doc.body


class TestParsingLeniency:
    def test_no_frontmatter_means_no_id_and_the_file_name_as_title(self):
        doc = parse_note("# Juste du texte\n\nSans en-tête.\n", "Mon fichier")

        assert doc.id is None
        assert doc.title == "Mon fichier"
        assert doc.body.startswith("# Juste du texte")

    def test_a_missing_title_falls_back_to_the_file_name(self):
        assert parse_note(f"---\nid: {new_ulid()}\n---\nbody", "stem").title == "stem"

    def test_a_blank_title_falls_back_too(self):
        assert parse_note("---\ntitle: '  '\n---\nbody", "stem").title == "stem"

    def test_an_empty_frontmatter_block_is_a_hand_made_note(self):
        doc = parse_note("---\n---\nbody", "stem")

        assert doc.id is None and doc.body == "body"

    def test_crlf_and_bom_are_tolerated(self):
        text = f"﻿---\r\nid: {new_ulid()}\r\ntitle: T\r\n---\r\n\r\nBody\r\n"

        doc = parse_note(text, "x")

        assert doc.title == "T" and doc.body == "Body"

    def test_a_lowercase_id_is_normalised(self):
        note_id = new_ulid()

        assert parse_note(f"---\nid: {note_id.lower()}\n---\n", "x").id == note_id

    def test_scalars_where_lists_are_expected_are_wrapped(self):
        doc = parse_note("---\ntags: santé\nlinks:\nsources: []\n---\n", "x")

        assert doc.tags == ["santé"] and doc.links == [] and doc.sources == []

    def test_dates_may_be_plain_yaml_dates(self):
        doc = parse_note("---\nconfirmed_at: 2026-01-02\n---\n", "x")

        assert doc.confirmed_at == datetime(2026, 1, 2, tzinfo=UTC)

    def test_a_z_suffixed_iso_date(self):
        doc = parse_note("---\ncreated_at: '2026-01-02T03:04:05Z'\n---\n", "x")

        assert doc.created_at == datetime(2026, 1, 2, 3, 4, 5, tzinfo=UTC)


class TestUnreadable:
    @pytest.mark.parametrize(
        "text",
        [
            "---\ntitle: [unclosed\n---\nbody",
            "---\ntitle: T\nbody never closed",
            "---\n- a\n- b\n---\nbody",
            "---\njust a string\n---\nbody",
            "---\nid: not-a-ulid\n---\nbody",
            "---\nid: 12\n---\nbody",
            "---\ntags: {a: 1}\n---\nbody",
            "---\ntags: [[1], 2]\n---\nbody",
            "---\npinned: yes please\n---\nbody",
            "---\nimportance: lots\n---\nbody",
            "---\nimportance: [1]\n---\nbody",
            "---\nimportance: true\n---\nbody",
            "---\nconfirmed_at: someday\n---\nbody",
            "---\nforget_after: [1]\n---\nbody",
        ],
    )
    def test_a_broken_header_raises(self, text):
        with pytest.raises(UnreadableNote):
            parse_note(text, "x")


class TestBodyHash:
    def test_it_ignores_surrounding_whitespace_and_frontmatter(self):
        assert body_hash("  a\n") == body_hash("a")
        assert body_hash("a") != body_hash("b")
