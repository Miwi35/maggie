// The data side of the recurring-operations journey, played over HTTP (MAG-309).
//
// The journey reads and accepts on the phone; this script only sets the table before
// (a rent series anchored two months back, its one line booked eight days late, which
// the pass only proposes) and reads the database after (the line is attached).
// Same login and polling as `meal-api.js`.
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | expect-attached | remove
//
// Everything lives on an account of its own, so `remove` is one delete: the series and
// the lines go with it. Dates come from TODAY, the day the stack runs at.

(function () {
const base = E2E_BASE_URL
const email = 'e2e@maggie.local'
const accountName = 'Courant MAG-309'
const categoryName = 'Loyer MAG-309'
const transactionLabel = 'LOYER SCI VERDA'

function fail(message) {
  throw new Error('[recurring-api] ' + ACTION + ': ' + message)
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
const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const deadline = Date.now() + 30000

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

function removeLeftovers() {
  collection('/api/accounts?itemsPerPage=200')
    .filter(function (row) { return row.name === accountName })
    .forEach(function (row) { http.request(base + row['@id'], { method: 'DELETE', headers: authorized() }) })
  collection('/api/categories?itemsPerPage=200')
    .filter(function (row) { return row.name === categoryName })
    .forEach(function (row) { http.request(base + row['@id'], { method: 'DELETE', headers: authorized() }) })
}

// The 5th of the month two months before TODAY: the first due date of the series.
function firstDue() {
  const year = Number(TODAY.substring(0, 4))
  const month = Number(TODAY.substring(5, 7)) - 1 // 0-based
  const back = month - 2
  const y = back < 0 ? year - 1 : year
  const m = (back + 12) % 12 + 1
  return y + '-' + (m < 10 ? '0' : '') + m + '-05'
}

function transactions() {
  return collection('/api/transactions?itemsPerPage=200').filter(function (row) { return row.label === transactionLabel })
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const anchorOn = firstDue()
  const account = post('/api/accounts', { name: accountName, type: 'checking', currency: 'EUR', balanceCents: 0 })
  const category = post('/api/categories', { name: categoryName, obligation: 'mandatory' })
  post('/api/recurring_operations', {
    label: 'Loyer MAG-309',
    counterpartyName: 'SCI VERDA',
    account: account['@id'],
    category: category['@id'],
    period: 'monthly',
    anchorOn: anchorOn,
    referenceAmountCents: -90000,
    amountTolerancePercent: 20,
    dateToleranceDays: 5,
  })
  // Eight days after the due date, past the tolerance: proposed, never attached on its own.
  const line = post('/api/transactions', {
    account: account['@id'],
    // The payee is read from the label: the line has no writable counterparty.
    label: transactionLabel,
    amountCents: -90000,
    currency: 'EUR',
    bookedAt: anchorOn.substring(0, 8) + '13',
    status: 'spent',
  })
  if (line.recurringOperation) {
    fail('the late line was attached on its own, so there is nothing to accept')
  }
} else if (ACTION === 'expect-attached') {
  for (;;) {
    const lines = transactions()
    if (lines.length === 1 && lines[0].recurringOperation) {
      break
    }
    if (Date.now() >= deadline) {
      fail('the line "' + transactionLabel + '" was never attached within 30 s (found ' + lines.length + ' line(s))')
    }
    pause(300)
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
