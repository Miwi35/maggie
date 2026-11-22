import json
from pathlib import Path

from fastapi.routing import APIRoute

from app.main import app

SNAPSHOT = Path(__file__).parent / "fixtures" / "agent_routes.json"


def _inventory() -> dict:
    routes = sorted(
        [method, route.path, route.status_code]
        for route in app.routes
        if isinstance(route, APIRoute)
        for method in route.methods
    )
    return {"routes": routes, "paths": sorted(app.openapi()["paths"])}


def test_the_agent_serves_the_same_routes():
    inventory = _inventory()
    if not SNAPSHOT.exists():
        SNAPSHOT.write_text(json.dumps(inventory, indent=2) + "\n")
    assert inventory == json.loads(SNAPSHOT.read_text())
