# External services, simulated

WireMock stands in for every third-party HTTP API the e2e stack would otherwise
call (MAG-94). A journey that reached the real internet would be slow, would
depend on someone else's uptime, and would stop being deterministic — which is
the one thing this stack exists to guarantee.

| File | Stands in for | Reached through |
|---|---|---|
| `mappings/enablebanking.json` | Enable Banking — banks, consent, session, balances, transactions | `ENABLE_BANKING_BASE_URL` |
| `mappings/google.json` | Google Calendar and Google Tasks | `GOOGLE_API_BASE_URL` |
| `mappings/openai.json` | Whisper speech-to-text | `OPENAI_BASE_URL` (agent) |

Two things are **not** here:

- **Edge TTS.** `edge_tts` opens its own WebSocket to Microsoft, so no base URL
  redirects it. The agent switches provider instead: `TTS_PROVIDER=fake` streams
  a fixed silent clip. See `agent/app/tts/synthesis.py`.
- **Google sign-in.** The e2e test login (`POST /api/auth/e2e/login`) replaces it
  entirely, so a stubbed `tokeninfo` would have no caller.

The conversation model is not stubbed here either — that is MAG-95's fake LLM.

## Editing a stub

WireMock reads this directory once at startup. After an edit:

```sh
task e2e:restart:wiremock   # or task e2e:down && task e2e:up
```

The admin API is enabled, so a journey that needs a one-off answer can push a
stub at runtime against `http://wiremock:8080/__admin/mappings` from inside the
stack, and `DELETE /__admin/mappings` resets to what is on disk.

## Dates in these stubs

They are fixed (`2026-03-…`) because WireMock has no access to the seed anchor.

**Do not line them up by pinning the anchor** with `task e2e:seed -- --now=…`.
That moves the entire seeded world into the past while the containers' clock
stays real, so every endpoint deriving its period from `new DateTimeImmutable`
— the finance dashboard, the daily score, "upcoming events" — returns an empty
list. The failure is silent and CI-only.

A journey that needs imported data near today should template the stub instead:
response templating is enabled on the container, so

```json
"booking_date": "{{now offset='-3 days' format='yyyy-MM-dd'}}"
```

gives a date relative to the run, with no effect on the anchor.
