# Prompt-lab scenarios

What Maggie is expected to do, written once and read by two consumers:

- **`/prompt-lab`**, which replays a scenario through a Claude Code subagent —
  free, interactive, for iterating on a prompt;
- **the eval suite**, `task e2e:eval`, which replays all of them against the
  **real model** through the e2e stack, nightly or on demand (MAG-95). It is
  separate from CI and gates nothing: a prompt regression is a signal, not a
  merge blocker.

Neither belongs in a journey. A journey asserts on plumbing and runs with
`LLM_PROVIDER=fake`; these scenarios assert on *judgement* — tone, picking the
right tool, keeping the thread — which only the real model has. That is the
split MAG-95 draws.

## Format

```yaml
name: agenda-du-jour            # required
description: …                  # for humans
channel: chat                   # chat (default) or stream — stream is the only
                                # one that exercises context routing
steps:
  - user_message: "Qu'est-ce que j'ai de prévu aujourd'hui ?"
    expected_tools: [get_upcoming_events]     # each has to be called
    forbidden_tools: [create_event]           # none may be
    expected_contains: ["Alex"]               # substrings, case-insensitive
    expected_absent: ["je n'ai pas accès"]
    expected_context: created                 # created | matched (channel: stream)
    expected_behavior: >                      # judged by the model against this
      Elle annonce le déjeuner en une ou deux phrases naturelles, en français.
```

A single-turn scenario may put `user_message` and its expectations at the top
level instead of under `steps` — the shape `/prompt-lab` already documents.

`expected_behavior` is the part a regex cannot check, so a second call to the
real model judges the answer against it and has to return `{"pass": …}`. Write
it as one verifiable sentence about what the answer must do, not as a mood.

## What the scenarios run against

The e2e stack, seeded by `task e2e:seed` — so the agenda, the grocery list and
the recipes are the same fixtures every night, and a failure is the model or the
prompt, never the data. Ids differ between runs (ULIDs), so assert on labels.

The runner empties the agent's own database between scenarios: each one starts
with no conversation history, no context and no memory.

## Adding one

Keep it about judgement. If the assertion would hold with any plausible wording,
it belongs in a journey with the fake instead — cheaper, deterministic, and it
runs on every PR.
