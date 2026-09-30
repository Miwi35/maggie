# The Web Journeys

Playwright against the admin, running inside the e2e stack (MAG-97).

```sh
task e2e:up            # the stack, once
task e2e:web           # reseed, then every journey
task e2e:web -- --project=phone tests/smoke.spec.ts
task e2e:web:lint      # ESLint + the Playwright plugin; also in `task lint:all`
task e2e:web:typecheck # seconds; run both after editing a helper
task e2e:web:shell     # a shell in the Playwright container
task e2e:admin:build   # after editing admin/src — nginx serves a built bundle
```

Never `npx playwright test` on the host: the browsers, the base URL and the
seed manifest all come from the container. `task e2e:web` reseeds first, so a
second run starts from the same world as the first.

## Where it runs, and why that matters

The journeys browse **`http://traefik`** — the stack's own router, from inside
its network. Three things follow:

- no ephemeral host port to discover;
- the admin's relative URLs (`/api`, `/.well-known/mercure`, `/agent`) all
  resolve against one origin, exactly as they do in production behind Traefik;
- every request that is *not* that origin is aborted. The admin pulls a Google
  font and react-admin phones home to a telemetry endpoint; both are slow or
  blocked depending on the runner, and neither has anything to do with a
  journey. The e2e stack simulates every external service, so a request leaving
  the network is a bug, not a dependency.

## Layout

| | |
|---|---|
| `fixtures/` | the signed-in user, the neighbour, the seed manifest |
| `helpers/` | Mercure probes, AG-UI stream parsing, API polling |
| `pages/` | one page object per screen, `AdminShell` underneath them all |
| `tests/` | the journeys |

Import from `fixtures/index.js` rather than `@playwright/test` — that is where
`test` gains `session`, `api`, `otherUser`, `twoWindows`, `anonymousPage` and
`pageWithToken`, and where every context is cut off from the internet.

## Three widths

`desktop` (1440), `tablet` (834) and `phone` (393), straddling MUI's `md`
breakpoint where react-admin folds the sidebar away. Only tests tagged
`@responsive` run on all three — running everything three times would triple a
suite whose slowest steps have nothing to do with layout. Tag a test when its
*layout* is the point; MAG-38 and MAG-90 will add more.

## Writing a journey

1. **Sign in through the fixture, never through the UI.** Google's consent
   screen cannot be driven. `test({ page })` is already signed in as
   `e2e@maggie.local`.
2. **Read ids from the seed manifest** (`seedId('e2e_task_open')`), never from
   a literal ULID — they change every seed. Dates come from `seedAnchorDate()`,
   not from the wall clock.
3. **Scope to the page.** `AdminShell.content` is the page without the menu
   beside it or the chat panel after it. React-admin puts all three in one
   `<main>`, and Maggie quotes the page's own wording often enough that an
   unscoped `getByText` matches twice.
4. **Wait for indexed entities.** Anything written through the API is indexed
   asynchronously and the collections are served from Elasticsearch, so a row
   exists before it is findable: `waitForIndexed`, or a page object that
   reloads while it waits. Reading once is how a working feature gets reported
   as broken.
5. **Subscribe before you act.** `openSubscribed(page, () => page.open())` for
   a real-time assertion — an update published before the hub registered the
   subscriber is never delivered.
6. **Assert Maggie's wording against the scenario, her effects against the
   data.** `LLM_PROVIDER=fake` scripts the model and nothing else; the fake
   does not read tool results, so its sentences cannot prove a write happened.
   `ChatPanel.send()` returns the AG-UI events of the whole run — which tool
   ran, how many deltas, whether the run finished.

## The chat journey

`tests/chat.spec.ts` is the one journey that is serial, because the
conversation is stateful: the context router opens a context on the first
message and every later one joins it, until a message deliberately changes the
subject. It covers streaming, a tool call seen in the Mind panel and verified
in the database and on the calendar, the change of subject, a thread resumed
after a reload, and a proaction arriving over Mercure (MAG-99).

It is also the one file that turns retries off. A serial group replays whole
and nothing reseeds between the attempts, so the second one starts on the
conversation the first one wrote — and every count below would fail as a
duplicate on a run where nothing was wrong.

Most of its assertions are counts, and that is deliberate. MAG-93 found ten
past regressions in this module and none was about what Maggie said; three of
them are `176c40c`, which fixed the same symptom — one message shown twice — in
three independent places. So the journey asserts one message id per run, one
bubble per message, and the same again after a reload.

**The microphone is out of reach.** `navigator.mediaDevices` only exists in a
trustworthy origin and the stack answers on plain `http://traefik`, so
`useVoiceRecorder` reports "Accès au microphone refusé" and nothing in
Playwright works around it — not the permission, not Chromium's fake capture
device, not `--unsafely-treat-insecure-origin-as-secure`, which this build
ignores even with a persistent profile. Dictation is asserted over HTTP in
`e2e/smoke/smoke.sh` (step 9) until MAG-145 gives the stack an origin the
browser trusts. TTS has no surface in the admin at all: the web never speaks,
so it lives in the smoke journey and in the Maestro flows (MAG-98).

## Two windows, not two tabs

`twoWindows` gives the same user two live views. It is two browser *contexts*
because headless Chromium freezes a hidden tab: a second page in the same
context stops rendering the moment the first is acted on, both views sit
unchanged, and real-time looks dead when it is not.

`expectRealtimeSync` wraps the check and fails if the observing view navigated
— a reload would satisfy the assertion while proving nothing.

## What the harness found on its first run

Worth knowing, because each was invisible to every test that existed before:

- **The global search's results lead nowhere.** API Platform Admin takes the
  IRI as a record id; `/api/search` returns the bare Elasticsearch one, and
  `getResultPath` passes it straight through. `getOne` then resolves it against
  the origin, requests `/<ulid>`, gets a 404, and the screen says "introuvable"
  — for every index, from both the search bar and the results page. Found by
  the chat journey following the calendar's `?eventId=` deep link (MAG-144).

- **The Mercure image was unpinned, everywhere.** CI pulls fresh, got a build
  that had renamed the subscribe parameter from `topic` to
  `match`/`match_urlpattern`, and every subscription answered
  `400 unknown topic matcher query parameter`. Production had
  `imagePullPolicy: Always` and no version either — one pod restart from the
  same silence. Pinned by digest to what production already serves; moving to
  the new parameter is MAG-142.

- `MERCURE_JWT_SECRET` was 144 bits. lcobucci/jwt refuses to sign HS256 with
  less, `MercurePublishMiddleware` catches and logs the failure, so the stack
  had no real-time at all and looked healthy. Fixed here for e2e and for the
  example files; MAG-141 checks the deployed value and adds a boot-time guard.
- `ChatWidget` called `new URL()` on a Mercure URL that is relative in
  production — b16916d again, in a second place. Fixed here, with a unit test
  that loads the module with the production value.
- A logged-out visitor waited 7–25 seconds on a blank page before the login
  screen appeared (MAG-140: a livelock in react-admin's `requireAuth` gate, not
  the query retries). Fixed; `LoginPage.expectShown` now allows 5 seconds and
  the smoke journey holds an anonymous visitor to 3.
- Updates were published without `private: true`, so a user holding their own
  valid token received another user's updates by subscribing to their topic.
  Fixed by MAG-139 while this branch was in review; `tests/mercure.spec.ts` is
  what keeps it fixed.
