// The data side of the Revue journey, played over HTTP (MAG-539; it replaces the data of the « À qualifier » and
// « Reconnues à contrôler » journeys, MAG-423, MAG-458, MAG-460).
//
// The journey reads the list on the phone; this script sets the table before: a category « Courses MAG-539 » and
// two Carrefour lines without a category, then the rule « contient CARREFOUR » that files under it (a rule written
// after the lines does not apply to them: both stay to qualify, each carrying the rule as its suggestion), then a
// second rule « contient RECONNUE MAG539 » written before its line, which recognises it as it arrives.
// Same login as `finance-budget-api.js`, as the seed's second user, who holds no finance data of its own:
// the Revue is exactly these three lines, dated today.
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | check | remove
//
// `create` reads the queue back (2 lines to qualify, 1 recognised). `check` reads the server back after the
// journey: one Carrefour line filed under « Courses MAG-539 », the other still to qualify (the server keeps the
// verdict, the phone's list is not proof).
//
// Everything is the journey's own, named after the ticket, and gone after `remove`.

(function () {
const base = E2E_BASE_URL
const email = 'e2e-other@maggie.local'
const accountName = 'Compte MAG-539'
const categoryName = 'Courses MAG-539'
const toQualifyPattern = 'CARREFOUR'
const recognisedPattern = 'RECONNUE MAG539'

function fail(message) {
  throw new Error('[review-list-api] ' + ACTION + ': ' + message)
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

// This month's Revue, every state (the API reads the month from TODAY's first 7 characters).
function queue() {
  const path = '/api/finance/review/queue?period=month&month=' + TODAY.substring(0, 7) + '&state=all&limit=1000'
  const response = http.get(base + path, { headers: authorized({ Accept: 'application/json' }) })
  if (!response.ok) {
    fail('GET ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

// Rules first, then the account with its lines, then the category.
function removeLeftovers() {
  collection('/api/categorization_rules?itemsPerPage=200')
    .filter(function (rule) {
      const pattern = rule.labelPattern || ''
      return pattern.indexOf(toQualifyPattern) === 0 || pattern.indexOf(recognisedPattern) === 0
    })
    .forEach(function (rule) { remove(rule['@id']) })
  collection('/api/accounts?itemsPerPage=200')
    .filter(function (account) { return account.name === accountName })
    .forEach(function (account) { remove(account['@id']) })
  collection('/api/categories?itemsPerPage=200')
    .filter(function (category) { return category.name === categoryName })
    .forEach(function (category) { remove(category['@id']) })
}

function line(account, label, cents) {
  return post('/api/transactions', {
    account: account['@id'],
    label: label,
    amountCents: cents,
    currency: 'EUR',
    bookedAt: TODAY,
    status: 'spent',
  })
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const account = post('/api/accounts', { name: accountName, type: 'checking', currency: 'EUR', balanceCents: 100000 })
  const category = post('/api/categories', { name: categoryName })
  line(account, 'CARREFOUR MARKET 1234', -4250)
  line(account, 'CARREFOUR MARKET 5678', -4250)
  post('/api/categorization_rules', {
    category: category['@id'],
    labelPattern: toQualifyPattern,
    matchType: 'contains',
    priority: 9,
    isActive: true,
  })
  post('/api/categorization_rules', {
    category: category['@id'],
    labelPattern: recognisedPattern,
    matchType: 'contains',
    priority: 9,
    isActive: true,
  })
  line(account, recognisedPattern + ' 111', -1800)
  const read = queue()
  if (read.unrecognisedCount !== 2 || read.recognisedCount !== 1) {
    fail('expected 2 lines to qualify and 1 recognised, got ' + read.unrecognisedCount + ' and ' + read.recognisedCount)
  }
} else if (ACTION === 'check') {
  const category = collection('/api/categories?itemsPerPage=200').filter(function (c) { return c.name === categoryName })[0]
  if (!category) {
    fail('the category of the journey is missing')
  }
  const carrefour = collection('/api/transactions?itemsPerPage=200').filter(function (t) {
    return (t.label || '').indexOf(toQualifyPattern) === 0
  })
  const filed = carrefour.filter(function (t) { return t.category === category['@id'] })
  if (carrefour.length !== 2 || filed.length !== 1) {
    fail('expected one of the 2 Carrefour lines filed under ' + categoryName + ', got ' + filed.length + ' of ' + carrefour.length)
  }
  const read = queue()
  if (read.unrecognisedCount !== 1) {
    fail('expected 1 line still to qualify, got ' + read.unrecognisedCount)
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
