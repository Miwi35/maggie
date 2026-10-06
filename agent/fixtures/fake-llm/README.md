# Scenario fixtures for the fake LLM

What Maggie answers when `LLM_PROVIDER=fake` — the mode the e2e stack runs in,
so a journey can talk to her without calling Claude (MAG-95). One file per
scenario. The code that reads them is `agent/app/llm/fake.py`.

Only the model is replaced: the tool loop, the AG-UI streaming gateway, the MCP
client and the metrics are the production ones. A scenario therefore scripts
*the model's side of the conversation* — the text, and the tools it asks for.
The tools themselves really run, against the real MCP server and the seeded
database.

## Format

```yaml
name: upcoming-events        # for the logs; defaults to the file name
description: …               # for humans

match:                       # every condition declared has to hold
  user_contains: "prévu"     # a string, or a list of strings — case-insensitive
  user_matches: 'demain|ce soir'   # a regex on the last user message
  system_contains: "routeur de contexte"   # matched on the system prompt
  history_contains: "mon budget"   # matched on the conversation sent, minus the last message

turns:                       # one entry per model turn, in order
  - text: "Je regarde."      # optional: what she says this turn
    tools:                   # optional: the tools she asks for this turn
      - name: get_upcoming_events
        input: {days: 1}
  - text: "Tu as un déjeuner avec Alex à midi."
```

A turn with `tools` answers `stop_reason: tool_use`, so the loop runs them and
comes back for the next turn. A turn without ends the exchange. A turn needs at
least one of the two.

Only the **last** turn's text is the answer: the gateway stores, shows and reads
aloud what the model said after its last tool call, and the text of the turns
before is a passing state at most (MAG-229). Script an intermediate `text` to
prove that — `36-create-event-retry.yaml` — not to say something the journey
expects to find.

**Which scenario wins.** The first one that matches, in file name order — so a
numeric prefix is how you express precedence. A scenario declaring no condition
at all matches nothing; the catch-all is `default: true`, and it is always tried
last whatever its file is called.

**No catch-all ships here, on purpose.** When nothing matches, Maggie answers
`[fake-llm] aucun scénario ne correspond à : '…'`. A journey's assertion then
fails on a sentence that names its own cause, instead of on a plausible answer
that happens to be wrong — the same reason `task e2e:smoke` fails on a WireMock
request without a stub.

**Ids.** A scripted answer cannot hardcode one: they differ between runs. When
a scenario declares `user_matches`, its text **and every string in its tools'
`input`** go through Python's `Match.expand`, so `\1` inserts the first capture
group. That is how `10-context-router-existing.yaml` answers with the id of a
context the stack created seconds earlier, and how `43-grocery-fallback.yaml`
calls `move_to_fallback` on a shop the seed invented — the journey says the id,
the scenario carries it into the real MCP call. Lists and nested mappings are
walked; numbers and booleans are left alone, so a `quantity: 4` stays a number.
Check which kind of id you are matching — API entities carry ULIDs, the agent's
own rows carry `uuid4().hex`.

A scenario needing an id it cannot capture has no way to get one: write the id
into what the journey *says*. The sentence then reads oddly for a human, which
is the honest trade — judging whether Maggie could have worked out which shop
"le primeur" is belongs to `task e2e:eval` on the real model, not here.

**Editing.** The files are re-read whenever one of them changes on disk, so a
fixture fixed mid-session takes effect on the next message — no agent restart.

**Slow answers.** `stream_delay_ms: 600` at the top level of a scenario makes the
stream wait that long between two text deltas, so a journey can act on an answer
that is still being written (MAG-223: cutting Maggie off). Default `0`; a negative
or non-integer value is refused at load. `41-dictated-long-story.yaml` uses it, and
`41-dictated-interrupted-known.yaml` — `history_contains: coupé la parole` — proves
the turn after an interruption was told about it.

## Beyond chat

Four calls in the agent are not a conversation, and each has its scenario here
because otherwise they would fall through to "no scenario":

| Call | Matched on |
|---|---|
| Context routing, before every streamed message and after every proaction | `system_contains: routeur de contexte` |
| Thread summary, once a thread has grown by `CONTEXT_SUMMARY_EVERY_MESSAGES` | `system_contains: tu résumes un fil de conversation` |
| Transcript cleanup, after Whisper | `user_contains: assistant de transcription` |
| A proaction (`POST /agent/proaction`) | the prompt it was scheduled with |

The summary is the one whose absence does not show up where it happened: it is
*stored*, then injected into every later system prompt, so a missing scenario
turns into a journey failing several steps further on. `12-context-summary.yaml`
covers the call and `71-context-summary-recall.yaml` proves the injection, by
matching on the summary's own text in the system prompt.

**What the history held** is proved the same way, with `history_contains` instead
(MAG-13). The messages array is no more observable from a browser than the system
prompt, and since MAG-13 it is built around the thread the message was routed into
rather than from the last fifty messages of everything. The e2e stack runs with
`RECENT_HISTORY_MESSAGES=2`, so a sentence from an older thread can only be there
because that thread's own messages were loaded —
`72-thread-history-recall.yaml` declares one and is unreachable otherwise. The
condition deliberately ignores the message being answered: `user_contains` already
covers that one, and counting it would let a scenario "prove" the history carried a
sentence the user had just typed.

A behaviour preference (`add_instruction` with `kind: behavior`, MAG-22) is
stored and injected the same way, and is proved the same way:
`60-behavior-preference.yaml` stores « Tutoie-moi et évite les emojis » and
`61-behavior-applied.yaml` declares that sentence as its `system_contains`, so
it can only answer once the preference really is in front of the model.

A proaction has no conversation history of its own — the prompt is the whole
request — but it does read the open threads' summaries, and the message it
produces is routed into a thread like any other (MAG-14). So it costs two calls:
the proaction itself, then the router on what it wrote.
`80-proaction-bin-night.yaml` is the one the chat journey triggers for delivery;
`81-proaction-thread-recall.yaml` declares the thread summary's own text as its
`system_contains`, and is therefore the proof that the summaries reach a
proaction at all.

`82-greeting.yaml` answers « Bonjour Maggie » and nothing else: the two-window chat
journey sends it from one window to see the exchange reach the other (MAG-109).

The transcript cleanup pairs with the WireMock Whisper stub: that stub returns
one fixed sentence, and `20-transcription-cleanup.yaml` returns it cleaned. Change
one and change the other.

## Adding a scenario

1. Pick a file name whose prefix puts it where you want in the matching order.
2. Make `match` narrow enough that another journey's message cannot hit it.
3. Script only tools the MCP server really exposes — the fake logs an error when
   a scenario calls a tool the agent was not offered, which is how a module
   dropping out of `discovery.scan_dirs` shows up here.
4. Data the tools need goes in `api/fixtures/e2e/`, like any other e2e data.
