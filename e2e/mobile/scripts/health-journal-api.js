// The weighing of the Santé › Journal journey, written over HTTP (MAG-750).
//
// The phone has no form for a weighing, so this writes one for the journey's own user through
// `POST /api/body-measurements`: 71,6 kg TODAY at 06:00 UTC — the same day in Paris whatever the
// season, and before any hour the journey may run at. TODAY is the stack's day, YYYY-MM-DD, never
// this host's clock. The journal lays the day's entry down from the weighing: this polls
// `GET /api/health/days/{TODAY}` until it carries 71,6 kg, so the flow never opens an empty day.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   EMAIL   the journey's user (`${output.journeyEmail}`)
//   TODAY   the stack's day (`${TODAY}`)

(function () {
const base = E2E_BASE_URL

function fail(message) {
  throw new Error('[health-journal-api] ' + message)
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: EMAIL }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + EMAIL + ': ' + login.body)
}
const token = JSON.parse(login.body).token
if (isNaN(new Date(TODAY + 'T06:00:00Z').getTime())) {
  fail('TODAY is not a day: ' + TODAY)
}

const weighing = { measuredAt: TODAY + 'T06:00:00Z', weightKg: 71.6 }
const created = http.post(base + '/api/body-measurements', {
  headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json', Authorization: 'Bearer ' + token },
  body: JSON.stringify(weighing),
})
if (!created.ok) {
  fail('POST /api/body-measurements ' + JSON.stringify(weighing) + ' answered ' + created.status + ': ' + created.body)
}

const deadline = Date.now() + 30000
for (;;) {
  const response = http.get(base + '/api/health/days/' + TODAY, {
    headers: { Accept: 'application/json', Authorization: 'Bearer ' + token },
  })
  if (!response.ok) {
    fail('GET /api/health/days/' + TODAY + ' answered ' + response.status + ': ' + response.body)
  }
  if (JSON.parse(response.body).weightKg === 71.6) {
    break
  }
  if (Date.now() >= deadline) {
    fail('the weighing never reached the journal at 71,6 kg within 30 s: ' + response.body)
  }
  pause(300)
}
})()
