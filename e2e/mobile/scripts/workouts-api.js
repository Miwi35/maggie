// « Séance A » of the Santé › Sport journey, written over HTTP (MAG-775).
//
// The phone has no form for a session's exercises — the coach writes them — so this writes one
// strength session for the journey's own user through `POST /api/workouts`: « Séance A », done
// yesterday at 18:00 UTC, with its six exercises as prescribed. Done and not planned, because the
// phone lists the sessions done only (the tablet lists the planned ones too). Yesterday counts from
// TODAY, the stack's day — never this host's clock, which is not pinned.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   EMAIL   the journey's user (`${output.journeyEmail}`)
//   TODAY   the stack's day, YYYY-MM-DD (`${TODAY}`)

(function () {
const base = E2E_BASE_URL

function fail(message) {
  throw new Error('[workouts-api] ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: EMAIL }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + EMAIL + ': ' + login.body)
}
const token = JSON.parse(login.body).token
const today = new Date(TODAY + 'T18:00:00Z').getTime()
if (isNaN(today)) {
  fail('TODAY is not a day: ' + TODAY)
}

function exercise(slug, step, sets, target, targetMin, targetMax, restSeconds, loadKg) {
  const prescribed = { exercise: slug, step: step, sets: sets, target: target, targetMin: targetMin, targetMax: targetMax, restSeconds: restSeconds }
  if (loadKg !== undefined) {
    prescribed.loadKg = loadKg
  }
  return prescribed
}

const session = {
  type: 'strength',
  title: 'Séance A',
  startedAt: new Date(today - 24 * 60 * 60 * 1000).toISOString(),
  durationMinutes: 45,
  source: 'manual',
  variant: 'normal',
  exercises: [
    exercise('pull-up-negative', 5, 3, 5, 3, 5, 120),
    exercise('goblet-squat', 5, 3, 10, 8, 12, 90, 10),
    exercise('push-up', 4, 3, 8, 6, 10, 90),
    exercise('bent-over-bag-row', 4, 3, 10, 8, 12, 90, 12.5),
    exercise('glute-bridge', 1, 3, 12, 10, 15, 60),
    exercise('plank', 2, 3, 30, 20, 45, 60),
  ],
}
const created = http.post(base + '/api/workouts', {
  headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json', Authorization: 'Bearer ' + token },
  body: JSON.stringify(session),
})
if (!created.ok) {
  fail('POST /api/workouts answered ' + created.status + ': ' + created.body)
}
})()
