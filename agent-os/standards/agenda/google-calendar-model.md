# Google Calendar Event Model

Events follow the **Google Calendar API** structure.

## Recurrence
- RRULE stored as string (RFC 5545 format) on the Event entity
- Expanded at query time via `simshaun/recurr` — no separate Occurrence table
- Exception instances: separate Event records with `recurringEvent` + `originalStartAt`

## Reminders
JSON field: `{"useDefault": bool, "overrides": [{"method": "popup", "minutes": 60}]}`

## Status
Enum: `confirmed`, `tentative`, `cancelled`
