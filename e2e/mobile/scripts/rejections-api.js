// The data side of the « Régularisé » journey, played over HTTP (MAG-424).
//
// The journey settles a rejection on the phone; this script sets the table before: an
// account « Compte MAG-424 » with two rejected direct debits, each a debit and the REJET
// credit that gives it back (the API pairs them as the credit lands, as a sync does).
// Same login as `to-sort-api.js`, as the seed's second user.
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | remove
//
// Everything is the journey's own, named after the ticket, and gone after `remove`.

(function () {
const base = E2E_BASE_URL
const email = 'e2e-other@maggie.local'
const accountName = 'Compte MAG-424'

function fail(message) {
  throw new Error('[rejections-api] ' + ACTION + ': ' + message)
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
  ;[
    { debit: 'PRELEVEMENT EDF MAG-424', credit: 'REJET PRLV EDF MAG-424', cents: 20600 },
    { debit: 'PRELEVEMENT ORANGE MAG-424', credit: 'REJET PRLV ORANGE MAG-424', cents: 3999 },
  ].forEach(function (rejection) {
    ;[[rejection.debit, -rejection.cents], [rejection.credit, rejection.cents]].forEach(function (line) {
      post('/api/transactions', {
        account: account['@id'],
        label: line[0],
        amountCents: line[1],
        currency: 'EUR',
        bookedAt: TODAY,
        status: 'spent',
      })
    })
  })
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
