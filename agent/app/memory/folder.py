"""The local copy of one user's memory folder, and what the last synchronisation knew about it.

The copy is a cache: nothing here survives a new pod, and nothing has to — the bucket holds the truth
and the outbox (Postgres) holds the writes it has not received. Every path goes through
`paths.local_path`, which proves it stays inside this user's folder; files are written atomically so
a crash never leaves half a fiche.
"""

import hashlib
import json
import logging
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path

from app.memory import paths
from app.memory.ulid import is_ulid

logger = logging.getLogger(__name__)

STATE_FILE = ".sync-state.json"


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


@dataclass
class Known:
    """What the last pass saw of a file: the bucket's ETag, and the hash of the content both sides had."""

    etag: str
    sha: str


class LocalFolder:
    def __init__(self, root: Path, user_id: str) -> None:
        self.root = root
        self.user_id = paths.check_user_id(user_id)
        self.base = root / self.user_id / paths.MEMORY_DIR

    def _path(self, rel: str) -> Path:
        return paths.local_path(self.root, self.user_id, rel)

    def exists(self, rel: str) -> bool:
        try:
            return self._path(rel).is_file()
        except paths.UnsafePath:
            return False

    def read(self, rel: str) -> str | None:
        target = self._path(rel)
        if not target.is_file():
            return None
        return target.read_bytes().decode("utf-8", errors="replace")

    def write(self, rel: str, text: str) -> None:
        target = self._path(rel)
        target.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=target.parent, prefix=".tmp-")
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(text.encode("utf-8"))
            os.replace(tmp, target)
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise

    def delete(self, rel: str) -> None:
        target = self._path(rel)
        target.unlink(missing_ok=True)
        parent = target.parent
        while parent != self.base and parent.is_relative_to(self.base):
            try:
                parent.rmdir()
            except OSError:
                break
            parent = parent.parent

    def files(self) -> list[str]:
        """Relative paths of the Markdown files that travel to the bucket, sorted. Hidden entries are never listed."""
        if not self.base.is_dir():
            return []
        found: list[str] = []
        for current, dirs, names in os.walk(self.base, followlinks=False):
            dirs[:] = [d for d in dirs if not d.startswith(".")]
            for name in names:
                if name.startswith("."):
                    continue
                full = Path(current) / name
                if full.is_symlink():
                    continue
                rel = full.relative_to(self.base).as_posix()
                try:
                    rel = paths.clean_relative(rel)
                except paths.UnsafePath:
                    logger.warning("Ignoring %r in the local memory folder of %s", rel, self.user_id)
                    continue
                if paths.is_syncable(rel):
                    found.append(rel)
        return sorted(found)

    def wipe(self) -> None:
        """Remove the local copy (and its sync state) of this user."""
        import shutil

        shutil.rmtree(self.root / self.user_id, ignore_errors=True)

    @property
    def _state_path(self) -> Path:
        return self.root / self.user_id / STATE_FILE

    def load_state(self) -> dict[str, Known]:
        try:
            raw = json.loads(self._state_path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}
        out: dict[str, Known] = {}
        for rel, item in (raw.get("files") or {}).items():
            if isinstance(item, dict) and "etag" in item and "sha" in item:
                out[rel] = Known(str(item["etag"]), str(item["sha"]))
        return out

    def save_state(self, state: dict[str, Known]) -> None:
        self._state_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {"files": {rel: {"etag": k.etag, "sha": k.sha} for rel, k in sorted(state.items())}}
        fd, tmp = tempfile.mkstemp(dir=self._state_path.parent, prefix=".tmp-")
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False)
        os.replace(tmp, self._state_path)


def local_users(root: Path) -> list[str]:
    """User folders present in the local copy: only names that are valid user ids."""
    if not root.is_dir():
        return []
    return sorted(entry.name for entry in root.iterdir() if entry.is_dir() and is_ulid(entry.name))
