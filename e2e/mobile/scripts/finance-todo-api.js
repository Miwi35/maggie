// The data side of the « ce qui est à faire » journey, played over HTTP (MAG-421).
//
// The journey reads the pills of the finance home's Comptes tile on the phone; this script sets the
// table before: an account « Compte MAG-421 » with three lines no category claims (dated on three
// consecutive days, so the pile has an oldest) and one rejected direct debit, a debit and the REJET
// credit that gives it back (the API pairs them as the credit lands, as a sync does). The pair is not
// a line to sort: the pile holds three lines, not five.
// Same login as `rejections-api.js`, as the seed's second user, who holds no finance data of its own.
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | remove
//
// `create` leaves once the pairing is findable: the home reads the rejections as it opens, and a pair
// still being recognised would not be counted.
//
// Everything is the journey's own, named after the ticket, and gone after `remove`.

(function () {
const base = E2E_BASE_URL
const email = 'e2e-other@maggie.local'
const accountName = 'Compte MAG-421'
const debitLabel = 'PRELEVEMENT EDF MAG-421'
const creditLabel = 'REJET PRLV EDF MAG-421'

function fail(message) {
  throw new Error('[finance-todo-api] ' + ACTION + ': ' + message)
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

function collection(path) {
  const response = http.get(base + path, { headers: authorized({ Accept: 'application/ld+json' }) })
  if (!response.ok) {
    fail('GET ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).member
}

function post(path, body) {
  const response = http.post(base + path, { headers: authorized(LD), body: JSON.stringify(body) })
  if (!response.ok) {
    fail('POST ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

// The day `back` days before TODAY, YYYY-MM-DD (UTC arithmetic on a calendar date).
function daysBefore(back) {
  const parts = TODAY.split('-').map(function (part) { return parseInt(part, 10) })
  return new Date(Date.UTC(parts[0], parts[1] - 1, parts[2] - back)).toISOString().substring(0, 10)
}

// Deleting the account takes its lines with it.
function removeLeftovers() {
  collection('/api/accounts?itemsPerPage=200')
    .filter(function (account) { return account.name === accountName })
    .forEach(function (account) { http.request(base + account['@id'], { method: 'DELETE', headers: authorized() }) })
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const account = post('/api/accounts', { name: accountName, type: 'checking', currency: 'EUR', balanceCents: 100000 })
  ;[
    { label: 'A TRIER MAG-421 A', cents: -1200, day: daysBefore(2) },
    { label: 'A TRIER MAG-421 B', cents: -1300, day: daysBefore(1) },
    { label: 'A TRIER MAG-421 C', cents: -1400, day: daysBefore(0) },
    { label: debitLabel, cents: -20600, day: TODAY },
    { label: creditLabel, cents: 20600, day: TODAY },
  ].forEach(function (line) {
    post('/api/transactions', {
      account: account['@id'],
      label: line.label,
      amountCents: line.cents,
      currency: 'EUR',
      bookedAt: line.day,
      status: 'spent',
    })
  })
  const deadline = Date.now() + 30000
  for (;;) {
    const paired = collection('/api/transactions?transferKind=rejected&itemsPerPage=200')
      .filter(function (line) { return line.label === debitLabel || line.label === creditLabel })
    if (paired.length === 2) {
      break
    }
    if (Date.now() >= deadline) {
      fail('the rejection was never recognised within 30 s (found ' + paired.length + ' of its 2 lines)')
    }
    pause(300)
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
