// The data side of the 20-year frise journey, played over HTTP (MAG-554).
//
// The phone draws the « Indépendance » event of the followed scenario on the month of its date N. This
// script reads that date from the API (`GET /api/finance/timeline`, the same request the phone sends)
// and hands the journey the month as the app words it, so the flow asserts what the server computed
// rather than a date written in the flow: the seed's figures may move, the flow must not.
//
// It first makes a scenario saving 10 000 € a month the followed one, then fails with a plain
// sentence if even that reaches no independence within 20 years (rentes against the train de vie,
// see api/fixtures/e2e/50-finance.yaml).
//
// Output: `output.nLabel` (« décembre 2045 », as `independenceMonthLabel` writes it) and `output.nMonth`.
// Parameters: E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`.

(function () {
const base = E2E_BASE_URL
const MONTHS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre']

function fail(message) {
  throw new Error('[timeline-api] ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: 'e2e@maggie.local' }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ': ' + login.body)
}
const token = JSON.parse(login.body).token

// The seed alone does not promise a date N within 20 years: the journey follows a scenario of its
// own, saving 10 000 € a month, so N is always within reach. The seed is rebuilt by the next run.
const JSON_HEADERS = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json', Accept: 'application/json' }
const created = http.post(base + '/api/finance/scenarios', {
  headers: JSON_HEADERS,
  body: JSON.stringify({ name: 'Frise e2e', monthlySaving: 1000000 }),
})
if (!created.ok) {
  fail('POST /api/finance/scenarios answered ' + created.status + ': ' + created.body)
}
const scenarioId = JSON.parse(created.body).scenario.id
const followed = http.post(base + '/api/finance/scenarios/' + scenarioId + '/make-current', { headers: JSON_HEADERS })
if (!followed.ok) {
  fail('make-current answered ' + followed.status + ': ' + followed.body)
}

const response = http.get(base + '/api/finance/timeline', {
  headers: { Authorization: 'Bearer ' + token, Accept: 'application/json' },
})
if (!response.ok) {
  fail('GET /api/finance/timeline answered ' + response.status + ': ' + response.body)
}
const current = JSON.parse(response.body).current
const reached = current.events.find(function (event) { return event.type === 'independence_reached' })
if (!current.nDate || !reached) {
  fail('the followed scenario « ' + current.name + ' » reaches no independence within 20 years although it saves 10 000 € a month')
}
if (reached.month !== current.nDate) {
  fail('the independence event is on ' + reached.month + ', not on the date N ' + current.nDate)
}

const parts = current.nDate.split('-')
output.nMonth = current.nDate
output.nLabel = MONTHS[parseInt(parts[1], 10) - 1] + ' ' + parts[0]
})()
