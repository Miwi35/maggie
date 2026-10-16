// The data side of the nested-envelopes journey, played over HTTP (MAG-412).
//
// The journey reads the budget screen; this script sets the table before (Loisirs ›
// Bars › Obsydian, an envelope on Loisirs and one on Obsydian, an account, a spend) and
// files a second spend while the screen is open. Same login as `meal-api.js`, as the
// seed's second user, who holds no finance data of its own.
//
// Collections are served from Elasticsearch, which trails a write: what the script has
// just made is addressed by the id its POST returned, and what an earlier action made is
// polled for, never read once.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create (Obsydian 40 €) | spend-bars (Bars 25 €) | create-split (MAG-413: Loisirs 300 €,
//           Bars 100 €, Cinéma 50 €) | split (Bars 120 €, Cinéma 30 €)
//           | hellfest (a one-off « Hellfest 2026 » of 400 € and an 89 € ticket in Loisirs, MAG-414)
//           | cinema (Cinéma 20 € this month, 45 € in a season from next month on, MAG-415)
//           | create-adjust (MAG-461: Loisirs at 100 € and 140 € spent, this month and the two before)
//           | remove
//   TODAY   the seed's anchor day, YYYY-MM-DD: the month the screen opens on
//
// Everything is the journey's own, named after the ticket, and gone after `remove`.

(function () {
const base = E2E_BASE_URL
const email = 'e2e-other@maggie.local'
const accountName = 'Compte MAG-412'
const hellfestName = 'Hellfest 2026'
const year = parseInt(TODAY.substring(0, 4), 10)
const month = parseInt(TODAY.substring(5, 7), 10)

function fail(message) {
  throw new Error('[finance-budget-api] ' + ACTION + ': ' + message)
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

function named(path, name) {
  return collection(path).filter(function (row) { return row.name === name })
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
    const rows = named(path, name)
    if (rows.length === 1) {
      return rows[0]
    }
    if (Date.now() >= deadline) {
      fail('"' + name + '" never became findable within 30 s (found ' + rows.length + ')')
    }
    pause(300)
  }
}

function spend(accountId, categoryId, label, amountCents, bookedAt) {
  post('/api/transactions', {
    account: '/api/accounts/' + accountId,
    category: '/api/categories/' + categoryId,
    label: label,
    amountCents: -amountCents,
    currency: 'EUR',
    bookedAt: bookedAt || TODAY,
    status: 'spent',
  })
}

function removeLeftovers() {
  const envelopeIds = ['Loisirs', 'Bars', 'Obsydian', 'Cinéma'].flatMap(function (name) {
    return named('/api/categories?itemsPerPage=200', name).flatMap(function (category) {
      return collection('/api/envelopes?itemsPerPage=200')
        .filter(function (envelope) { return envelope.category === '/api/categories/' + category.id })
        .map(function (envelope) { return envelope['@id'] })
    })
  })
  // A one-off envelope has no category to be found by: it goes by its name.
  collection('/api/envelopes?itemsPerPage=200')
    .filter(function (envelope) { return envelope.name === hellfestName })
    .forEach(function (envelope) { envelopeIds.push(envelope['@id']) })
  envelopeIds.forEach(function (iri) {
    http.request(base + iri, { method: 'DELETE', headers: authorized() })
  })
  named('/api/accounts?itemsPerPage=200', accountName).forEach(function (account) {
    http.request(base + account['@id'], { method: 'DELETE', headers: authorized() })
  })
  // Deepest first: a category with children cannot be deleted.
  ;['Obsydian', 'Bars', 'Loisirs', 'Cinéma'].forEach(function (name) {
    named('/api/categories?itemsPerPage=200', name).forEach(function (category) {
      http.request(base + category['@id'], { method: 'DELETE', headers: authorized() })
    })
  })
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const loisirs = post('/api/categories', { name: 'Loisirs' })
  const bars = post('/api/categories', {
    name: 'Bars',
    parent: '/api/categories/' + loisirs.id,
  })
  const obsydian = post('/api/categories', {
    name: 'Obsydian',
    parent: '/api/categories/' + bars.id,
  })
  ;[[loisirs, 30000], [obsydian, 10000]].forEach(function (pair) {
    post('/api/envelopes', {
      category: '/api/categories/' + pair[0].id,
      mode: 'monthly',
      amountCents: pair[1],
      currency: 'EUR',
      year: year,
      month: month,
    })
  })
  const account = post('/api/accounts', { name: accountName, type: 'checking', currency: 'EUR', balanceCents: 0 })
  spend(account.id, obsydian.id, 'OBSYDIAN MAG-412', 4000)
  // The next action looks the tree up by name: leave only once it is findable.
  awaitOne('/api/categories?itemsPerPage=200', 'Bars')
  awaitOne('/api/accounts?itemsPerPage=200', accountName)
} else if (ACTION === 'create-adjust') {
  // Loisirs exceeded three months running: the month under way and the two before it.
  removeLeftovers()
  const loisirs = post('/api/categories', { name: 'Loisirs' })
  const account = post('/api/accounts', { name: accountName, type: 'checking', currency: 'EUR', balanceCents: 0 })
  ;[0, 1, 2].forEach(function (back) {
    const index = year * 12 + (month - 1) - back
    const y = Math.floor(index / 12)
    const m = index % 12 + 1
    post('/api/envelopes', {
      category: '/api/categories/' + loisirs.id,
      mode: 'monthly',
      amountCents: 10000,
      currency: 'EUR',
      year: y,
      month: m,
    })
    const day = y + '-' + (m < 10 ? '0' : '') + m + '-' + (back === 0 ? TODAY.substring(8, 10) : '10')
    spend(account.id, loisirs.id, 'LOISIRS MAG-461 ' + day, 14000, day)
  })
  awaitOne('/api/categories?itemsPerPage=200', 'Loisirs')
  awaitOne('/api/accounts?itemsPerPage=200', accountName)
} else if (ACTION === 'spend-bars') {
  const account = awaitOne('/api/accounts?itemsPerPage=200', accountName)
  const bars = awaitOne('/api/categories?itemsPerPage=200', 'Bars')
  spend(account.id, bars.id, 'BARS MAG-412', 2500)
} else if (ACTION === 'hellfest') {
  const account = awaitOne('/api/accounts?itemsPerPage=200', accountName)
  const loisirs = awaitOne('/api/categories?itemsPerPage=200', 'Loisirs')
  post('/api/envelopes', { mode: 'one_off', name: hellfestName, amountCents: 40000, currency: 'EUR' })
  spend(account.id, loisirs.id, 'BILLET HELLFEST MAG-414', 8900)
  // The sheet offers the one-off envelopes it reads from the list.
  awaitOne('/api/envelopes?itemsPerPage=200', hellfestName)
} else if (ACTION === 'cinema') {
  const next = month % 12 + 1
  const cinema = post('/api/categories', { name: 'Cinéma' })
  post('/api/envelopes', {
    category: '/api/categories/' + cinema.id,
    mode: 'monthly',
    amountCents: 2000,
    currency: 'EUR',
    year: year,
    month: month,
    seasonalAmounts: [{ fromMonth: next, toMonth: next % 12 + 1, amountCents: 4500 }],
  })
  awaitOne('/api/categories?itemsPerPage=200', 'Cinéma')
} else if (ACTION === 'create-split') {
  removeLeftovers()
  const loisirs = post('/api/categories', { name: 'Loisirs' })
  ;[['Bars', 10000], ['Cinéma', 5000]].forEach(function (pair) {
    const daughter = post('/api/categories', {
      name: pair[0],
      parent: '/api/categories/' + loisirs.id,
    })
    post('/api/envelopes', {
      category: '/api/categories/' + daughter.id,
      mode: 'monthly',
      amountCents: pair[1],
      currency: 'EUR',
      year: year,
      month: month,
    })
  })
  post('/api/envelopes', {
    category: '/api/categories/' + loisirs.id,
    mode: 'monthly',
    amountCents: 30000,
    currency: 'EUR',
    year: year,
    month: month,
  })
  awaitOne('/api/categories?itemsPerPage=200', 'Cinéma')
} else if (ACTION === 'split') {
  // What the admin's ruler does: one write that moves 20 € from Cinéma to Bars.
  function envelopeOf(name) {
    const category = awaitOne('/api/categories?itemsPerPage=200', name)
    const deadline = Date.now() + 30000
    for (;;) {
      const rows = collection('/api/envelopes?itemsPerPage=200').filter(function (envelope) {
        return envelope.category === '/api/categories/' + category.id
      })
      if (rows.length === 1) {
        return rows[0]
      }
      if (Date.now() >= deadline) {
        fail('the envelope of "' + name + '" never became findable within 30 s')
      }
      pause(300)
    }
  }
  const mother = envelopeOf('Loisirs')
  const bars = envelopeOf('Bars')
  const cinema = envelopeOf('Cinéma')
  const response = http.request(base + '/api/finance/envelopes/' + mother.id + '/split', {
    method: 'PUT',
    headers: authorized({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({
      children: [
        { envelopeId: bars.id, amountCents: 12000 },
        { envelopeId: cinema.id, amountCents: 3000 },
      ],
    }),
  })
  if (!response.ok) {
    fail('PUT split answered ' + response.status + ': ' + response.body)
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
