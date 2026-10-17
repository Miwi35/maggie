// The data side of the « Reconnues à contrôler » journey, played over HTTP (MAG-460).
//
// The journey checks on the phone; this script sets the table before: a category « Courses MAG-460 »
// with the rule « contient COURSES MAG460 » that files under it, and three spends dated today on
// that pattern. The rule is written first, so it recognises the lines as they come in (and
// `apply-categorization-rules` makes sure of it); they wait in the group « COURSES MAG460 · 3 ».
// Same login as `to-sort-api.js`, as the seed's second user, who holds no finance data of its own.
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | check | remove
//
// `check` reads the server back: the three lines are judged « À conserver », and nothing of the
// journey is left in « Reconnues à contrôler » (a check is kept by the API, the phone's list is not proof).
//
// Everything is the journey's own, named after the ticket, and gone after `remove`.

(function () {
const base = E2E_BASE_URL
const email = 'e2e-other@maggie.local'
const accountName = 'Compte MAG-460'
const categoryName = 'Courses MAG-460'
const pattern = 'COURSES MAG460'

function fail(message) {
  throw new Error('[to-control-api] ' + ACTION + ': ' + message)
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

function remove(iri) {
  http.request(base + iri, { method: 'DELETE', headers: authorized() })
}

// This month's lines to check, grouped by operation (the API reads the month from TODAY's first 7 characters).
function recognised() {
  const response = http.get(base + '/api/finance/review/recognised?month=' + TODAY.substring(0, 7), {
    headers: authorized({ Accept: 'application/json' }),
  })
  if (!response.ok) {
    fail('GET /review/recognised answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).groups
}

// Rules first, then the account with its lines, then the category.
function removeLeftovers() {
  collection('/api/categorization_rules?itemsPerPage=200')
    .filter(function (rule) { return (rule.labelPattern || '').indexOf(pattern) === 0 })
    .forEach(function (rule) { remove(rule['@id']) })
  collection('/api/accounts?itemsPerPage=200')
    .filter(function (account) { return account.name === accountName })
    .forEach(function (account) { remove(account['@id']) })
  collection('/api/categories?itemsPerPage=200')
    .filter(function (category) { return category.name === categoryName })
    .forEach(function (category) { remove(category['@id']) })
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const category = post('/api/categories', { name: categoryName })
  post('/api/categorization_rules', {
    category: category['@id'],
    labelPattern: pattern,
    matchType: 'contains',
    priority: 9,
    isActive: true,
  })
  const account = post('/api/accounts', { name: accountName, type: 'checking', currency: 'EUR', balanceCents: 100000 })
  ;['111', '222', '333'].forEach(function (suffix) {
    post('/api/transactions', {
      account: account['@id'],
      label: pattern + ' ' + suffix,
      amountCents: -1800,
      currency: 'EUR',
      bookedAt: TODAY,
      status: 'spent',
    })
  })
  const applied = http.post(base + '/api/finance/apply-categorization-rules', {
    headers: authorized({ 'Content-Type': 'application/json' }),
    body: '{}',
  })
  if (!applied.ok) {
    fail('apply-categorization-rules answered ' + applied.status + ': ' + applied.body)
  }
  const group = recognised().filter(function (g) { return g.label === pattern })[0]
  if (!group || group.count !== 3) {
    fail('expected the rule to have recognised 3 lines, got ' + (group ? group.count : 'no group'))
  }
} else if (ACTION === 'check') {
  const lines = collection('/api/transactions?itemsPerPage=200').filter(function (t) {
    return (t.label || '').indexOf(pattern) === 0
  })
  if (lines.length !== 3) {
    fail('expected 3 lines, found ' + lines.length)
  }
  lines.forEach(function (line) {
    if (line.retrospect !== 'keep') {
      fail(line.label + ': expected « À conserver », got ' + line.retrospect)
    }
  })
  if (recognised().some(function (g) { return g.label === pattern })) {
    fail('the checked group came back in the recognised lines')
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
