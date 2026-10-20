// The user's task settings, over HTTP (MAG-407).
//
// `reset` puts the critical tasks' days before the due day back to the seed's [3, 1], and the
// reminders back to the API's defaults — 6 a day, quiet from 21:00 to 08:00 (MAG-510) — so the
// flow starts from the same chips and numbers whatever ran before it.
// `expect` reads `GET /api/task-settings/me` — what the app, the admin and the alerts read —
// which is how the flow proves the phone's change reached the server.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION         reset | expect
//   CRITICAL_DAYS  expect: the expected `alertSchedule.critical.daysBefore`, comma-separated, e.g. 1
//   MORNING        expect, optional: the expected `morningTime`, e.g. 07:30
//   CAP            expect, optional: the expected `reminderDailyCap`, e.g. 4
//   QUIET_START    expect, optional: the expected `quietStart`, e.g. 22:00
//   QUIET_END      expect, optional: the expected `quietEnd`, e.g. 07:00
//   EMAIL          the account, default the one the app signs in as (e2e@maggie.local)

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'

function fail(message) {
  throw new Error('[task-settings-api] ' + ACTION + ': ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + email + ': ' + login.body)
}
const token = JSON.parse(login.body).token

if (ACTION === 'reset') {
  const write = http.request(base + '/api/task-settings/me', {
    method: 'PUT',
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({
      alertSchedule: { critical: { daysBefore: [3, 1] } },
      reminderDailyCap: 6,
      quietStart: '21:00',
      quietEnd: '08:00',
    }),
  })
  if (!write.ok) {
    fail('PUT /api/task-settings/me answered ' + write.status + ': ' + write.body)
  }
} else if (ACTION === 'expect') {
  const read = http.get(base + '/api/task-settings/me', {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/json' },
  })
  if (!read.ok) {
    fail('GET /api/task-settings/me answered ' + read.status + ': ' + read.body)
  }
  const settings = JSON.parse(read.body)
  if (typeof MORNING !== 'undefined' && MORNING && settings.morningTime !== MORNING) {
    fail('morningTime is "' + settings.morningTime + '", expected "' + MORNING + '"')
  }
  if (typeof CAP !== 'undefined' && CAP && settings.reminderDailyCap !== Number(CAP)) {
    fail('reminderDailyCap is ' + settings.reminderDailyCap + ', expected ' + CAP)
  }
  if (typeof QUIET_START !== 'undefined' && QUIET_START && settings.quietStart !== QUIET_START) {
    fail('quietStart is "' + settings.quietStart + '", expected "' + QUIET_START + '"')
  }
  if (typeof QUIET_END !== 'undefined' && QUIET_END && settings.quietEnd !== QUIET_END) {
    fail('quietEnd is "' + settings.quietEnd + '", expected "' + QUIET_END + '"')
  }
  const days = settings.alertSchedule.critical.daysBefore.join(',')
  const expected = String(CRITICAL_DAYS).split(',').map(function (d) {
    return d.trim()
  }).join(',')
  if (days !== expected) {
    fail('alertSchedule.critical.daysBefore is [' + days + '], expected [' + expected + ']')
  }
} else {
  fail('unknown action')
}
})()
