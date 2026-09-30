---
name: agenda-events
description: "Agenda event model following Google Calendar conventions. Use when working with events, recurrence (RRULE), reminders, exception instances, or event status in the calendar module (api/modules/calendar)."
user-invocable: false
---

# Agenda Event Model

Events follow the **Google Calendar API** structure.

## Recurrence

- RRULE stored as string (RFC 5545 format) on the Event entity
- Expanded at query time via `simshaun/recurr` — no separate Occurrence table
- Exception instances: separate Event records with `recurringEvent` + `originalStartAt`

## Reminders

JSON field on Event:

```json
{
  "useDefault": true,
  "overrides": [
    {"method": "popup", "minutes": 60}
  ]
}
```

## Status

Enum: `confirmed`, `tentative`, `cancelled`

## Entity Location

`api/modules/calendar/src/Entity/Event.php`

Implements `MercurePublishable` — see `api-entities` skill for entity conventions.

## Reference

For full details, read `agent-os/standards/agenda/google-calendar-model.md`
