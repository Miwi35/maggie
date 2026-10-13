import os

import pytest

from app.memory import paths
from app.memory.folder import Known, LocalFolder, local_users
from tests.memory.conftest import USER_A, USER_B


def test_write_read_delete_and_empty_parents_go(tmp_path):
    folder = LocalFolder(tmp_path, USER_A)
    folder.write("Famille/Roses.md", "texte")
    assert folder.read("Famille/Roses.md") == "texte"
    assert folder.files() == ["Famille/Roses.md"]
    folder.delete("Famille/Roses.md")
    assert folder.read("Famille/Roses.md") is None
    assert not (folder.base / "Famille").exists()
    assert folder.base.exists()


def test_a_write_leaves_no_temporary_file(tmp_path):
    folder = LocalFolder(tmp_path, USER_A)
    folder.write("x.md", "un")
    folder.write("x.md", "deux")
    assert sorted(p.name for p in folder.base.iterdir()) == ["x.md"]
    assert folder.read("x.md") == "deux"


def test_listing_ignores_hidden_symlinked_and_non_markdown(tmp_path):
    folder = LocalFolder(tmp_path, USER_A)
    folder.write("ok.md", "x")
    (folder.base / ".cache").mkdir()
    (folder.base / ".cache" / "no.md").write_text("x")
    (folder.base / "notes.txt").write_text("x")
    (folder.base / ".tmp-1").write_text("x")
    outside = tmp_path / "secret.md"
    outside.write_text("secret")
    os.symlink(outside, folder.base / "lien.md")
    assert folder.files() == ["ok.md"]


def test_an_escaping_path_is_refused_and_never_written(tmp_path):
    folder = LocalFolder(tmp_path, USER_A)
    with pytest.raises(paths.UnsafePath):
        folder.write("../../escape.md", "x")
    assert not (tmp_path / "escape.md").exists()
    assert not folder.exists("../x.md")


def test_state_round_trips_and_a_corrupt_state_is_empty(tmp_path):
    folder = LocalFolder(tmp_path, USER_A)
    assert folder.load_state() == {}
    folder.save_state({"a.md": Known("etag", "sha")})
    assert folder.load_state() == {"a.md": Known("etag", "sha")}
    (tmp_path / USER_A / ".sync-state.json").write_text("{pas du json")
    assert folder.load_state() == {}


def test_users_are_separate_and_wipe_removes_one(tmp_path):
    LocalFolder(tmp_path, USER_A).write("a.md", "a")
    LocalFolder(tmp_path, USER_B).write("a.md", "b")
    (tmp_path / "pas-un-utilisateur").mkdir()
    assert local_users(tmp_path) == [USER_A, USER_B]
    LocalFolder(tmp_path, USER_A).wipe()
    assert LocalFolder(tmp_path, USER_B).read("a.md") == "b"
    assert local_users(tmp_path) == [USER_B]


def test_a_user_id_that_is_not_a_ulid_is_refused(tmp_path):
    with pytest.raises(paths.UnsafePath):
        LocalFolder(tmp_path, "../x")
