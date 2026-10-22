// The data side of the « more than a page of transactions » journey, played over HTTP (MAG-436).
//
// An account « Compte MAG-436 » holding 45 lines, one a day backwards from today, the number in the
// label rising as the line gets older: « LIGNE MAG-436 45 » is the oldest, so it comes last
// on the list (newest first) and on the API's second page. Same login as `rejections-api.js`,
// the seed's second user.
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | remove
//
// Everything is the journey's own, named after the ticket, and gone after `remove`.

(function () {
const base = E2E_BASE_URL
const email = 'e2e-other@maggie.local'
const accountName = 'Compte MAG-436'
const lines = 45

function fail(message) {
  throw new Error('[transactions-api] ' + ACTION + ': ' + message)
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
const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }

function post(path, body) {
  const response = http.post(base + path, { headers: authorized(LD), body: JSON.stringify(body) })
  if (!response.ok) {
    fail('POST ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

function daysBefore(day, days) {
  const date = new Date(day + 'T12:00:00Z')
  date.setUTCDate(date.getUTCDate() - days)
  return date.toISOString().substring(0, 10)
}

// Deleting the account takes its lines with it.
function removeLeftovers() {
  const response = http.get(base + '/api/accounts?itemsPerPage=200', { headers: authorized({ Accept: 'application/ld+json' }) })
  if (!response.ok) {
    fail('GET /api/accounts answered ' + response.status + ': ' + response.body)
  }
  JSON.parse(response.body).member
    .filter(function (account) { return account.name === accountName })
    .forEach(function (account) { http.request(base + account['@id'], { method: 'DELETE', headers: authorized() }) })
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const account = post('/api/accounts', { name: accountName, type: 'checking', currency: 'EUR', balanceCents: 100000 })
  for (let number = 1; number <= lines; number++) {
    post('/api/transactions', {
      account: account['@id'],
      label: 'LIGNE MAG-436 ' + number,
      amountCents: -1000 - number,
      currency: 'EUR',
      bookedAt: daysBefore(TODAY, number),
      status: 'spent',
    })
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
