import pytest

from app.memory import carte
from app.memory.carte import Carte, UnreadableFile


def test_parses_a_carte_and_keeps_unknown_keys():
    text = "---\nid: abcdef0123456789\nresume: Les fleurs de Françoise\nevoque: fleurs, cadeau\nvu: oui\n---\n\nCorps.\n"
    parsed = carte.parse(text)
    assert parsed.carte.id == "abcdef0123456789"
    assert parsed.carte.evoque == ["fleurs", "cadeau"]
    assert parsed.carte.extra == {"vu": "oui"}
    assert parsed.body == "Corps."


def test_a_file_without_frontmatter_is_a_hand_written_note():
    parsed = carte.parse("Juste une note.\n")
    assert parsed.carte.id is None and not parsed.has_frontmatter
    assert parsed.body == "Juste une note."


@pytest.mark.parametrize(
    "text",
    ["---\nid: [oops\n---\ntexte", "---\n- a\n- b\n---\ntexte", "---\nid: abc\nresume: x\ntexte jamais fermé", "---\nid: a b\n---\n"],
)
def test_an_unreadable_carte_raises(text):
    with pytest.raises(UnreadableFile):
        carte.parse(text)


def test_with_id_does_not_touch_the_rest():
    text = "---\n# mon commentaire\nresume: x\n---\n\nCorps.\n"
    stamped = carte.with_id(text, "abcdef0123456789")
    assert stamped == "---\nid: abcdef0123456789\n# mon commentaire\nresume: x\n---\n\nCorps.\n"
    assert carte.parse(stamped).carte.id == "abcdef0123456789"


def test_with_id_replaces_an_existing_id_and_wraps_a_bare_note():
    assert "id: neuf12345678" in carte.with_id("---\nid: ancien1234\n---\nx", "neuf12345678")
    wrapped = carte.with_id("Une note.\n", "abcdef0123456789")
    assert wrapped.startswith("---\nid: abcdef0123456789\n---\n") and wrapped.endswith("Une note.\n")


def test_render_round_trips():
    original = Carte(id=carte.new_id(), resume="Roses", personnes=["Françoise"], evoque=["cadeau"], liens=["abcdef01"])
    parsed = carte.parse(carte.render(original, "Elle préfère les pivoines."))
    assert parsed.carte.id == original.id
    assert parsed.carte.personnes == ["Françoise"]
    assert parsed.carte.linked_ids == ["abcdef01"]
    assert parsed.body == "Elle préfère les pivoines."


def test_ids_and_hash():
    assert carte.is_valid_id(carte.new_id()) and not carte.is_valid_id("a/b")
    assert carte.content_hash("a") == carte.content_hash("a") != carte.content_hash("b")
