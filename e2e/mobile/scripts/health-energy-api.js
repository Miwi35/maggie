// The day of Health Connect of the Santé › Suivi journey, pushed over HTTP (MAG-746).
//
// The phone's own sync needs Health Connect, which the e2e flavor does not have: this posts the
// batch it would send through `POST /api/health/sync` — TODAY with 8 000 steps and 400 active
// kcal — and completes the profile (sex, birth year, height; the weighing is the one
// `body-measurements-api.js` wrote) so the API has a basal metabolism to stack. TODAY is the
// stack's day, YYYY-MM-DD, never this host's clock.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   EMAIL   the journey's user (`${output.journeyEmail}`)
//   TODAY   the stack's day (`${TODAY}`)

(function () {
const base = E2E_BASE_URL

function fail(message) {
  throw new Error('[health-energy-api] ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: EMAIL }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + EMAIL + ': ' + login.body)
}
const headers = {
  'Content-Type': 'application/json',
  Accept: 'application/json',
  Authorization: 'Bearer ' + JSON.parse(login.body).token,
}

const profile = http.request(base + '/api/health/profile', {
  method: 'PATCH',
  headers: headers,
  body: JSON.stringify({ sex: 'male', birthYear: 1986, heightCm: 180 }),
})
if (!profile.ok) {
  fail('PATCH /api/health/profile answered ' + profile.status + ': ' + profile.body)
}

const sync = http.post(base + '/api/health/sync', {
  headers: headers,
  body: JSON.stringify({ workouts: [], measurements: [], days: [{ date: TODAY, steps: 8000, activeKcal: 400 }] }),
})
if (!sync.ok) {
  fail('POST /api/health/sync answered ' + sync.status + ': ' + sync.body)
}
})()
