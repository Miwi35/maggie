---
name: agenda-events
description: "Agenda event model following Google Calendar conventions. Use when working with events, recurrence (RRULE), reminders, exception instances, or event status in the calendar module (api/modules/calendar)."
user-invocable: false
---

# Agenda Event Model

**Use when** working on events, recurrence, reminders, exception instances or status in `api/modules/calendar`.

**Read** `agent-os/standards/agenda/google-calendar-model.md`; entity rules: `agent-os/standards/api/entities.md`.

## Gotchas

- Recurrence is an RFC 5545 RRULE string on the Event, expanded at query time with `simshaun/recurr`: there is no Occurrence table.
- An exception instance is a separate Event with `recurringEvent` + `originalStartAt`.
- Status is `confirmed`, `tentative` or `cancelled`; reminders are `{useDefault, overrides: [{method, minutes}]}`.
- The MCP schedule contract (start and end, never a duration; all-day end excluded) is in `agent-os/standards/api/mcp-tools.md`.
