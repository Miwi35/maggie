"""Maggie hesitates: a question with boxes to tick, then the answer in the thread (MAG-717).

`ask_owner` puts the question and ends the turn. The question travels as a `question` block of the
message that carries it (`Message.ui_blocks`); the owner answers it from the chat card or from an
interruption through `POST /agent/questions/{id}/answer`. A task Maggie just created is shown by a
`task` block on the message that announces it.
"""

import json
import logging
import uuid

from app.mcp.client import mcp_client

logger = logging.getLogger(__name__)

ASK_OWNER = "ask_owner"
CREATE_TASK = "create_task"
QUESTION = "question"
TASK = "task"
MIN_OPTIONS = 2
MAX_OPTIONS = 6
CHOICES_SEPARATOR = " · "

ASK_OWNER_TOOL = {
    "name": ASK_OWNER,
    "description": (
        "Ask the owner a short question with options to tick, and stop there: his answer comes back as his next "
        "message. Use it for any hesitation that fits options — what he expects from his request (« je dois penser à "
        "prendre… » : a task with the list, planned, or just a note?), which of two plausible readings, whether to "
        "go ahead with something irreversible — never to ask a free-text question and never for a clear "
        "request. 'text' is one natural sentence without Markdown; 'options' are 2 to 6 short labels; "
        "'multiple' is true when several can be ticked together."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "text": {"type": "string"},
            "options": {"type": "array", "items": {"type": "string"}, "minItems": MIN_OPTIONS, "maxItems": MAX_OPTIONS},
            "multiple": {"type": "boolean"},
        },
        "required": ["text", "options"],
    },
}


def _clean_options(options: object) -> list[str]:
    if not isinstance(options, list):
        return []
    cleaned: list[str] = []
    for option in options:
        label = option.strip() if isinstance(option, str) else ""
        if label and label not in cleaned:
            cleaned.append(label)
    return cleaned


async def handle_ask_owner(arguments: dict, user_id: str, source: str = "chat") -> str:
    text = arguments.get("text")
    text = text.strip() if isinstance(text, str) else ""
    options = _clean_options(arguments.get("options"))
    if not text or not MIN_OPTIONS <= len(options) <= MAX_OPTIONS:
        return json.dumps({"error": f"text and {MIN_OPTIONS} to {MAX_OPTIONS} distinct options are required"})

    block = {
        "type": QUESTION,
        "id": uuid.uuid4().hex[:26],
        "text": text,
        "options": options,
        "multiple": arguments.get("multiple") is True,
        "answered": None,
    }
    if source not in ("chat", "chat_stream"):
        await notify_question(user_id, block)
    return json.dumps({"asked": True, "question": block}, ensure_ascii=False)


async def notify_question(user_id: str, block: dict) -> None:
    """The interruption of a question Maggie asks outside a live chat turn. Logged on failure, never raised."""
    try:
        await mcp_client.call_tool(
            "manage_notifications",
            {
                "action": "create",
                "type": "question",
                "title": "Maggie a une question",
                "body": block["text"],
                "interaction": {
                    "kind": "choice",
                    "options": block["options"],
                    "multiple": block["multiple"],
                    "questionId": block["id"],
                },
            },
            user_id=user_id,
        )
    except Exception as e:
        logger.warning(f"Could not interrupt user {user_id} with question {block['id']}: {e!r}")


def _result(call: dict) -> dict | None:
    try:
        parsed = json.loads(call.get("result") or "")
    except (TypeError, ValueError):
        return None
    return parsed if isinstance(parsed, dict) else None


def asked_question(calls: list[dict]) -> dict | None:
    """The question block of the last successful `ask_owner` among the tool calls, if any."""
    for call in reversed(calls):
        if call.get("name") != ASK_OWNER:
            continue
        parsed = _result(call)
        if parsed and parsed.get("asked") and isinstance(parsed.get("question"), dict):
            return parsed["question"]
    return None


def blocks_of(calls: list[dict]) -> list[dict]:
    """The blocks a turn's tool calls leave on its message: the question asked, the cards of tasks created."""
    blocks: list[dict] = []
    if question := asked_question(calls):
        blocks.append(question)
    for call in calls:
        if call.get("name") != CREATE_TASK:
            continue
        parsed = _result(call)
        task = parsed.get("task") if parsed and parsed.get("success") else None
        task_id = task.get("id") if isinstance(task, dict) else None
        if isinstance(task_id, str) and task_id:
            blocks.append({"type": TASK, "id": task_id})
    return blocks


def choices_message(choices: list[str]) -> str:
    """What the owner's answer reads as in the thread: « A · B »."""
    return CHOICES_SEPARATOR.join(choices)
