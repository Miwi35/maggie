// The weighings of the Santé › Suivi journey, written over HTTP (MAG-706).
//
// The phone has no form for a weighing — they come from Maggie, the admin or an import — so this
// writes three for the journey's own user through `POST /api/body-measurements`: 72,9 kg five days
// ago, 72,4 kg two days ago, and 71,8 kg with 21,3 % of body fat now (no `measuredAt`: the API
// dates it). The dates follow the stack's clock, the one the phone reads its days with.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   EMAIL   the journey's user (`${output.journeyEmail}`)

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

const weighings = [
  { measuredAt: new Date(Date.now() - 5 * DAY).toISOString(), weightKg: 72.9 },
  { measuredAt: new Date(Date.now() - 2 * DAY).toISOString(), weightKg: 72.4 },
  { weightKg: 71.8, bodyFatPercent: 21.3 },
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
