// The data side of the « À qualifier » journey, played over HTTP (MAG-423, MAG-458).
//
// The journey reads the pile on the phone; this script sets the table before: a category
// « Courses MAG-423 » with the rule « contient CARREFOUR » that files under it, and two
// Carrefour lines without a category (the rule is written after them and does not apply to
// existing lines, so the pile holds both and each carries the rule as its suggestion).
// Same login as `finance-budget-api.js`, as the seed's second user, who holds no finance
// data of its own: the pile is exactly these two lines.
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | add-leisure | check-leisure | remove
//
// `add-leisure` then puts one more expense on the same account, without a rule to recognise it, and the
// category « Loisirs MAG-458 » to file it under; `check-leisure` reads it back: judged « Superflue » and
// filed under that category.
//
// Everything is the journey's own, named after the ticket, and gone after `remove`.

(function () {
const base = E2E_BASE_URL
const email = 'e2e-other@maggie.local'
const accountName = 'Compte MAG-423'
const categoryName = 'Courses MAG-423'
const leisureName = 'Loisirs MAG-458'
const leisureLabel = 'DUPONT MARTIN'

function fail(message) {
  throw new Error('[to-sort-api] ' + ACTION + ': ' + message)
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

// Rules first (the write of « Toujours » may have reused or added one), then the account
// with its lines, then the category.
function removeLeftovers() {
  collection('/api/categorization_rules?itemsPerPage=200')
    .filter(function (rule) { return (rule.labelPattern || '').indexOf('CARREFOUR') === 0 })
    .forEach(function (rule) { remove(rule['@id']) })
  collection('/api/accounts?itemsPerPage=200')
    .filter(function (account) { return account.name === accountName })
    .forEach(function (account) { remove(account['@id']) })
  collection('/api/categories?itemsPerPage=200')
    .filter(function (category) { return category.name === categoryName || category.name === leisureName })
    .forEach(function (category) { remove(category['@id']) })
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const account = post('/api/accounts', { name: accountName, type: 'checking', currency: 'EUR', balanceCents: 100000 })
  ;['CARREFOUR MARKET 1234', 'CARREFOUR MARKET 5678'].forEach(function (label) {
    post('/api/transactions', {
      account: account['@id'],
      label: label,
      amountCents: -4250,
      currency: 'EUR',
      bookedAt: TODAY,
      status: 'spent',
    })
  })
  const category = post('/api/categories', { name: categoryName })
  post('/api/categorization_rules', {
    category: category['@id'],
    labelPattern: 'CARREFOUR',
    matchType: 'contains',
    priority: 9,
    isActive: true,
  })
} else if (ACTION === 'add-leisure') {
  const account = collection('/api/accounts?itemsPerPage=200').filter(function (a) { return a.name === accountName })[0]
  if (!account) {
    fail('the account of the journey is missing')
  }
  post('/api/categories', { name: leisureName })
  post('/api/transactions', {
    account: account['@id'],
    label: leisureLabel,
    amountCents: -1200,
    currency: 'EUR',
    bookedAt: TODAY,
    status: 'spent',
  })
} else if (ACTION === 'check-leisure') {
  const category = collection('/api/categories?itemsPerPage=200').filter(function (c) { return c.name === leisureName })[0]
  const line = collection('/api/transactions?itemsPerPage=200').filter(function (t) { return t.label === leisureLabel })[0]
  if (!category || !line) {
    fail('the expense or its category is missing')
  }
  if (line.retrospect !== 'avoidable') {
    fail('expected the expense judged avoidable, got ' + line.retrospect)
  }
  if (line.category !== category['@id']) {
    fail('expected the expense filed under ' + leisureName + ', got ' + line.category)
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
