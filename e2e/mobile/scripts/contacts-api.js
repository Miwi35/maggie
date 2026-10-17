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
// MAG-452 reads the month's income back: `income` fails unless the dashboard counts exactly
// EXPECTED_CENTS this month, which is how the journey knows the part to give back left it.
//
// MAG-453 allocates a received transfer: `allocation` writes the créance « Gîte de Crozon » (a spend of
// 400 € in « Voyages », my part 200 €, Tom owing the other 200 €) and a third transfer from Tom of 400 €,
// filed and dated the day before so it is the oldest card of « À trier »; `allocated` waits until Tom's
// share of the gîte is settled and checks the transfer leaves 200 € « Non affecté ».
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION          create | second | share | income | allocation | allocated | remove
//   EXPECTED_CENTS  income only: the month's income, in cents
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
// MAG-453: the créance a transfer from Tom repays.
const crozonLabel = 'GITE DE CROZON MAG-453'
const crozonObject = 'Gîte de Crozon'

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

function put(path, body) {
  const response = http.request(base + path, {
    method: 'PUT',
    headers: authorized({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    fail('PUT ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

function getJson(path) {
  const response = http.get(base + path, { headers: authorized() })
  if (!response.ok) {
    fail('GET ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

// The day before TODAY, so the transfer comes first in « À trier », oldest first.
function dayBefore(day) {
  const date = new Date(day + 'T12:00:00Z')
  date.setUTCDate(date.getUTCDate() - 1)
  return date.toISOString().substring(0, 10)
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
  // MAG-453: the allocations first, which free the shares they repaid and take the dettes they made.
  collection('/api/accounts?itemsPerPage=200')
    .filter(function (a) { return a.name === accountName })
    .forEach(function (a) {
      collection('/api/transactions?itemsPerPage=200&account=' + encodeURIComponent(a['@id']))
        .filter(function (line) { return line.peerKind })
        .forEach(function (line) {
          http.request(base + '/api/finance/transactions/' + line.id + '/allocation', { method: 'DELETE', headers: authorized() })
        })
    })
  collection('/api/peer_debts')
    .filter(function (debt) {
      return debt.originTransactionId && debt.shares.some(function (share) { return people.indexOf(share.contactName) >= 0 })
    })
    .forEach(function (debt) {
      // A dette (MAG-452) hangs on a received transfer, a créance on a shared spend.
      const endpoint = debt.direction === 'i_owe' ? '/debt' : '/my-share'
      http.request(base + '/api/finance/transactions/' + debt.originTransactionId + endpoint, { method: 'DELETE', headers: authorized() })
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
} else if (ACTION === 'income') {
  const dashboard = http.get(base + '/api/finance/dashboard', { headers: authorized() })
  if (!dashboard.ok) {
    fail('GET /api/finance/dashboard answered ' + dashboard.status + ': ' + dashboard.body)
  }
  const month = TODAY.substring(0, 7)
  const flow = JSON.parse(dashboard.body).monthlyFlows.filter(function (f) { return f.month === month })[0]
  const income = flow ? flow.incomeCents : 0
  if (income !== parseInt(EXPECTED_CENTS, 10)) {
    fail('the income of ' + month + ' is ' + income + ' cents, expected ' + EXPECTED_CENTS)
  }
} else if (ACTION === 'allocation') {
  const existing = account()
  if (!existing) {
    fail('no account « ' + accountName + ' »: run create first')
  }
  const voyages = awaitOne('/api/categories?itemsPerPage=200', categoryName)
  const tom = awaitOne('/api/contacts?pagination=false', contactName)
  const crozon = post('/api/transactions', {
    account: existing['@id'],
    category: '/api/categories/' + voyages.id,
    label: crozonLabel,
    amountCents: -40000,
    currency: 'EUR',
    bookedAt: TODAY,
    status: 'spent',
  })
  put('/api/finance/transactions/' + crozon.id + '/my-share', {
    myShareCents: 20000,
    object: crozonObject,
    shares: [{ contactId: tom.id }],
  })
  const received = post('/api/transactions', {
    account: existing['@id'],
    category: '/api/categories/' + voyages.id,
    label: label,
    amountCents: 40000,
    currency: 'EUR',
    bookedAt: dayBefore(TODAY),
    status: 'spent',
  })
  if (!received.contact) {
    fail('the third transfer was not attached to ' + contactName + ': ' + JSON.stringify(received))
  }
  output.allocatedTransferId = received.id
} else if (ACTION === 'allocated') {
  // Handed over by `allocation`; else found again: the only filed transfer from Tom.
  let transferId = output.allocatedTransferId
  if (!transferId) {
    const existing = account()
    const filed = existing
      ? collection('/api/transactions?itemsPerPage=200&account=' + encodeURIComponent(existing['@id']))
        .filter(function (line) { return line.label === label && line.category && line.amountCents === 40000 })
      : []
    if (filed.length !== 1) {
      fail('no single transfer to check (found ' + filed.length + '): run allocation first')
    }
    transferId = filed[0].id
  }
  const deadline = Date.now() + 30000
  for (;;) {
    const allocation = getJson('/api/finance/transactions/' + transferId + '/allocation')
    const gite = allocation.candidates.filter(function (c) { return c.object === crozonObject })[0]
    if (allocation.peerKind === 'repayment' && gite && gite.remainingCents === 0) {
      if (allocation.unallocatedCents !== 20000) {
        fail('the transfer leaves ' + allocation.unallocatedCents + ' cents « Non affecté », expected 20000')
      }
      break
    }
    if (Date.now() >= deadline) {
      fail('Tom\'s share of the gîte was never settled: ' + JSON.stringify(allocation))
    }
    pause(300)
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
