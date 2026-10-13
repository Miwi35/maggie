from datetime import UTC, datetime

from app.memory import generated, paths


def test_each_generated_file_says_so_on_its_first_line():
    for text in (generated.readme(), generated.agents(), generated.summary([]), generated.journal_header()):
        assert generated.is_generated_text(text)
        assert generated.GENERATED_MARK in text.split("\n", 1)[0]


def test_a_seal_holds_until_the_file_is_edited():
    text = generated.readme()
    assert generated.is_untouched(text)
    assert not generated.is_untouched(text + "Ma remarque.\n")
    assert not generated.is_untouched(text.replace("Mémoire de Maggie", "Ma mémoire"))
    assert not generated.is_untouched("pas un fichier généré\n")


def test_the_folder_explains_itself_to_an_agent_without_the_app():
    readme, agents = generated.readme(), generated.agents()
    for name in (paths.SUMMARY, paths.JOURNAL, paths.CONVERSATIONS_DIR, paths.CONFLICTS_DIR):
        assert name in readme
    assert "id" in readme and "carte" in readme
    assert "version de la personne" in agents and paths.CONFLICTS_DIR in agents
    assert "compteurs" in agents


def test_summary_lists_one_line_per_fiche():
    text = generated.summary([("Famille/Roses.md", "Françoise  préfère\nles pivoines"), ("Vide.md", "")])
    assert "- Famille/Roses — Françoise préfère les pivoines\n" in text
    assert "- Vide\n" in text
    assert generated.is_untouched(text)


def test_journal_line_is_one_dated_line():
    line = generated.journal_line(datetime(2026, 10, 13, 9, 5, tzinfo=UTC), "créé\n`x.md`")
    assert line == "- 2026-10-13 09h05 — créé `x.md`\n"
