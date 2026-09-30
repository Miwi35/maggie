"""Runs inside the dev `agent` container, fed on stdin by mcp-session.sh.

    python - <command> [args...]

The JWT comes in PROMPT_LAB_TOKEN. Nothing here runs on the host.
"""

import asyncio
import base64
import json
import os
import sys
from typing import NoReturn

import httpx

from app.config import settings


def die(message: str) -> NoReturn:
    print(f"Erreur : {message}", file=sys.stderr)
    sys.exit(1)


def token() -> str:
    value = os.environ.get("PROMPT_LAB_TOKEN", "")
    if not value:
        die("PROMPT_LAB_TOKEN est vide — lancez « mcp-session.sh login ».")
    return value


def user_id() -> str:
    """The user the agent would act for: same claim order as app.auth.get_current_user_id."""
    payload = token().split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    return claims.get("sub") or claims.get("username") or die("le jeton n'a ni 'sub' ni 'username'.")


def rpc_message(response: httpx.Response) -> dict:
    body = response.text
    if "text/event-stream" in response.headers.get("content-type", ""):
        data = [line[5:].strip() for line in body.splitlines() if line.startswith("data:")]
        body = data[-1] if data else ""
    if not body.strip():
        die(f"réponse MCP vide (HTTP {response.status_code}).")
    return json.loads(body)


class McpSession:
    """One stateful /_mcp session: initialize, notifications/initialized, then requests.

    Without the handshake the server answers "a valid session id is REQUIRED",
    which reads like an empty tool list if the error is not shown.
    """

    def __init__(self) -> None:
        self.url = settings.mcp_server_url
        self.headers = {
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
            "Authorization": f"Bearer {token()}",
        }
        self.client = httpx.AsyncClient(timeout=60.0)
        self._next_id = 1

    async def __aenter__(self) -> "McpSession":
        init = await self._post(
            {
                "jsonrpc": "2.0",
                "id": self._take_id(),
                "method": "initialize",
                "params": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": {"name": "maggie-prompt-lab", "version": "1.0.0"},
                },
            }
        )
        if init.status_code in (401, 403):
            die("jeton refusé par /_mcp — relancez « mcp-session.sh login ».")
        if init.status_code >= 400:
            die(f"initialize a échoué (HTTP {init.status_code}) : {init.text[:300]}")
        session_id = init.headers.get("mcp-session-id")
        if not session_id:
            die("initialize n'a pas rendu d'identifiant de session MCP.")
        self.headers["Mcp-Session-Id"] = session_id
        await self._post({"jsonrpc": "2.0", "method": "notifications/initialized"})
        return self

    async def __aexit__(self, *exc_info) -> None:
        await self.client.aclose()

    def _take_id(self) -> int:
        self._next_id += 1
        return self._next_id - 1

    async def _post(self, payload: dict) -> httpx.Response:
        try:
            return await self.client.post(self.url, json=payload, headers=self.headers)
        except httpx.HTTPError as e:
            die(f"/_mcp injoignable sur {self.url} : {e}")

    async def request(self, method: str, params: dict) -> dict:
        response = await self._post({"jsonrpc": "2.0", "id": self._take_id(), "method": method, "params": params})
        message = rpc_message(response)
        if "error" in message:
            die(f"{method} : {json.dumps(message['error'], ensure_ascii=False)}")
        return message.get("result", {})

    async def list_tools(self) -> list[dict]:
        tools: list[dict] = []
        params: dict = {}
        while True:
            result = await self.request("tools/list", params)
            tools.extend(result.get("tools", []))
            if not result.get("nextCursor"):
                return tools
            params = {"cursor": result["nextCursor"]}


async def cmd_tools() -> None:
    async with McpSession() as mcp:
        tools = await mcp.list_tools()
    if not tools:
        die(
            "tools/list ne rend aucun outil — un module manque dans discovery.scan_dirs "
            "(api/config/packages/mcp.yaml) ?"
        )
    print(json.dumps(tools, ensure_ascii=False, indent=2))


async def cmd_call(name: str, raw_arguments: str) -> None:
    try:
        arguments = json.loads(raw_arguments)
    except json.JSONDecodeError as e:
        die(f"arguments JSON invalides : {e}")
    if not isinstance(arguments, dict):
        die("les arguments doivent être un objet JSON.")

    async with McpSession() as mcp:
        result = await mcp.request("tools/call", {"name": name, "arguments": arguments})

    text = "\n".join(block.get("text", "") for block in result.get("content", []) if block.get("type") == "text")
    print(text or json.dumps(result, ensure_ascii=False))
    if result.get("isError"):
        sys.exit(1)


async def cmd_personality() -> None:
    from app.personality.engine import PersonalityEngine

    print(json.dumps(await PersonalityEngine().get_config(user_id()), ensure_ascii=False, indent=2))


async def cmd_instructions() -> None:
    from app.db.instruction_repository import instruction_repo

    instructions = await instruction_repo.find_by_user(user_id())
    print(json.dumps([i.to_dict() for i in instructions], ensure_ascii=False, indent=2))


async def cmd_skills() -> None:
    from app.skills.index import skill_index

    skill_index.rebuild()
    skills = [
        {"name": e.name, "description": e.description, "tags": e.tags, "content": skill_index.get(e.name)}
        for e in sorted(skill_index.list_all(), key=lambda e: e.name)
    ]
    print(json.dumps(skills, ensure_ascii=False, indent=2))


async def cmd_context(mode: str, variant: str) -> None:
    """The system prompt exactly as LLMGateway builds it: personality + capabilities + skill index, memory, date."""
    from app.db.instruction_repository import instruction_repo
    from app.llm.gateway import EXECUTION_PREAMBLE, PLANNING_PREAMBLE, LLMGateway
    from app.mcp.client import mcp_client
    from app.skills.index import skill_index

    if mode not in ("chat", "proaction"):
        die(f"mode inconnu « {mode} » — attendu : chat ou proaction.")
    if variant not in ("planning", "execution"):
        die(f"variante inconnue « {variant} » — attendu : planning ou execution.")

    uid = user_id()
    skill_index.rebuild()
    gateway = LLMGateway()

    try:
        if not await mcp_client.list_tools():
            die(
                "le client MCP de l'agent ne rend aucun outil — SERVICE_TOKEN de l'agent différent de celui de l'API, "
                "ou /_mcp en erreur ? Sans outils, le résumé des capacités serait vide."
            )
        tools = await gateway.tool_router.get_tool_definitions(include_native=True)
    finally:
        await mcp_client.disconnect()

    preamble = ""
    if mode == "proaction":
        preamble = PLANNING_PREAMBLE if variant == "planning" else EXECUTION_PREAMBLE

    blocks = await gateway._build_system_prompt(uid, tools=tools, preamble=preamble)
    prompt = "\n\n".join(block["text"] for block in blocks)

    if mode == "proaction":
        instructions = await instruction_repo.find_by_user(uid)
        if instructions:
            prompt += "\n\nDirectives de l'utilisateur (list_instructions) :\n"
            prompt += "\n".join(f"- {i.content}" for i in instructions)

    print(prompt)


async def main(argv: list[str]) -> None:
    command, args = (argv[0] if argv else ""), argv[1:]
    if command == "tools":
        await cmd_tools()
    elif command == "call":
        if not args:
            die("usage : call <tool_name> '<json_args>'")
        await cmd_call(args[0], args[1] if len(args) > 1 else "{}")
    elif command == "personality":
        await cmd_personality()
    elif command == "instructions":
        await cmd_instructions()
    elif command == "skills":
        await cmd_skills()
    elif command == "context":
        await cmd_context(args[0] if args else "chat", args[1] if len(args) > 1 else "planning")
    else:
        die(f"commande inconnue « {command} ».")


asyncio.run(main(sys.argv[1:]))
