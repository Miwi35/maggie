# Add a fake-LLM scenario

**When**: an e2e step (Playwright or Maestro) talks to Maggie and needs a deterministic answer through the real tool loop (`LLM_PROVIDER=fake`). Not for judging her wording or tool choice: that is a prompt-lab scenario, see `agent-behaviour-change.md`. The journey itself: `e2e-web-journey.md` / `e2e-mobile-journey.md`.
**Reference**: MAG-639 (`3119c9da6`) — `agent/fixtures/fake-llm/97-move-meal-by-name.yaml`, its test in `agent/tests/test_fake_llm.py` (`test_the_meal_scenario_looks_it_up_by_title_then_moves_it_on_the_id_found`), the journey `e2e/web/tests/meals-chat-move.spec.ts`. Format: `agent/fixtures/fake-llm/README.md`; reader: `agent/app/llm/fake.py`.

## Steps
1. `agent/fixtures/fake-llm/<NN>-<slug>.yaml` — pick `NN` so it wins over broader scenarios (first match in file-name order). Header comment: the sentence, the ticket, what the run proves. Then `name`, `description`, `match`, `turns`.
2. `match` narrow enough that no other journey's message hits it: `user_matches` anchored on the whole sentence the journey types (case-insensitive, accents as `[ée]`), plus `history_contains` / `system_contains` when the step depends on earlier state.
3. `turns`: one per model turn; a turn with `tools` loops back, the last turn's `text` is the answer. Script only tools the MCP server really exposes, with the exact argument names of the tool (`api/contract/mcp-tools.json`).
4. Ids: never literal. `\1` from `user_matches` (the journey says the id), `history_matches` (replayed tool result of an earlier turn), or top-level `results_matches` (a tool called earlier in this run: separate keys with `\W+`, end on the next key). Dates: `${today}` / `${tomorrow}` (Paris day of the stack clock).
5. Agent calls that are not chat each need their own scenario if the journey triggers them: context router (`system_contains: routeur de contexte`), thread summary, memory encoding, transcription cleanup, proaction — table *Beyond chat* of the README.
6. Add one line for the scenario in `agent/fixtures/fake-llm/README.md`, under the section of its module.
7. Data the tools read goes in `api/fixtures/e2e/` (see `e2e-web-journey.md`), or is created by the journey itself as its own user.
8. In `e2e/impact-map.yml`, list the scenario file under the journey's `paths`.

## Tests owed
- `agent/tests/test_fake_llm.py` (class `TestMatching`) — drive `FakeAnthropicClient(fixtures_dir=DEFAULT_FIXTURES_DIR)` with the journey's sentence; assert each turn's tool `name` and `input`, and the id captured from a result shaped exactly like the tool's JSON.
- Or, when the scenario chains several tools, `agent/tests/test_<domain>_scenarios.py` — `run_tool_loop` with `ToolRouter()` and a fake `mcp_client` patched on `app.llm.tools.mcp_client` (pattern: `test_recipe_scenarios.py`, `test_routine_scenarios.py`); assert the calls reached and their arguments.
- The journey that uses it asserts `calledTools(events)` / `toolResults(events)` and the data in the API, never only the scripted text.
- Run: `task wt:test:agent -- tests/test_fake_llm.py -k <words of the test name>` (paths are relative to `agent/`).

## Standards
- [global/e2e-environment](../global/e2e-environment.md) — *The fake LLM*, *The eval suite*
- [agent/testing](../agent/testing.md)
- [global/testing](../global/testing.md)

## Pitfalls
- A file that fails to load (bad regex, turn with neither `text` nor `tools`, bad `stream_delay_ms`) is **skipped with a log, not fatal**: the journey then gets `[fake-llm] aucun scénario ne correspond à : '…'`. The pytest above is what catches it before CI.
- The fake never reads tool results: a scripted sentence cannot describe data. Assert data against the API, the wording against the scenario.
- Only the last turn's `text` reaches the bubble (MAG-229): an intermediate `text` is never what the journey should look for.
- API JSON escapes non-ASCII (`œ` arrives as `œ`): a `results_matches` on a label needs both spellings (`B(?:\W+u0153|œ)uf`).
- `history_contains` ignores the message being answered, and Maggie's own past answers are in the history as text: a needle meant to prove a replayed tool round must be something no message ever said.
- A streamed message also calls the context router: `11-context-router-new.yaml` opens a new thread for any message. A step that must join an existing thread needs its own router scenario numbered before it (`10-context-router-existing.yaml`).
