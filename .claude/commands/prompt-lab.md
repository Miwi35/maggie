# Maggie Prompt Lab

You are entering Maggie's Prompt Lab — test and iterate on the AI agent's prompts using Claude Code subagents instead of Anthropic API tokens.

## Setup

Run these commands to gather Maggie's live configuration:

1. **Login**: `scripts/prompt-lab/mcp-session.sh login` (24h JWT, cached in the gitignored `scripts/prompt-lab/.token`; the other subcommands log in again by themselves when it is missing or about to expire). It needs the dev stack (`task up`) and acts as the only user of the dev DB — set `PROMPT_LAB_USER_EMAIL` if there are several.
2. **Full context**: `scripts/prompt-lab/mcp-session.sh context [chat|proaction [planning|execution]]`

Two modes matching how the real agent works:
- `context chat` (default) — personality + capabilities + skill index, then memory and the date, built by the agent's own `LLMGateway`. This is what the LLM sees during normal conversation. Skills are listed as a compact index (`- name: description`, sorted by name, no body); the agent loads a skill's content on demand with `get_skill`.
- `context proaction [planning|execution]` — everything above + the autonomous preamble (`planning` by default: the silent daily planning; `execution`: a scheduled proaction that writes to the chat) + the user's instructions. Instructions are ONLY used by proactions, never in regular chat: in production the agent reads them with `list_instructions`, the lab inlines them because a subagent has no native tools.

3. Show the assembled prompt to the user for review

Individual data can also be fetched:
- `scripts/prompt-lab/mcp-session.sh personality` — personality config
- `scripts/prompt-lab/mcp-session.sh instructions` — proaction directives (only used in proaction mode)
- `scripts/prompt-lab/mcp-session.sh skills` — all skills with full content
- `scripts/prompt-lab/mcp-session.sh tools` — all MCP tool schemas

## Ask the user what mode they want

Use AskUserQuestion to ask:

### Interactive Mode — Chat
The user provides a test message. You:
1. Run `scripts/prompt-lab/mcp-session.sh context chat` to get the base system prompt
2. The output already ends with the skill index (`- name: description`, sorted by name, no body): do not inject any skill body, the subagent loads one with `get_skill` when it needs it
3. Spawn a subagent (model: sonnet by default) with:
   - **System instructions**: The assembled system prompt
   - **Task**: "You are Maggie, responding to this user message. Respond exactly as Maggie would. When you would call a tool, use the Bash tool to execute: `scripts/prompt-lab/mcp-session.sh call <tool_name> '<json_args>'` and incorporate the real result into your response. Stay in character throughout."
   - **User message**: The test input

After the subagent responds, show the user:
1. The full response
2. Which tools were called and with what arguments
3. Ask if the behavior was correct and what to change

### Interactive Mode — Proaction Planning
For testing daily planning or proaction execution. You:
1. Run `scripts/prompt-lab/mcp-session.sh context proaction` to get the proaction system prompt (includes the autonomous preamble + instructions)
2. Spawn a subagent (model: sonnet by default) with:
   - **System instructions**: The proaction system prompt
   - **Task**: "You are Maggie in autonomous proaction mode. Execute the task below without asking the user for confirmation. When you would call a tool, use the Bash tool to execute: `scripts/prompt-lab/mcp-session.sh call <tool_name> '<json_args>'` and incorporate the real result. Stay in character throughout."
   - **User message**: Either the DAILY_PLANNING_PROMPT (from `proaction-planning.yaml`) or a specific proaction prompt

For daily planning tests with mock data, inject `mock_tool_results` from the scenario file so that `list_instructions` and `list_proactions` return controlled data instead of live DB content.

Key differences from chat mode:
- No conversation history (each proaction starts fresh)
- Same skill index as chat (no per-message matching; skills load on demand with `get_skill`)
- Maggie must act autonomously — any "voulez-vous que..." is a FAIL
- The "user message" is from the scheduler, not a real user

### Comparison Mode
The user provides two prompt variations (or you propose them). Run both in parallel using two subagents with model: sonnet, same user message, different system prompts. Present outputs side-by-side for comparison.

### Automated Suite Mode
Load test scenarios from `scripts/prompt-lab/scenarios/*.yaml`. The format is in
that directory's `README.md` — the same files the eval suite replays on the real
model (`task e2e:eval`, MAG-95), so keep them in step rather than inventing keys:

```yaml
name: descriptive-name
channel: chat                     # chat (default) or stream
steps:                            # or `user_message` at the top level, for one turn
  - user_message: "the message to test"
    expected_tools: [tool_name_1]
    forbidden_tools: [tool_name_2]
    expected_contains: ["Alex"]
    expected_absent: ["je n'ai pas accès"]
    expected_context: created     # created | matched, channel: stream only
    expected_behavior: "description of correct behavior"
mock_tool_results:                # prompt-lab only: use these instead of real MCP calls
  tool_name_1: '{"result": "mocked"}'
```

Run all scenarios, report pass/fail based on:
- Did Maggie call the expected tools, and none of the forbidden ones?
- Was the response in French (unless English input)?
- Did she stay in character (vouvoiement, formal tone)?
- Does the answer satisfy `expected_behavior`?

`task e2e:eval:check` validates the files without spending anything — run it after
editing one, since an unknown expectation key is refused there rather than
silently asserting nothing.

## Tool execution in subagents

Subagents have Bash access and can call real MCP tools:
```bash
scripts/prompt-lab/mcp-session.sh call get_upcoming_events '{"days": 7}'
scripts/prompt-lab/mcp-session.sh call create_event '{"title":"Test","date":"2026-03-03"}'
```

For automated tests, use `mock_tool_results` from the scenario file instead of live calls.

## Prompt modification workflow

When iterating on prompts:
1. Show the current prompt section being modified
2. Propose the change
3. Test with a relevant scenario
4. If approved, edit the source file:
   - Personality: update via `PUT /agent/personality` (or edit `agent/app/personality/default.yaml` for the template/rules)
   - Instructions: create/delete via `/agent/instructions` API
   - Skills: create/update via `/agent/skills` API
   - Capabilities: edit `agent/app/llm/capabilities.py`
5. Re-run the test to confirm improvement

## Available models for subagents

- **sonnet** (default) — same family as production (Maggie defaults to Sonnet 5.5 via `ANTHROPIC_MODEL`; check the subagent model before comparing tone)
- **haiku** — fast iteration, cheaper, good for bulk scenario testing
- **opus** — highest quality, use for evaluating difficult edge cases

## Important notes

- Subagent tokens come from Claude Code subscription, NOT the Anthropic API key
- MCP tool calls hit the REAL API database — be aware of side effects with write operations
- Native tools (memory, skills, instructions, proactions) are handled within the agent process — for testing, mock their results in scenarios or test via the agent REST API
- `context chat` builds the prompt the way production does (same as production minus conversation history), skill index included (no body; the agent calls `get_skill`)
- `context proaction` includes instructions — use this when testing daily planning or autonomous tasks
