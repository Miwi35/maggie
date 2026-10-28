# Change what Maggie does in the agent

**When**: a ticket changes Maggie's behaviour on the agent side — a rule of her prompt, the skills mechanism, a sub-agent, the tool policy, a guard on what she claims. Not for a new MCP tool (that is API work) nor for a scripted e2e answer (`agent-fake-llm-scenario.md`). **Never hard-code a user preference** (« always notify », « talk only of agenda X »): she learns those in conversation (`add_instruction` kind `behavior`, `create_skill`); when a learned rule is not applied, fix the extraction, storage or injection, not the preference.
**Reference**: MAG-639 (`3119c9da6`) — `agent/app/personality/default.yaml`, `agent/tests/test_personality_engine.py` (`test_a_standing_agenda_limit_never_narrows_a_search_by_name`), `scripts/prompt-lab/scenarios/17-deplacer-repas-par-nom.yaml`. For a code guard: `agent/app/llm/claim_guard.py` + `agent/tests/test_learning_claims.py` (MAG-340).

## Steps
1. Pick where the change lives — one place, the narrowest that works:
   - **A prompt rule**: one line `- KEY, DETAIL : …` in `system_prompt` of `agent/app/personality/default.yaml` (in French, vouvoiement); a voice-only rule in `spoken_prompt`. Prompts of the non-chat calls stay beside their code (`app/llm/contexts.py`, `app/llm/context_summary.py`, `app/memory/…`).
   - **Skills**: the repository ships none — they are the user's, files in `Compétences/` of the bucket. Only the mechanism changes: `app/skills/` (`index.py`, `store.py`), the native tools `create_skill` / `update_skill` / `delete_skill` / `get_skill` in `app/llm/tools.py`, the « → COMPÉTENCE » line of `default.yaml`. A skill tagged `moment:chat|proaction|planification` is injected in full at that moment (MAG-345, `MOMENT_SKILLS_MAX_CHARS`), the others stay in the index.
   - **A sub-agent**: `agent/data/agents/<name>.md` with its frontmatter (`name`, `description`, `model` alias, `tools` fnmatch patterns, `max_iterations`), loaded by `app/agents/registry.py`, reached through `delegate`.
   - **Tool policy**: a rule in `agent/data/policy.yaml` (`tools`, optional `when` / `sources`, `mode` allow | ask | deny), exceptions before broad patterns. Enforced in `ToolRouter.call_tool`, never by the prompt.
   - **A guard on her claims** (« je vous rappellerai » with nothing scheduled): `app/llm/claim_guard.py`, applied in the shared loop (`app/llm/runner.py`) and in `app/llm/streaming.py`.
2. A new native tool: its definition and handler in `app/llm/tools.py` (the dict of handlers, `ToolRouter`), so the policy applies to it.
3. A prompt change that a journey depends on: add or adjust the fake-LLM scenario (`agent-fake-llm-scenario.md`).
4. A prompt change on judgement (right tool, tone, not asking): add or adjust `scripts/prompt-lab/scenarios/<NN>-<slug>.yaml` (`user_message`, `expected_tools`, `forbidden_tools`, `expected_absent`, one-sentence `expected_behavior`; format in its `README.md`).

## Tests owed
- Prompt rule: `agent/tests/test_personality_engine.py` — `PersonalityEngine().get_system_prompt()` with `personality_repo` patched; find the rule's line and assert its key phrases are there.
- Policy: `agent/tests/test_policy_engine.py`, class `TestTheShippedPolicy` — `policy_engine.evaluate(tool, args, source)` gives the expected `Mode`, and what stays allowed stays allowed.
- Sub-agent: `agent/tests/test_subagent_registry.py` — the shipped file loads, its model alias, and the tools it can reach (pattern: `test_researcher_only_reaches_tools_that_read`).
- Guard or loop change: a test driving the shipped fake-LLM scenarios through `run_tool_loop` (pattern: `test_learning_claims.py`, `test_reminder_guard.py`), red first for a bug.
- Skills mechanism: `agent/tests/test_skill_index.py`, `test_skills_by_moment.py`.
- Run: `task wt:test:agent -- tests/<file>.py -k <name>`; lint: `task wt:lint:agent` (or `task fix:all` before the push).
- **Judgement is never tested in CI.** CI only checks the prompt-lab scenarios parse (`task e2e:eval:check`). Replay on the real model by hand when a prompt changes: `task e2e:eval -- --only <name>` (needs `task e2e:up` and `ANTHROPIC_API_KEY`), or Actions › Nightly › `what: eval`. Say in the PR whether it was run.

## Standards
- [agent/architecture](../agent/architecture.md) — Skills, Sub-agents, Policy and approvals, Data ownership
- [agent/testing](../agent/testing.md)
- [agent/memory](../agent/memory.md) — when the change touches what she remembers
- [global/e2e-environment](../global/e2e-environment.md) — *The eval suite*

## Pitfalls
- `system_prompt` is the cached stable prefix (`app/llm/prompt_cache.py`): nothing per-request (date, memory, directives) goes in `default.yaml`; it goes in the volatile block.
- A preference coded as a rule or a branch is refused in review (owner, 7 Oct.): code keeps only what is not a preference — channels, guards against unbacked claims, tool contracts.
- Never rely on the prompt to stop a destructive call: a new destructive tool not named `delete_*` and not a `manage_*` `action: delete` needs its own `ask` rule. A malformed `policy.yaml` stops the agent from starting.
- A prompt test that asserts a whole sentence breaks on every rewording: assert the few phrases that carry the rule.
- A fake-LLM scenario does not read the prompt: a passing journey never proves a prompt rule — only `task e2e:eval` does.
- `agent/architecture.md` still says skills are « on demand, never injected »; the code injects the skills of the current moment (MAG-345, `test_skills_by_moment.py`). Follow the code until the standard is updated.
- A sub-agent sees neither the conversation nor `delegate`; its calls carry `source="subagent:<name>"` and the policy still applies.
