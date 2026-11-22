"""Layering guards (MAG-885): the engine never reaches the HTTP layer, the domain knows no FastAPI."""

import ast
from pathlib import Path

import app
import app.api.routes as routes

APP_DIR = Path(app.__file__).parent


def _imported(path: Path) -> set[str]:
    modules = set()
    for node in ast.walk(ast.parse(path.read_text())):
        if isinstance(node, ast.Import):
            modules.update(alias.name for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            base = node.module or ""
            modules.add(base)
            modules.update(f"{base}.{alias.name}" for alias in node.names)
    return modules


def _is(module: str, package: str) -> bool:
    return module == package or module.startswith(package + ".")


def test_the_engine_never_imports_the_http_layer():
    offenders = {
        path.relative_to(APP_DIR).as_posix()
        for path in (APP_DIR / "llm").rglob("*.py")
        if any(_is(m, "app.api") for m in _imported(path))
    }
    assert offenders == set()


def test_the_domain_and_message_reply_do_not_import_fastapi():
    paths = [*(APP_DIR / "domain").rglob("*.py"), APP_DIR / "llm" / "message_reply.py"]
    offenders = {p.relative_to(APP_DIR).as_posix() for p in paths if any(_is(m, "fastapi") for m in _imported(p))}
    assert offenders == set()


def test_the_approval_logic_left_the_routes():
    for name in (
        "announce_approved_action",
        "_pending_action_or_error",
        "_answer_channel",
        "_is_overdue",
        "_is_error_result",
    ):
        assert not hasattr(routes, name), name
