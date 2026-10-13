// The data side of the treasury journey, played over HTTP (MAG-280).
//
// The journey reads on the phone; this script only sets the table before: a salary
// expected today (so last month's is the whole delay), a Courant of 200 € with a rent
// of 900 € planned three days ahead (an overdraft the salary does not come to cover)
// and a Livret of 5 000 € to advance from. Same login and helpers as `recurring-api.js`.
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | remove
//
// Signs in as the treasury account, whose salary day and « Salaire » series no other
// journey reads. Everything it adds lives on two accounts of its own, so `remove` is two
// deletes and the day forgotten. Dates come from TODAY, the day the stack runs at.

(function () {
const base = E2E_BASE_URL
const email = 'e2e-treasury@maggie.local'
const checkingName = 'Courant MAG-280'
const savingsName = 'Livret MAG-280'

function fail(message) {
  throw new Error('[treasury-api] ' + ACTION + ': ' + message)
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

function patchSettings(body) {
  const response = http.request(base + '/api/finance/treasury-settings', {
    method: 'PATCH',
    headers: authorized({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    fail('PATCH treasury-settings answered ' + response.status + ': ' + response.body)
  }
}

function removeLeftovers() {
  collection('/api/accounts?itemsPerPage=200')
    .filter(function (row) { return row.name === checkingName || row.name === savingsName })
    .forEach(function (row) { http.request(base + row['@id'], { method: 'DELETE', headers: authorized() }) })
  patchSettings({ salaryExpectedDay: null, overdraftLimitCents: 0 })
}

// The standard categories are indexed a moment after they are installed.
function salaryCategory() {
  const installed = http.post(base + '/api/finance/categories/standard', {
    headers: authorized({ 'Content-Type': 'application/json', Accept: 'application/json' }),
    body: '{}',
  })
  if (!installed.ok) {
    fail('installing the standard categories answered ' + installed.status + ': ' + installed.body)
  }
  const deadline = Date.now() + 30000
  for (;;) {
    const found = collection('/api/categories?itemsPerPage=200').filter(function (row) { return row.standardKey === 'salary' })
    if (found.length > 0) {
      return found[0]
    }
    if (Date.now() >= deadline) {
      fail('the standard « Salaire » category was never indexed within 30 s')
    }
    pause(300)
  }
}

// TODAY plus whole days, on the calendar alone.
function shiftDay(days) {
  const date = new Date(TODAY + 'T00:00:00Z')
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().substring(0, 10)
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const salary = salaryCategory()
  const checking = post('/api/accounts', { name: checkingName, type: 'checking', currency: 'EUR', balanceCents: 20000 })
  post('/api/accounts', { name: savingsName, type: 'savings', currency: 'EUR', balanceCents: 500000 })
  post('/api/recurring_operations', {
    label: 'Salaire ACME MAG-280',
    counterpartyName: 'ACME MAG-280',
    account: checking['@id'],
    category: salary['@id'],
    period: 'monthly',
    anchorOn: '2025-01-25',
    referenceAmountCents: 495000,
    referenceSource: 'declared',
  })
  patchSettings({ salaryExpectedDay: Number(TODAY.substring(8, 10)), overdraftLimitCents: 0 })
  post('/api/transactions', {
    account: checking['@id'],
    label: 'Loyer MAG-280',
    amountCents: -90000,
    currency: 'EUR',
    bookedAt: shiftDay(3),
    status: 'planned',
  })
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
