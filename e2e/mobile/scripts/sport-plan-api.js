// The session of the day of the Santé › Sport activity journey, written over HTTP (MAG-779).
//
// The phone has no form for a session's exercises, so this writes one planned strength session
// for the journey's own user through `POST /api/workouts`: « Séance A », planned TODAY at 18:00 UTC
// (the stack's day, never this host's clock), with its six exercises, the first one at step 4.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   EMAIL   the journey's user (`${output.journeyEmail}`)
//   TODAY   the stack's day, YYYY-MM-DD (`${TODAY}`)
//   MINI    optional, any value: also writes « Séance B » (one push-up set, planned at 19:00 so the card keeps
//           « Séance A ») and sets `output.miniId` for the deep link that launches it (MAG-781)

(function () {
const base = E2E_BASE_URL

function fail(message) {
  throw new Error('[sport-plan-api] ' + message)
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
  startedAt: new Date(today).toISOString(),
  durationMinutes: 45,
  source: 'planned',
  variant: 'normal',
  exercises: [
    exercise('pull-up-negative', 4, 3, 5, 3, 5, 120),
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

if (typeof MINI !== 'undefined' && MINI) {
  const mini = {
    type: 'strength',
    title: 'Séance B',
    startedAt: new Date(today + 3600 * 1000).toISOString(),
    durationMinutes: 10,
    source: 'planned',
    variant: 'normal',
    exercises: [exercise('push-up', 4, 1, 8, 6, 10, 60)],
  }
  const second = http.post(base + '/api/workouts', {
    headers: { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json', Authorization: 'Bearer ' + token },
    body: JSON.stringify(mini),
  })
  if (!second.ok) {
    fail('POST /api/workouts (mini) answered ' + second.status + ': ' + second.body)
  }
  output.miniId = JSON.parse(second.body).id
}
})()
