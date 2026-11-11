// The user's daily rendezvous settings, over HTTP (MAG-787).
//
// `reset` puts the morning digest and the evening review back to the API's defaults — both on,
// 07:00 and 21:30 — so the flow starts from the same cards whatever ran before it.
// `expect` reads `GET /api/user_preferences/me` — what the agent's planner reads — which is how
// the flow proves the phone's change reached the server.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION          reset | expect
//   DIGEST_ENABLED  expect: the expected `morningDigestEnabled`, true or false
//   REVIEW_TIME     expect: the expected `eveningReviewTime`, e.g. 22:15
//   EMAIL           the account, default the one the app signs in as (e2e@maggie.local)

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'

function fail(message) {
  throw new Error('[rendezvous-api] ' + ACTION + ': ' + message)
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
  const write = http.request(base + '/api/user_preferences/me', {
    method: 'PATCH',
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/json', 'Content-Type': 'application/merge-patch+json' },
    body: JSON.stringify({
      morningDigestEnabled: true,
      morningDigestTime: '07:00',
      eveningReviewEnabled: true,
      eveningReviewTime: '21:30',
    }),
  })
  if (!write.ok) {
    fail('PATCH /api/user_preferences/me answered ' + write.status + ': ' + write.body)
  }
} else if (ACTION === 'expect') {
  const read = http.get(base + '/api/user_preferences/me', {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/json' },
  })
  if (!read.ok) {
    fail('GET /api/user_preferences/me answered ' + read.status + ': ' + read.body)
  }
  const preference = JSON.parse(read.body)
  if (String(preference.morningDigestEnabled) !== String(DIGEST_ENABLED)) {
    fail('morningDigestEnabled is ' + preference.morningDigestEnabled + ', expected ' + DIGEST_ENABLED)
  }
  if (preference.eveningReviewTime !== REVIEW_TIME) {
    fail('eveningReviewTime is "' + preference.eveningReviewTime + '", expected "' + REVIEW_TIME + '"')
  }
} else {
  fail('unknown action')
}
})()
