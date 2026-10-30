// The weighings of the Santé › Suivi journey, written over HTTP (MAG-706).
//
// The phone has no form for a weighing — they come from Maggie, the admin or an import — so this
// writes three for the journey's own user through `POST /api/body-measurements`: 72,9 kg five days
// ago, 72,4 kg two days ago, and 71,8 kg with 21,3 % of body fat today. The days count from TODAY,
// the stack's day the phone reads its days with — never this host's clock, which is not pinned —
// at 09:00 UTC, the same day in Paris whatever the season. The API requires `measuredAt`.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   EMAIL   the journey's user (`${output.journeyEmail}`)
//   TODAY   the stack's day, YYYY-MM-DD (`${TODAY}`)

(function () {
const base = E2E_BASE_URL

function fail(message) {
  throw new Error('[body-measurements-api] ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: EMAIL }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + EMAIL + ': ' + login.body)
}
const token = JSON.parse(login.body).token
const DAY = 24 * 60 * 60 * 1000
const today = new Date(TODAY + 'T09:00:00Z').getTime()
if (isNaN(today)) {
  fail('TODAY is not a day: ' + TODAY)
}

function daysAgo(days) {
  return new Date(today - days * DAY).toISOString()
}

const weighings = [
  { measuredAt: daysAgo(5), weightKg: 72.9 },
  { measuredAt: daysAgo(2), weightKg: 72.4 },
  { measuredAt: daysAgo(0), weightKg: 71.8, bodyFatPercent: 21.3 },
]
weighings.forEach(function (weighing) {
  const created = http.post(base + '/api/body-measurements', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json', Authorization: 'Bearer ' + token },
    body: JSON.stringify(weighing),
  })
  if (!created.ok) {
    fail('POST /api/body-measurements ' + JSON.stringify(weighing) + ' answered ' + created.status + ': ' + created.body)
  }
})
})()
