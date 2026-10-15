// Runs a bank sync for the envelope alert account (MAG-49).
//
// Enable Banking is WireMock here: the account `e2e-account-envelope-alert-mobile` answers
// with one 40 € cinema ticket booked today, which takes the seeded « Loisirs » from 70 % to
// 83 % (api/fixtures/e2e/55-envelope-alert.yaml). The API then raises the alert, and the
// open app receives it from the live feed.
//
// Parameters (plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   IMPORTED  how many movements the sync must store: 1 the first time, 0 after

(function () {
const base = E2E_BASE_URL
const email = 'e2e-envelope-alert-mobile@maggie.local'
const expected = typeof IMPORTED !== 'undefined' && IMPORTED !== '' ? Number(IMPORTED) : null

function fail(message) {
  throw new Error('[envelope-alert-sync] ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + email + ': ' + login.body)
}
const token = JSON.parse(login.body).token

const synced = http.post(base + '/api/finance/bank-connections/sync', {
  headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
  body: '{}',
})
if (!synced.ok) {
  fail('the sync answered ' + synced.status + ': ' + synced.body)
}
const imported = JSON.parse(synced.body).imported
if (expected !== null && imported !== expected) {
  fail('the sync stored ' + imported + ' movement(s), the journey expects ' + expected)
}
})()
