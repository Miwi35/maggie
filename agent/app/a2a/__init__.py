from a2a.server.apps import A2AStarletteApplication
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.tasks import InMemoryTaskStore
from fastapi import FastAPI

from app.a2a.auth import A2A_RPC_PATH, A2ABearerMiddleware
from app.a2a.card import build_agent_card
from app.a2a.executor import MaggieAgentExecutor


def setup_a2a(app: FastAPI) -> None:
    """Wire A2A protocol routes into the existing FastAPI app, behind a bearer token."""
    app.add_middleware(A2ABearerMiddleware)

    agent_card = build_agent_card()

    request_handler = DefaultRequestHandler(
        agent_executor=MaggieAgentExecutor(),
        task_store=InMemoryTaskStore(),
    )

    a2a_app = A2AStarletteApplication(
        agent_card=agent_card,
        http_handler=request_handler,
    )

    # Add A2A routes directly to the FastAPI (Starlette) app
    a2a_app.add_routes_to_app(
        app=app,
        agent_card_url="/.well-known/agent-card.json",
        rpc_url=A2A_RPC_PATH,
    )
