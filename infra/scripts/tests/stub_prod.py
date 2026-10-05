#!/usr/bin/env python3
"""A stand-in for production, just faithful enough for smoke-prod.sh.

Healthy by default; STUB_BREAK=<a>,<b> breaks one thing each, so the test can
check that every failure the suite exists to catch turns it red. Prints its
port on stdout, then serves until killed.
"""

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BREAK = set(filter(None, os.environ.get("STUB_BREAK", "").split(",")))
TOKEN = os.environ["STUB_TOKEN"]

MODULES = ["list_agendas", "get_upcoming_events", "search_recipes", "add_grocery_item", "list_accounts", "list_notifications"]
SESSION = "stub-session"

# What the technical account owns in the stub: its test agendas and events, and how many
# messages its history holds. `imitates_history` starts it with the fifty pairs that made
# the real model stop calling the tool (MAG-253).
STATE = {"agendas": {}, "events": [], "history": 100 if "imitates_history" in BREAK else 0}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send(self, status, body=b"", headers=None):
        self.send_response(status)
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def json(self, status, payload, headers=None):
        self.send(status, json.dumps(payload).encode(), {"Content-Type": "application/json", **(headers or {})})

    def authorized(self):
        return self.headers.get("Authorization") == f"Bearer {TOKEN}"

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length) or b"{}")

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/api/docs":
            self.json(200, {"openapi": "3.1.0"})
        elif path == "/agent/health":
            self.json(200, {"status": "degraded" if "agent_health" in BREAK else "ok"})
        elif path in ("/admin", "/admin/"):
            if "admin_missing" in BREAK and path == "/admin":
                self.send(404)
            else:
                self.send(200, b"<!doctype html><div id=root></div>", {"Content-Type": "text/html"})
        elif path == "/":
            location = "http://stub.invalid/admin" if "admin_http_redirect" in BREAK else "/admin"
            self.send(302, headers={"Location": location})
        elif path == "/api/users/me":
            if self.authorized():
                self.json(200, {"email": "smoke@maggieai.fr"})
            else:
                self.json(401, {"message": "Invalid JWT Token"})
        elif path == "/__state":
            self.json(200, {"agendas": len(STATE["agendas"]), "history": STATE["history"]})
        else:
            self.send(404)

    def do_DELETE(self):
        path = self.path.split("?")[0]
        if not self.authorized():
            self.json(401, {"message": "Invalid JWT Token"})
        elif path == "/agent/smoke/history":
            if "history_reset_fails" in BREAK:
                self.json(403, {"detail": "Reserved to the technical smoke account"})
            else:
                STATE["history"] = 0
                self.json(200, {"deletedMessages": 0, "deletedContexts": 0})
        elif path.startswith("/api/agendas/") and path.removeprefix("/api/agendas/") in STATE["agendas"]:
            del STATE["agendas"][path.removeprefix("/api/agendas/")]
            STATE["events"] = [e for e in STATE["events"] if e["agenda"] != path.removeprefix("/api/agendas/")]
            self.send(204)
        else:
            self.send(404)

    def do_OPTIONS(self):
        if self.path.startswith("/.well-known/mercure"):
            headers = {}
            origin = f"http://127.0.0.1:{self.server.server_address[1]}"
            if "mercure_cors" not in BREAK and self.headers.get("Origin") == origin:
                headers["Access-Control-Allow-Origin"] = origin
            self.send(204, headers=headers)
        else:
            self.send(404)

    def do_POST(self):
        path = self.path.split("?")[0]
        payload = self.body()
        if path == "/_mcp":
            self.mcp(payload)
        elif path == "/agent/chat":
            self.chat(payload)
        elif path == "/api/agendas":
            if self.authorized():
                agenda_id = f"agenda-{len(STATE['agendas']) + 1}"
                STATE["agendas"][agenda_id] = payload.get("name")
                self.json(201, {"id": agenda_id, "name": payload.get("name")})
            else:
                self.json(401, {"message": "Invalid JWT Token"})
        elif path == "/api/events":
            if self.authorized() and "event_creation_fails" not in BREAK:
                agenda_id = payload.get("agenda", "").removeprefix("/api/agendas/")
                STATE["events"].append({**payload, "agenda": agenda_id})
                self.json(201, {"id": f"event-{len(STATE['events'])}"})
            else:
                self.json(422, {"detail": "Unprocessable"})
        else:
            self.send(404)

    def mcp(self, payload):
        if not self.authorized():
            self.json(401, {"error": {"message": "Invalid bearer token."}})
        elif payload.get("method") == "initialize":
            headers = {} if "mcp_no_session" in BREAK else {"Mcp-Session-Id": SESSION}
            self.json(200, {"jsonrpc": "2.0", "id": 1, "result": {}}, headers)
        elif payload.get("method") == "tools/list":
            if self.headers.get("Mcp-Session-Id") != SESSION:
                self.json(400, {"error": {"message": "a valid session id is REQUIRED"}})
                return
            tools = [] if "mcp_empty" in BREAK else [m for m in MODULES if not ("mcp_missing_module" in BREAK and "grocery" in m)]
            body = json.dumps({"jsonrpc": "2.0", "id": 2, "result": {"tools": [{"name": t} for t in tools]}})
            self.send(200, f"event: message\nid: 1\ndata: {body}\n\n".encode(), {"Content-Type": "text/event-stream"})
        else:
            self.send(202)

    def chat(self, payload):
        if not self.authorized():
            self.json(401, {"detail": "Invalid token"})
            return
        if "agent_down" in BREAK:
            self.send(502)
            return
        # Answers from the history like the real model did: with a conversation behind it,
        # it replies without the tool.
        skips_tool = "agent_no_tool" in BREAK or STATE["history"] > 0
        tool_calls = [] if skips_tool else [
            {"name": "get_upcoming_events", "input": {}, "result": '{"error": "boom"}' if "agent_tool_error" in BREAK else "[]"}
        ]
        # What `get_upcoming_events` would read: the title of the event the question names.
        asked = payload.get("message", "")
        titles = [e["summary"] for e in STATE["events"] if e["description"] in asked]
        if "agent_apology" in BREAK:
            response = "Désolé, une erreur est survenue. Réessaie."
        elif "agent_guesses" in BREAK or skips_tool or not titles:
            response = "Je ne vois aucun événement."
        else:
            response = f"L'événement s'appelle « {titles[0]} »."
        STATE["history"] += 2
        self.json(200, {"response": response, "tool_calls": tool_calls, "messages": []})


server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
print(server.server_address[1], flush=True)
server.serve_forever()
