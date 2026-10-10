// Makes Maggie raise a notification on her own, while the app is open (MAG-314).
//
// A notification cannot be created through the REST API, only by Maggie herself, so the
// message goes through `POST /agent/chat` like `chat-api.js`; the scenario
// `94-interruption-finance.yaml` answers by calling `manage_notifications`, and the API
// publishes the notification on Mercure. Called by a flow whose app is already open and
// signed in: the interruption must arrive from the live feed, not from a relaunch.
//
// Parameters (plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   EMAIL   the account, default the one the app signs in as (e2e@maggie.local)

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'

function fail(message) {
  throw new Error('[interruption-api] ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + email + ': ' + login.body)
}
const token = JSON.parse(login.body).token

const sent = http.post(base + '/agent/chat', {
  headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
  body: JSON.stringify({ message: 'MAG-314 interruption : préviens-moi pour mes comptes.' }),
})
if (!sent.ok) {
  fail('the message answered ' + sent.status + ': ' + sent.body)
}
})()
