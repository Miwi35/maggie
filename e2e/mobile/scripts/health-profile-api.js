// The health profile of the Santé › Configuration journey, written over HTTP (MAG-707).
//
// The ticket's figures for the journey's own user, through `PATCH /api/health/profile`: Homme,
// 38 ans, 178 cm, 72 kg, Sédentaire — basal metabolism 1 648 kcal, need 1 980 kcal. The age is a
// birth year counted from TODAY, the stack's day — never this host's clock, which is not pinned.
// The weight records a manual weighing, as the phone's stepper does.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   EMAIL   the journey's user (`${output.journeyEmail}`)
//   TODAY   the stack's day, YYYY-MM-DD (`${TODAY}`)

(function () {
const base = E2E_BASE_URL

function fail(message) {
  throw new Error('[health-profile-api] ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: EMAIL }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + EMAIL + ': ' + login.body)
}
const token = JSON.parse(login.body).token
const year = parseInt(String(TODAY).substring(0, 4), 10)
if (isNaN(year)) {
  fail('TODAY is not a day: ' + TODAY)
}

const profile = { sex: 'male', birthYear: year - 38, heightCm: 178, weightKg: 72, activityLevel: 'sedentary' }
const written = http.request(base + '/api/health/profile', {
  method: 'PATCH',
  headers: { 'Content-Type': 'application/json', Accept: 'application/json', Authorization: 'Bearer ' + token },
  body: JSON.stringify(profile),
})
if (!written.ok) {
  fail('PATCH /api/health/profile ' + JSON.stringify(profile) + ' answered ' + written.status + ': ' + written.body)
}
const answer = JSON.parse(written.body)
if (answer.bmrKcal !== 1648 || answer.needKcal !== 1980) {
  fail('the profile answered bmr ' + answer.bmrKcal + ', need ' + answer.needKcal + ' instead of 1648 and 1980: ' + written.body)
}
})()
