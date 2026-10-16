// The data side of the contacts journey, played over HTTP (MAG-450).
//
// The journey links a transfer to a contact on the phone; this script sets the table before —
// an account and one transfer « VIR. DE TOM MARTIN » of +400 € without category nor contact,
// so it waits in « À trier » and asks « Associer à un contact ? » — then writes, once Tom
// exists, a second transfer with the same label, which the API must attach to Tom by itself.
// Same login as `to-sort-api.js`, as the seed's second user, who holds no finance data of its
// own: the pile is this one line.
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | second | remove
//
// Everything is the journey's own — the account named after the ticket, the contact « Tom » —
// and gone after `remove`.

(function () {
const base = E2E_BASE_URL
const email = 'e2e-other@maggie.local'
const accountName = 'Compte MAG-450'
const contactName = 'Tom'
const label = 'VIR. DE TOM MARTIN'

function fail(message) {
  throw new Error('[contacts-api] ' + ACTION + ': ' + message)
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

function account() {
  return collection('/api/accounts?itemsPerPage=200').filter(function (a) { return a.name === accountName })[0]
}

function transfer(accountIri) {
  return post('/api/transactions', {
    account: accountIri,
    label: label,
    amountCents: 40000,
    currency: 'EUR',
    bookedAt: TODAY,
    status: 'spent',
  })
}

// The contact first (its labels go with it), then the account with its lines.
function removeLeftovers() {
  collection('/api/contacts?pagination=false')
    .filter(function (contact) { return contact.name === contactName })
    .forEach(function (contact) { remove(contact['@id']) })
  collection('/api/accounts?itemsPerPage=200')
    .filter(function (a) { return a.name === accountName })
    .forEach(function (a) { remove(a['@id']) })
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const created = post('/api/accounts', { name: accountName, type: 'checking', currency: 'EUR', balanceCents: 100000 })
  const line = transfer(created['@id'])
  if (line.contact) {
    fail('the first transfer is already attached to ' + line.contact + ': a leftover label claims it')
  }
} else if (ACTION === 'second') {
  const existing = account()
  if (!existing) {
    fail('no account « ' + accountName + ' »: run create first')
  }
  const line = transfer(existing['@id'])
  // The API attaches it while writing it: no need to wait for the phone to find out.
  if (!line.contact) {
    fail('the second transfer was not attached to ' + contactName + ': ' + JSON.stringify(line))
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
