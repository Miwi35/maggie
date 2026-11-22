"""The assembly of the agent's own tools (MAG-945).

The only module that imports the tools of each domain: it registers them, in the order
the model sees them, in the registry `ToolRouter` dispatches through.
"""

from collections.abc import Awaitable, Callable

from app.agents.tool import delegate_offered, delegate_tool_definition, handle_delegate_tool
from app.bilan_consigne import BILAN_CONSIGNE_TOOL, handle_add_bilan_consigne
from app.coach_method import GET_COACH_METHOD_TOOL, handle_get_coach_method
from app.instruction_tools import (
    INSTRUCTION_TOOLS,
    handle_add_instruction,
    handle_delete_instruction,
    handle_list_instructions,
)
from app.llm.message_reply import ANSWER_APPROVAL_TOOL, handle_answer_approval
from app.llm.questions import ASK_OWNER_TOOL, handle_ask_owner
from app.llm.time_tool import DATE_TIME_TOOLS, handle_date_time
from app.memory import conversations
from app.memory.tools import MEMORY_TOOLS
from app.memory.tools import NATIVE_HANDLERS as MEMORY_HANDLERS
from app.runtime.tools.registry import NativeTool, NativeToolRegistry, ToolCallContext
from app.skills.tools import (
    SKILL_TOOLS,
    handle_create_skill,
    handle_delete_skill,
    handle_get_skill,
    handle_list_skills,
    handle_update_skill,
)
from app.tts.voice_message import SEND_VOICE_MESSAGE_TOOL, handle_send_voice_message

UserHandler = Callable[[dict, str], Awaitable[str]]
Handler = Callable[[dict, ToolCallContext], Awaitable[str]]


def _for_user(handler: UserHandler) -> Handler:
    async def run(arguments: dict, ctx: ToolCallContext) -> str:
        return await handler(arguments, ctx.user_id)

    return run


def _memory_tool(handler: UserHandler) -> Handler:
    async def run(arguments: dict, ctx: ToolCallContext) -> str:
        # What was just said is on file before a fiche cites it, whatever the router managed to tag.
        await conversations.record_thread(ctx.user_id, ctx.context_id)
        return await handler(arguments, ctx.user_id)

    return run


async def _ask_owner(arguments: dict, ctx: ToolCallContext) -> str:
    return await handle_ask_owner(arguments, ctx.user_id, ctx.source)


async def _send_voice_message(arguments: dict, ctx: ToolCallContext) -> str:
    return await handle_send_voice_message(arguments, ctx.user_id, ctx.context_id)


def build_native_registry() -> NativeToolRegistry:
    registry = NativeToolRegistry()

    def add(definitions: list[dict], handlers: dict[str, Handler]) -> None:
        for definition in definitions:
            registry.register(NativeTool(definition=definition, handler=handlers[definition["name"]]))

    add(MEMORY_TOOLS, {name: _memory_tool(h) for name, h in MEMORY_HANDLERS.items()})
    add(
        INSTRUCTION_TOOLS,
        {
            "add_instruction": _for_user(handle_add_instruction),
            "list_instructions": _for_user(handle_list_instructions),
            "delete_instruction": _for_user(handle_delete_instruction),
        },
    )
    add([BILAN_CONSIGNE_TOOL], {"add_bilan_consigne": _for_user(handle_add_bilan_consigne)})
    add(
        SKILL_TOOLS,
        {
            "create_skill": _for_user(handle_create_skill),
            "list_skills": _for_user(handle_list_skills),
            "get_skill": _for_user(handle_get_skill),
            "update_skill": _for_user(handle_update_skill),
            "delete_skill": _for_user(handle_delete_skill),
        },
    )
    add(DATE_TIME_TOOLS, {"date_time": _for_user(handle_date_time)})
    add([GET_COACH_METHOD_TOOL], {"get_coach_method": _for_user(handle_get_coach_method)})
    add([ANSWER_APPROVAL_TOOL], {"answer_approval": _for_user(handle_answer_approval)})
    add([ASK_OWNER_TOOL], {"ask_owner": _ask_owner})
    # Offered even when the bucket is off: the model then hears why it cannot send one (MAG-685).
    add([SEND_VOICE_MESSAGE_TOOL], {"send_voice_message": _send_voice_message})
    registry.register(
        NativeTool(definition=delegate_tool_definition, handler=handle_delegate_tool, offered=delegate_offered)
    )
    return registry
