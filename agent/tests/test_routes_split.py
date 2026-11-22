import ast
from pathlib import Path

API_DIR = Path(__file__).parent.parent / "app" / "api"
HTTP_METHODS = {"get", "put", "post", "delete", "patch"}

DOMAIN_PATHS = {
    "system_routes": ["/health", "/provider-status", "/internal/recette/reset"],
    "chat_routes": ["/chat", "/smoke/history", "/contexts"],
    "message_routes": ["/messages", "/chat/unread", "/chat/read"],
    "approval_routes": ["/approvals", "/questions"],
    "proaction_routes": ["/proaction", "/proactions", "/journal"],
    "settings_routes": ["/personality", "/instructions", "/style-proposals"],
    "voice_routes": ["/transcribe", "/tts", "/fake-bucket"],
    "skill_routes": ["/skills"],
}
# approval_routes resumes a turn after a question's answer and stores the reply like /chat does.
SHARED_HELPERS = {("approval_routes", "chat_routes"): {"_store_reply"}}


def _tree(name: str) -> ast.Module:
    return ast.parse((API_DIR / f"{name}.py").read_text())


def _declared_paths(tree: ast.Module) -> list[str]:
    paths = []
    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef):
            for decorator in node.decorator_list:
                func = getattr(decorator, "func", None)
                if isinstance(func, ast.Attribute) and func.attr in HTTP_METHODS and func.value.id == "router":
                    paths.append(decorator.args[0].value)
    return paths


def test_each_router_declares_only_its_domain_paths():
    for name, prefixes in DOMAIN_PATHS.items():
        paths = _declared_paths(_tree(name))
        assert paths, name
        for path in paths:
            assert any(path == p or path.startswith(p + "/") for p in prefixes), (name, path)


def test_routes_py_only_aggregates():
    source = (API_DIR / "routes.py").read_text()
    assert _declared_paths(ast.parse(source)) == []
    assert len(source.splitlines()) < 40


def test_routers_do_not_import_each_other():
    for name in [*DOMAIN_PATHS, "memory_routes"]:
        for node in ast.walk(_tree(name)):
            if isinstance(node, ast.ImportFrom) and node.module and node.module.startswith("app.api."):
                target = node.module.removeprefix("app.api.")
                if not target.endswith("_routes"):
                    continue
                imported = {a.name for a in node.names}
                assert imported <= SHARED_HELPERS.get((name, target), set()), (name, target, imported)
