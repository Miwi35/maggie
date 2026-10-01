"""The guard that tells a Git LFS pointer from the data it stands for.

`compo.xml` is 69 MB and tracked in LFS. A checkout that did not fetch LFS
leaves a short text pointer in its place, and `ElementTree` reports that as
`syntax error: line 1, column 0` — a message that sends you looking for
malformed XML rather than at the checkout. That is a whole CI run spent on the
wrong question, and it happened once (MAG-101).
"""

import pytest

from app import import_ciqual
from app.import_ciqual import is_lfs_pointer

POINTER = (
    b"version https://git-lfs.github.com/spec/v1\n"
    b"oid sha256:8c46a9032e0000000000000000000000000000000000000000000000000000000\n"
    b"size 69243149\n"
)


def test_a_pointer_is_recognised(tmp_path):
    path = tmp_path / "compo.xml"
    path.write_bytes(POINTER)

    assert is_lfs_pointer(path) is True


def test_real_xml_is_not_a_pointer(tmp_path):
    # Byte-order mark and CRLF included, because that is how the published
    # Ciqual files really start — a check reading text would trip on both.
    path = tmp_path / "compo.xml"
    path.write_bytes(b'\xef\xbb\xbf<?xml version="1.0" encoding="utf-8" ?>\r\n<TABLE>\r\n</TABLE>\r\n')

    assert is_lfs_pointer(path) is False


def test_a_file_shorter_than_the_prefix_is_not_a_pointer(tmp_path):
    path = tmp_path / "compo.xml"
    path.write_bytes(b"<TABLE/>")

    assert is_lfs_pointer(path) is False


def test_a_missing_file_is_not_a_pointer(tmp_path):
    # The caller checks `exists()` first and says so in its own words; this must
    # not raise on the way past.
    assert is_lfs_pointer(tmp_path / "nothing.xml") is False


def test_the_import_refuses_a_pointer_and_names_it(tmp_path, monkeypatch, capsys):
    """The whole point: the message a CI log shows.

    `ElementTree`'s own answer is `syntax error: line 1, column 0`, which is
    what sent the first investigation looking for malformed XML. This one names
    the file, the cause and the two ways out.
    """
    for name in ("const.xml", "alim.xml", "alim_grp.xml"):
        (tmp_path / name).write_bytes(b"<TABLE/>")
    (tmp_path / "compo.xml").write_bytes(POINTER)

    monkeypatch.setattr(import_ciqual, "DATA_DIR", tmp_path)

    with pytest.raises(SystemExit) as refused:
        import_ciqual.main()

    assert refused.value.code == 1

    message = capsys.readouterr().err
    assert "compo.xml is a Git LFS pointer" in message
    assert "git lfs pull" in message
    assert "lfs: true" in message


def test_the_import_gets_past_the_guard_when_the_data_is_real(tmp_path, monkeypatch):
    # The control. Without it the guard could refuse everything and the test
    # above would be just as green. The import then fails on the *empty* tables
    # rather than on the guard — a different error, which is the whole point.
    for name in ("const.xml", "alim.xml", "alim_grp.xml", "compo.xml"):
        (tmp_path / name).write_bytes(b'<?xml version="1.0" encoding="utf-8" ?>\n<TABLE>\n</TABLE>\n')

    monkeypatch.setattr(import_ciqual, "DATA_DIR", tmp_path)
    monkeypatch.setattr(import_ciqual, "DB_PATH", tmp_path / "db" / "ciqual.db")

    # No SystemExit: it runs to the end on an empty but well-formed set.
    import_ciqual.main()

    assert (tmp_path / "db" / "ciqual.db").exists()
