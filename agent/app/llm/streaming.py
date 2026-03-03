"""AG-UI streaming gateway for token-by-token chat responses."""

import json
import logging
import time
import uuid
from collections.abc import AsyncGenerator
from datetime import UTC, datetime

import anthropic

from app.config import settings
from app.db.context_model import ContextStatus
from app.db.context_repository import context_repo
from app.db.message_repository import message_repo
from app.llm.capabilities import generate_capability_summary
from app.llm.tools import ToolRouter
from app.memory.agent_memory import AgentMemory
from app.metrics import TOOL_CALLS, record_llm_usage
from app.personality.engine import PersonalityEngine
from app.skills.index import skill_index

logger = logging.getLogger(__name__)


class StreamingGateway:
    """Claude API gateway with AG-UI streaming event emission."""

    def __init__(self):
        self.client = (
            anthropic.AsyncAnthropic(api_key=settings.anthropic_api_key) if settings.anthropic_api_key else None
        )
        self.personality = PersonalityEngine()
        self.tool_router = ToolRouter()
        self.agent_memory = AgentMemory()

    async def _build_system_prompt(self, user_id: str, message: str = "", tools: list[dict] | None = None) -> str:
        """Build full system prompt: personality + memory + skills + active contexts."""
        capabilities = generate_capability_summary(tools) if tools else ""
        base = await self.personality.get_system_prompt(user_id, capabilities=capabilities)
        memory_context = await self.agent_memory.get_memory_context(user_id)
        skill_context = skill_index.get_relevant_skills_context(message)

        # Inject active contexts so Claude knows ongoing topics
        active_contexts = await context_repo.find_active(user_id)
        context_section = ""
        if active_contexts:
            lines = ["\n\nContextes de conversation en cours :"]
            for ctx in active_contexts:
                status_icon = "●" if ctx.status == ContextStatus.ACTIVE else "◐"
                lines.append(f"- {status_icon} {ctx.label} ({ctx.status.value})")
            context_section = "\n".join(lines)

        return base + memory_context + skill_context + context_section

    async def _load_conversation_history(self, user_id: str) -> list[dict]:
        """Load conversation history from the database."""
        try:
            messages = await message_repo.find_recent(user_id, limit=settings.max_conversation_history)
            anthropic_messages = []
            for msg in messages:
                if msg.role in ("user", "assistant") and msg.content:
                    anthropic_messages.append({"role": msg.role, "content": msg.content})

            # Merge consecutive same-role messages
            merged = []
            for msg in anthropic_messages:
                if merged and merged[-1]["role"] == msg["role"]:
                    merged[-1]["content"] += "\n" + msg["content"]
                else:
                    merged.append(msg)

            return merged
        except Exception as e:
            logger.warning(f"Failed to load conversation history: {e}")
            return []

    async def chat_stream(self, message: str, user_id: str, user_msg_id: str) -> AsyncGenerator[dict, None]:
        """Stream AG-UI events for a chat message.

        Yields dicts representing AG-UI protocol events:
        - RUN_STARTED / RUN_FINISHED
        - TEXT_MESSAGE_START / TEXT_MESSAGE_CONTENT / TEXT_MESSAGE_END
        - TOOL_CALL_START / TOOL_CALL_END
        - CUSTOM (context updates, tool results)
        """
        run_id = uuid.uuid4().hex[:16]
        yield {"type": "RUN_STARTED", "runId": run_id}

        if self.client is None:
            msg_id = uuid.uuid4().hex[:16]
            yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
            error_text = "Le service IA n'est pas configuré."
            yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": error_text}
            yield {"type": "TEXT_MESSAGE_END", "messageId": msg_id}
            yield {"type": "RUN_FINISHED", "runId": run_id}
            return

        # Load history (already includes the just-persisted user message)
        messages = await self._load_conversation_history(user_id)

        # Get tools (contexts are managed by the gateway, not by Claude)
        tools = await self.tool_router.get_tool_definitions(include_native=True)

        system_prompt = await self._build_system_prompt(user_id, message=message, tools=tools)

        accumulated_text = ""
        max_iterations = 5

        # Route message to existing or new context via fast classifier
        ctx_resolution = await self._resolve_context(message, user_id, user_msg_id)
        current_context_id = ctx_resolution.get("id") if ctx_resolution else None
        if ctx_resolution:
            yield {"type": "CUSTOM", "name": "context_update", "value": ctx_resolution}

        # Single message ID across all iterations so the frontend sees one message bubble
        msg_id = uuid.uuid4().hex[:16]
        text_started = False

        for iteration in range(max_iterations):
            logger.info(f"Stream iteration {iteration + 1}, {len(tools)} tools, {len(messages)} messages")

            t0 = time.monotonic()
            try:
                # Use streaming API
                stream = self.client.messages.stream(
                    model=settings.anthropic_model,
                    max_tokens=4096,
                    system=system_prompt,
                    messages=messages,
                    tools=tools if tools else anthropic.NOT_GIVEN,
                )

                current_text_block = ""
                tool_use_blocks = []
                response_content = []
                stop_reason = None

                async with stream as s:
                    async for event in s:
                        if event.type == "content_block_start":
                            if hasattr(event.content_block, "text"):
                                # Text block starting
                                if not text_started:
                                    yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                                    text_started = True
                                current_text_block = ""
                            elif hasattr(event.content_block, "name"):
                                # Tool use block starting
                                tool_name = event.content_block.name
                                tool_id = event.content_block.id
                                yield {
                                    "type": "TOOL_CALL_START",
                                    "toolCallId": tool_id,
                                    "toolName": tool_name,
                                }

                        elif event.type == "content_block_delta":
                            if hasattr(event.delta, "text"):
                                delta = event.delta.text
                                current_text_block += delta
                                accumulated_text += delta
                                if text_started:
                                    yield {"type": "TEXT_MESSAGE_CONTENT", "messageId": msg_id, "delta": delta}

                        elif event.type == "content_block_stop":
                            pass  # Handled after stream ends

                    # Get the final message
                    response = await s.get_final_message()
                    stop_reason = response.stop_reason
                    response_content = response.content

                    duration = time.monotonic() - t0
                    record_llm_usage(
                        model=settings.anthropic_model,
                        call_type="chat_stream",
                        input_tokens=response.usage.input_tokens,
                        output_tokens=response.usage.output_tokens,
                        duration_seconds=duration,
                    )

                # Process tool calls if stop_reason is tool_use
                if stop_reason == "tool_use":
                    messages.append({"role": "assistant", "content": response_content})

                    tool_results = []
                    for block in response_content:
                        if block.type == "tool_use":
                            tool_name = block.name
                            tool_input = block.input

                            # Regular tool call
                            logger.info(f"Tool call: {tool_name}({tool_input})")
                            TOOL_CALLS.labels(tool_name=tool_name, source="chat_stream").inc()

                            result = await self.tool_router.call_tool(tool_name, tool_input, user_id=user_id)
                            tool_use_blocks.append(
                                {
                                    "name": tool_name,
                                    "input": tool_input,
                                    "result": result,
                                }
                            )

                            # Log to context if we have one
                            if current_context_id:
                                try:
                                    status = "error" if '"error"' in result else "success"
                                    await context_repo.append_tool_call(
                                        current_context_id,
                                        {
                                            "name": tool_name,
                                            "status": status,
                                            "timestamp": datetime.now(UTC).isoformat(),
                                        },
                                    )
                                except Exception as e:
                                    logger.warning(f"Failed to log tool call to context: {e}")

                            yield {
                                "type": "TOOL_CALL_END",
                                "toolCallId": block.id,
                                "toolName": tool_name,
                            }

                            # Emit tool result for Mind Panel
                            try:
                                result_data = json.loads(result)
                                status = "error" if "error" in result_data else "success"
                            except (json.JSONDecodeError, TypeError):
                                status = "success"

                            yield {
                                "type": "CUSTOM",
                                "name": "tool_result",
                                "value": {
                                    "toolCallId": block.id,
                                    "toolName": tool_name,
                                    "status": status,
                                },
                            }

                            tool_results.append(
                                {
                                    "type": "tool_result",
                                    "tool_use_id": block.id,
                                    "content": result,
                                }
                            )

                    messages.append({"role": "user", "content": tool_results})
                    # Continue loop for more iterations
                else:
                    # Done — save accumulated text as assistant message
                    break

            except Exception as e:
                duration = time.monotonic() - t0
                record_llm_usage(
                    model=settings.anthropic_model,
                    call_type="chat_stream",
                    input_tokens=0,
                    output_tokens=0,
                    duration_seconds=duration,
                    status="error",
                )
                logger.error(f"Streaming error: {e}")
                if not text_started:
                    yield {"type": "TEXT_MESSAGE_START", "messageId": msg_id, "role": "assistant"}
                    text_started = True
                yield {
                    "type": "TEXT_MESSAGE_CONTENT",
                    "messageId": msg_id,
                    "delta": "Désolé, une erreur est survenue. Réessaie.",
                }
                accumulated_text = "Désolé, une erreur est survenue. Réessaie."
                break

        # End the single text message that spans all iterations
        if text_started:
            yield {"type": "TEXT_MESSAGE_END", "messageId": msg_id}

        # Persist the assistant message (no Mercure publish — client already has it from SSE)
        if accumulated_text.strip():
            await message_repo.create(
                user_id=user_id,
                role="assistant",
                content=accumulated_text.strip(),
                context_id=current_context_id,
                publish=False,
            )

        yield {"type": "RUN_FINISHED", "runId": run_id}

    async def _resolve_context(self, message: str, user_id: str, user_msg_id: str) -> dict | None:
        """Fast LLM call to route a message to an existing or new context.

        Returns a dict with context info for the frontend, or None on failure.
        """
        if self.client is None:
            return None

        contexts = await context_repo.find_active(user_id)
        now = datetime.now(UTC)
        context_lines = []
        for ctx in contexts:
            idle = now - ctx.updated_at
            if idle.total_seconds() < 60:
                age = "à l'instant"
            elif idle.total_seconds() < 3600:
                age = f"il y a {int(idle.total_seconds() // 60)}min"
            elif idle.total_seconds() < 86400:
                age = f"il y a {int(idle.total_seconds() // 3600)}h"
            else:
                age = f"il y a {int(idle.days)}j"
            context_lines.append(f'- id="{ctx.id}" label="{ctx.label}" dernière activité={age}')

        context_list = "\n".join(context_lines) if context_lines else "(aucun)"

        try:
            t0 = time.monotonic()
            response = await self.client.messages.create(
                model="claude-haiku-4-5-20251001",
                max_tokens=200,
                system=(
                    "Tu es un routeur de contexte. Analyse le message et les contextes existants.\n"
                    "Réponds UNIQUEMENT avec un JSON valide, sans explication :\n"
                    '- Si le message correspond à un contexte existant : {"context_id": "<id>"}\n'
                    '- Si c\'est un nouveau sujet : {"context_id": null, "label": "<label court>"}\n'
                    "Le label doit être court (3-5 mots max), en français.\n"
                    "Préfère les contextes récents, mais un ancien contexte reste valide si le sujet correspond."
                ),
                messages=[
                    {
                        "role": "user",
                        "content": (f"Contextes existants :\n{context_list}\n\nMessage : {message}"),
                    }
                ],
            )
            duration = time.monotonic() - t0
            record_llm_usage(
                model="claude-haiku-4-5-20251001",
                call_type="context_resolve",
                input_tokens=response.usage.input_tokens,
                output_tokens=response.usage.output_tokens,
                duration_seconds=duration,
            )

            # Parse response
            text = response.content[0].text.strip()
            # Handle markdown-wrapped JSON
            if text.startswith("```"):
                text = text.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
            result = json.loads(text)

            context_id = result.get("context_id")
            if context_id:
                # Existing context — tag message and return
                await message_repo.update_context(user_msg_id, context_id)
                # Find the context to get its label
                existing = next((c for c in contexts if str(c.id) == context_id), None)
                label = existing.label if existing else "?"
                logger.info(f"Context resolved: existing '{label}' ({context_id})")
                return {"action": "matched", "id": context_id, "label": label, "status": "active"}
            else:
                # New context
                label = result.get("label", message[:60])
                ctx = await context_repo.create(user_id, label)
                await message_repo.update_context(user_msg_id, str(ctx.id))
                logger.info(f"Context resolved: new '{label}' -> {ctx.id}")
                return {"action": "created", "id": str(ctx.id), "label": label, "status": "active"}

        except Exception as e:
            logger.warning(f"Context resolution failed, continuing without context: {e}")
            return None
