# Reference implementations studied — MAG-150

The ticket names the directories; these are the files inside them that the build copies
from, and why.

## What already exists and must keep working

| File | What it settles |
|---|---|
| `api/modules/calendar/src/Service/AgendaResolver.php` | MAG-230's exact resolution: id, then the folded name among the **caller's** agendas only, with the list of agendas in the error. The deduction is grafted onto this, not beside it |
| `api/modules/calendar/src/Mcp/Tool/CreateEventTool.php` | Where the choice is made today: `agenda_id` resolved, then `CreateEventCommand`; the success payload already returns `event.agenda` |
| `api/modules/calendar/src/MessageHandler/CreateEventHandler.php` | The no-default-agenda guard (MAG-149) and its wording — « ask which agenda to use ». Stays as the safety net for `CreateEventProcessor`, the only other caller |
| `api/modules/calendar/src/Entity/Agenda.php` | `name`, `description`, `isDefault`, the one-default-per-user unique index. There is no `Event::attendees`, so the ticket's « mêmes participants » signal can only be read out of the summary text — recorded in the plan |
| `api/modules/calendar/src/Repository/AgendaRepository.php` | `findByUser()`, `findDefault()` — the two queries the suggester needs, already user-scoped |
| `api/modules/calendar/src/Repository/EventRepository.php` | `dateRangeQueryBuilder()` shows how an event query is scoped to a user — through `agenda.user`, since an event has no user of its own |

## Patterns copied

| File | Pattern |
|---|---|
| `api/modules/calendar/tests/Mcp/AgendaByNameToolsTest.php` | The shape of the new tool test: a `create()` helper decoding the JSON, a data provider over the spoken forms, `eventCount()` before/after to prove nothing was written, and an assertion that no other user's agenda leaks into an error |
| `api/modules/grocery/tests/Mcp/GroceryToolsTest.php` | The reference the testing standard names for an MCP tool: no user, bad input, DB state, `assertMercureUpdatePublished()`, `assertElasticsearchIndexDispatched()` |
| `api/modules/calendar/src/Service/ConflictDetectionService.php` | A calendar service doing read-only logic over the user's events, injected into a tool — the closest sibling to `AgendaSuggester` |
| `agent/fixtures/fake-llm/37-create-event-named-agenda.yaml` | MAG-230's scenario, and the one the new ones differ from by a single line: no `agenda_id` |
| `agent/fixtures/fake-llm/36-create-event-retry.yaml` | How a scenario scripts a tool that comes back with an error and a turn that recovers — the shape journey C needs |
| `e2e/web/tests/chat.spec.ts` (« an event asked for in a named agenda lands in that agenda in one tool call ») | The journey this extends: `calledTools`, `toolResults`, `waitForIndexed` on `/api/events`, and `seedId()` to name the expected agenda |
| `api/fixtures/e2e/20-calendar.yaml` | Anchor-relative dates (`<e2eDate("…")>`) and the neighbour's agenda that proves isolation |
| `scripts/prompt-lab/scenarios/15-conflit-agenda.yaml` | The judgement half: `forbidden_tools` is how « she must not create it » is expressed, and its header explains why that half cannot be a journey |
