// The data side of the contacts journey, played over HTTP (MAG-450).
//
// The journey links a transfer to a contact on the phone; this script sets the table before —
// an account and one transfer « VIR. DE TOM MARTIN » of +400 € without category nor contact,
// so it waits in « À trier » and asks « Associer à un contact ? » — then writes, once Tom
// exists, a second transfer with the same label, which the API must attach to Tom by itself.
// Same login as `to-sort-api.js`, as the seed's second user, who holds no finance data of its
// own: the pile is this one line.
//
// MAG-451 adds the shared spend: `share` writes, on the same account, a gîte of 800 € filed in
// « Voyages » whose monthly envelope is 300 €, and the contacts Julie and Inès beside Tom, so the
// phone declares its part and the people who owe the rest. Collections are served from
// Elasticsearch, which trails a write: what the phone reads next is polled for until findable.
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | second | share | remove
//
// Everything is the journey's own — the account named after the ticket, the contacts « Tom »,
// « Julie » and « Inès », the category « Voyages » and its envelope — and gone after `remove`.

(function () {
const base = E2E_BASE_URL
const email = 'e2e-other@maggie.local'
const accountName = 'Compte MAG-450'
const contactName = 'Tom'
const label = 'VIR. DE TOM MARTIN'
// MAG-451: the people of the shared gîte, and where it is filed.
const sharingNames = ['Julie', 'Inès']
const categoryName = 'Voyages'
const giteLabel = 'GITE DES CIMES MAG-451'

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

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

// Polled until exactly one row of that name is findable.
function awaitOne(path, name) {
  const deadline = Date.now() + 30000
  for (;;) {
    const rows = collection(path).filter(function (row) { return row.name === name })
    if (rows.length === 1) {
      return rows[0]
    }
    if (Date.now() >= deadline) {
      fail('"' + name + '" never became findable within 30 s (found ' + rows.length + ')')
    }
    pause(300)
  }
}

// The shares first (a contact owed a share cannot be deleted), then the contacts (their labels
// go with them), the account with its lines, and « Voyages » with its envelope.
function removeLeftovers() {
  const people = [contactName].concat(sharingNames)
  collection('/api/peer_debts')
    .filter(function (debt) {
      return debt.originTransactionId && debt.shares.some(function (share) { return people.indexOf(share.contactName) >= 0 })
    })
    .forEach(function (debt) {
      http.request(base + '/api/finance/transactions/' + debt.originTransactionId + '/my-share', { method: 'DELETE', headers: authorized() })
    })
  collection('/api/contacts?pagination=false')
    .filter(function (contact) { return people.indexOf(contact.name) >= 0 })
    .forEach(function (contact) { remove(contact['@id']) })
  collection('/api/accounts?itemsPerPage=200')
    .filter(function (a) { return a.name === accountName })
    .forEach(function (a) { remove(a['@id']) })
  collection('/api/categories?itemsPerPage=200')
    .filter(function (category) { return category.name === categoryName })
    .forEach(function (category) {
      collection('/api/envelopes?itemsPerPage=200')
        .filter(function (envelope) { return envelope.category === '/api/categories/' + category.id })
        .forEach(function (envelope) { remove(envelope['@id']) })
      remove(category['@id'])
    })
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
} else if (ACTION === 'share') {
  const existing = account()
  if (!existing) {
    fail('no account « ' + accountName + ' »: run create first')
  }
  const voyages = post('/api/categories', { name: categoryName })
  post('/api/envelopes', {
    category: '/api/categories/' + voyages.id,
    mode: 'monthly',
    amountCents: 30000,
    currency: 'EUR',
    year: parseInt(TODAY.substring(0, 4), 10),
    month: parseInt(TODAY.substring(5, 7), 10),
  })
  post('/api/transactions', {
    account: existing['@id'],
    category: '/api/categories/' + voyages.id,
    label: giteLabel,
    amountCents: -80000,
    currency: 'EUR',
    bookedAt: TODAY,
    status: 'spent',
  })
  sharingNames.forEach(function (name) { post('/api/contacts', { name: name }) })
  // The picker and the budget read what the next steps need: leave only once it is findable.
  sharingNames.concat([contactName]).forEach(function (name) { awaitOne('/api/contacts?pagination=false', name) })
  awaitOne('/api/categories?itemsPerPage=200', categoryName)
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
