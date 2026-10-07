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
| `mappings/open-meteo.json` | Open-Meteo — city search and daily forecast (`get_weather`, MAG-156) | `OPEN_METEO_FORECAST_URL`, `OPEN_METEO_GEOCODING_URL` |

`google.json` covers every call the agenda can make from the browser: list the
calendars, import one (watch + pull), export a local agenda (create a calendar),
push an event (insert, **patch**, update, delete), and delete the calendar when
an agenda goes "on Google too". `patch` is the one the field-level push uses
since `544c9e5`, and its `updated` (09:00) is deliberately **newer** than the
events list's (08:00): that is what lets a journey prove the next pull skips an
event Google has not touched since, instead of overwriting the local change.

The all-day "today" Google event (MAG-204) is the one stub that follows the
calendar: its mapping carries `{{now}}`, but WireMock's JVM does not follow
`E2E_NOW` and disagrees with the seed's anchor for an hour or two each night, so
`task e2e:seed` rewrites it in memory to the anchor day (`e2e/wiremock-today.sh`,
MAG-234). A stub that needs "today" does the same: never rely on `{{now}}`.

Two stubs answer something derived from the request rather than a constant, and
both are load-bearing:

- **the watch channel id** is `e2e-channel-<calendarId>`. A constant would give two
  connected agendas the same channel, and `findByGoogleWatchChannelId()` is a
  `findOneBy` — so a webhook meant for one agenda could pull into the other, and
  the "a local change survives the pull" assertion would pass without the pull
  ever reaching the event it is about;
- **an inserted event's id** is random. `(google_event_id, agenda_id)` is unique,
  so a constant would make a second event in the same connected agenda a
  constraint violation.

The Google Tasks side answers **two** lists, `e2e-task-list` ("Mes tâches", the
one the seed starts connected to) and `e2e-task-list-courses` ("Courses
Google"). One would make MAG-118's journey meaningless: the settings screen
states a single list instead of offering it, and the sync reading the chosen
list rather than the first one can only be asserted where there is more than
one. The tasks-in-a-list stub answers for any list id, so which list the stack
asked for is the one thing that varies — and the journal is how the journey
reads it.

Two things are **not** here:

- **Edge TTS.** `edge_tts` opens its own WebSocket to Microsoft, so no base URL
  redirects it. The agent switches provider instead: `TTS_PROVIDER=fake` streams
  a fixed silent clip. See `agent/app/tts/synthesis.py`.
- **Google sign-in.** The e2e test login (`POST /api/auth/e2e/login`) replaces it
  entirely, so a stubbed `tokeninfo` would have no caller.
- **Anthropic.** Same reason as Edge TTS, and then some: a stubbed HTTP response
  would have to script a whole tool-use conversation in JSON. The agent switches
  provider instead — `LLM_PROVIDER=fake` answers from the scenario files in
  `agent/fixtures/fake-llm/` (MAG-95).

`open-meteo.json` answers the forecast with the two days the request names
(`start_date`, `end_date`, echoed back by templating — the stub declares
`response-template`, which is what turns it on under `--local-response-templating` —
so it follows the clock without `{{now}}`): 18.5 °C at most and 9.5 °C at least, light rain.
`38-weather.yaml` repeats those figures in Maggie's scripted answer, and the
journey in `chat.spec.ts` drives both — change the figures in one place and
change them in the other. The API caches a forecast for 30 minutes, so a stub
edit may need `task e2e:down` before it shows.

One pairing to keep in mind: `mappings/openai.json` dictates a fixed sentence,
and `agent/fixtures/fake-llm/20-transcription-cleanup.yaml` returns that sentence
cleaned up. Change one and change the other, or the voice path stops making sense
end to end.

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

The events list does exactly that for one of its two events: « Escapade importée
de Google » is an all-day event for today, so the mobile import journey
(`e2e/mobile/flows/04-calendar-import.yaml`) has something it can see in the
week view. `timezone='Europe/Paris'` is not decoration — `now` defaults to UTC,
which is yesterday's date for the seed between 00:00 and 02:00 Paris time. The
other event stays pinned to March 2026 on purpose: the web journeys look it up
by title and do not care where it falls.
