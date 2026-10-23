// The data side of the scenarios journey, played over HTTP (MAG-551).
//
// No scenario is current until the owner picks one. The journey makes « Rapide » current on the phone;
// this script puts the seed back before and after (no current scenario: otherwise the home card of the
// other journeys would follow it), and reads what the API stored in between.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  reset | expect-current
//   NAME    expect-current only: the name of the scenario that must be the current one

(function () {
const base = E2E_BASE_URL
const email = 'e2e@maggie.local'

function fail(message) {
  throw new Error('[scenarios-api] ' + ACTION + ': ' + message)
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ': ' + login.body)
}
const token = JSON.parse(login.body).token
const authorized = function (extra) {
  return Object.assign({ Authorization: 'Bearer ' + token }, extra || {})
}

function scenarios() {
  const response = http.get(base + '/api/finance/scenarios', { headers: authorized({ Accept: 'application/json' }) })
  if (!response.ok) {
    fail('GET /api/finance/scenarios answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).scenarios
}

if (ACTION === 'reset') {
  const cleared = http.request(base + '/api/finance/current-scenario', {
    method: 'DELETE',
    headers: authorized({ Accept: 'application/json' }),
  })
  if (!cleared.ok) {
    fail('DELETE /api/finance/current-scenario answered ' + cleared.status + ': ' + cleared.body)
  }
} else if (ACTION === 'expect-current') {
  // Polled: the phone's request may still be on its way when the flow gets here.
  let last = []
  for (let attempt = 0; attempt < 20; attempt++) {
    last = scenarios()
    const current = last.filter(function (s) { return s.current })
    if (current.length === 1 && current[0].name === NAME) return
    pause(500)
  }
  fail('« ' + NAME + ' » is not the only current scenario — the API holds: ' + last.map(function (s) { return s.name + (s.current ? ' (courant)' : '') }).join(', '))
} else {
  fail('unknown ACTION')
}
})()
