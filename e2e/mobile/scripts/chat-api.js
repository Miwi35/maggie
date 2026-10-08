// A long conversation of the signed-in account, played over HTTP (MAG-348).
//
// The journey "the chat opens on the last message" needs a history longer than the
// screen, and the app cannot be made to write twenty messages in a few seconds. This
// sends `COUNT` questions through `POST /agent/chat`, the route the app's own history
// is read from: each one stores the question and the scripted answer
// (`30-upcoming-events.yaml`, matched on « prévu »), so the conversation is 2 × COUNT
// messages, numbered in the questions so a flow can tell the first from the last.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   COUNT   how many questions to send
//   EMAIL   the account, default the one the app signs in as (e2e@maggie.local)

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'

function fail(message) {
  throw new Error('[chat-api] ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + email + ': ' + login.body)
}
const token = JSON.parse(login.body).token

for (let n = 1; n <= Number(COUNT); n++) {
  const sent = http.post(base + '/agent/chat', {
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: JSON.stringify({ message: 'Échange ' + n + ' MAG-348 : qu\'est-ce que j\'ai de prévu ?' }),
  })
  if (!sent.ok) {
    fail('question ' + n + ' answered ' + sent.status + ': ' + sent.body)
  }
}
})()
