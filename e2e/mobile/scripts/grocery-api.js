// The browser's side of a grocery journey, played over HTTP (MAG-178).
//
// Maestro drives one Android device and cannot drive a browser, so "written
// from the web" is the same request the admin sends, signed in as the same
// user: this script posts to the endpoints `GroceryListPage.addItem` and the
// tick box call (`e2e/web/pages`), and the server publishes to Mercure exactly
// as it does for a tab. What it cannot prove is the browser's own rendering,
// which `e2e/web/tests/grocery-errand.spec.ts` owns.
//
// It also reads the list back through the API, which is how a flow asserts on
// the database rather than on the screen.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  add | check | remove | expect
//   LABEL   the line, as written on the list
//   EMAIL   the account, default the shopper (e2e-other@maggie.local)
//   STORE   add: the shop's name, an existing one — left out, no shop
//   STATE   expect: present | absent | checked | unchecked
//   WAIT_MS pause before acting, in ms — gives the app time to open its
//           Mercure subscription, which a write made at once would beat
//
// Polled, never read once: a write is dispatched to RabbitMQ and indexed before
// the collection reflects it (see `waitForIndexed` in the web helpers).

// Wrapped so a second call in the same flow never redeclares these.
(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e-other@maggie.local'

function fail(message) {
  throw new Error('[grocery-api] ' + ACTION + ' "' + LABEL + '": ' + message)
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

function login() {
  const response = http.post(base + '/api/auth/e2e/login', {
    headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
    body: JSON.stringify({ email: email }),
  })
  if (!response.ok) {
    fail('the test login answered ' + response.status + ' for ' + email + ': ' + response.body)
  }
  return JSON.parse(response.body).token
}

if (typeof WAIT_MS !== 'undefined' && WAIT_MS) {
  pause(Number(WAIT_MS))
}

const deadline = Date.now() + 30000
const token = login()
const authorized = function (extra) {
  return Object.assign({ Authorization: 'Bearer ' + token }, extra || {})
}

function lines() {
  const response = http.get(base + '/api/grocery_lists', {
    headers: authorized({ Accept: 'application/ld+json' }),
  })
  if (!response.ok) {
    fail('GET /api/grocery_lists answered ' + response.status + ': ' + response.body)
  }
  const list = JSON.parse(response.body).member[0]
  if (!list) {
    fail('the account has no grocery list — did the seed run?')
  }
  return list.items
}

function find() {
  return lines().filter(function (line) {
    return line.label === LABEL
  })[0]
}

function waitFor(predicate, what) {
  let seen = []
  for (;;) {
    seen = lines()
    const line = seen.filter(function (item) {
      return item.label === LABEL
    })[0]
    if (predicate(line)) {
      return line
    }
    if (Date.now() >= deadline) {
      fail(what + ' within 30 s. The list holds: ' + seen.map(function (item) {
        return item.label + (item.checked ? ' (checked)' : '')
      }).join(', '))
    }
    pause(300)
  }
}

if (ACTION === 'add') {
  const body = { label: LABEL, quantity: 1 }
  if (typeof STORE !== 'undefined' && STORE) {
    body.storeName = STORE
  }
  const response = http.post(base + '/api/grocery/add-item', {
    headers: authorized({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    fail('POST /api/grocery/add-item answered ' + response.status + ': ' + response.body)
  }
  // Findable before the script returns: a flow that launches the app next
  // reads the same index, and a line still in flight would miss the first load.
  waitFor(function (line) { return line !== undefined }, 'the added line never became findable')
} else if (ACTION === 'check') {
  const line = waitFor(function (item) { return item !== undefined }, 'the line never became findable')
  const response = http.request(base + '/api/grocery_items/' + line.id, {
    method: 'PATCH',
    headers: authorized({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ checked: true }),
  })
  if (!response.ok) {
    fail('PATCH /api/grocery_items answered ' + response.status + ': ' + response.body)
  }
} else if (ACTION === 'remove') {
  const line = waitFor(function (item) { return item !== undefined }, 'the line never became findable')
  const response = http.request(base + '/api/grocery_items/' + line.id, {
    method: 'DELETE',
    headers: authorized(),
  })
  if (!response.ok) {
    fail('DELETE /api/grocery_items answered ' + response.status + ': ' + response.body)
  }
} else if (ACTION === 'expect') {
  const states = {
    present: function (line) { return line !== undefined },
    absent: function (line) { return line === undefined },
    checked: function (line) { return line !== undefined && line.checked === true },
    unchecked: function (line) { return line !== undefined && line.checked === false },
  }
  if (!states[STATE]) {
    fail('unknown STATE "' + STATE + '"')
  }
  waitFor(states[STATE], 'the line is not ' + STATE + ' in the database')
} else {
  fail('unknown ACTION')
}
})()
