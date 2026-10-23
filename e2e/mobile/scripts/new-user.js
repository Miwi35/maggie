// A user of its own for the journey, created over HTTP (MAG-577).
//
// `POST /api/auth/e2e/users` makes a `journey-<ulid>@e2e.test` user with the data of a profile and
// indexes it before answering, so nothing the journey reads or writes is shared with another flow.
// The flow then launches the app with `e2e_email: ${output.journeyEmail}` and signs in as that user.
//
// Parameters (`PROFILE` from the flow's top-level `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   PROFILE  empty (default) | standard (what the seed gives the journeys' user) | stock (the seed's stock account)
//
// Sets output.journeyEmail and output.journeyUserId.

(function () {
const profile = typeof PROFILE !== 'undefined' && PROFILE ? PROFILE : 'empty'

const response = http.post(E2E_BASE_URL + '/api/auth/e2e/users', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ profile: profile }),
})
if (!response.ok) {
  throw new Error('[new-user] profile "' + profile + '": POST /api/auth/e2e/users answered ' + response.status + ': ' + response.body)
}
const body = JSON.parse(response.body)
if (!body.user || !body.user.email) {
  throw new Error('[new-user] the answer carries no user email: ' + response.body)
}

output.journeyEmail = body.user.email
output.journeyUserId = body.user.id
})()
