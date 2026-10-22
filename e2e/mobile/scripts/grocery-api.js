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
//   ACTION  add | check | remove | expect | aisles | assign | drop-aisles |
//           product | drop-product | expect-line
//   LABEL   the line, as written on the list
//   EMAIL   the account, default the shopper (e2e-other@maggie.local)
//   STORE   add: the shop's name, an existing one — left out, no shop;
//           aisles, assign, drop-aisles: the shop the aisles belong to
//   STATE   expect: present | absent | checked | unchecked
//   AISLES  aisles, drop-aisles: names separated by `|`, in the order of the way
//           through the shop — `aisles` creates those missing, last in the shop
//           (MAG-493); `drop-aisles` deletes those present
//   AISLE   assign: the aisle the line's product takes in STORE, by name; the
//           script waits until the list carries it, as the app will read it
//   product, drop-product: LABEL is the product's name. `product` creates a pasta
//           in 500 g packs, two packs when it runs low, restocked by itself —
//           left as found when it exists; `drop-product` deletes it (MAG-573)
//   expect-line: the line's quantity and unit as stored (QUANTITY, UNIT) and,
//           when NEEDED_QUANTITY is given, the single need kept beside it
//           (NEEDED_QUANTITY, NEEDED_UNIT) — the numbers the add sheet sent
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
  const label = typeof LABEL !== 'undefined' ? LABEL : ''
  throw new Error('[grocery-api] ' + ACTION + ' "' + label + '": ' + message)
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

function idOf(reference) {
  if (!reference) {
    return null
  }
  const value = typeof reference === 'string' ? reference : reference.id || reference['@id']
  return String(value).split('/').pop()
}

function storeId() {
  const response = http.get(base + '/api/stores?pagination=false', {
    headers: authorized({ Accept: 'application/ld+json' }),
  })
  if (!response.ok) {
    fail('GET /api/stores answered ' + response.status + ': ' + response.body)
  }
  const store = JSON.parse(response.body).member.filter(function (candidate) {
    return candidate.name === STORE
  })[0]
  if (!store) {
    fail('no shop named "' + STORE + '" — did the seed run?')
  }
  return store.id
}

function aislesOf(store) {
  const response = http.get(base + '/api/aisles?store=' + encodeURIComponent('/api/stores/' + store), {
    headers: authorized({ Accept: 'application/ld+json' }),
  })
  if (!response.ok) {
    fail('GET /api/aisles answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).member
}

function aisleNames() {
  return String(AISLES).split('|').map(function (name) { return name.trim() }).filter(Boolean)
}

// The list as the app reads it: the item endpoint, which carries each line's aisle.
function linesWithAisles() {
  const collection = http.get(base + '/api/grocery_lists', {
    headers: authorized({ Accept: 'application/ld+json' }),
  })
  if (!collection.ok) {
    fail('GET /api/grocery_lists answered ' + collection.status + ': ' + collection.body)
  }
  const list = JSON.parse(collection.body).member[0]
  const response = http.get(base + '/api/grocery_lists/' + list.id, {
    headers: authorized({ Accept: 'application/ld+json' }),
  })
  if (!response.ok) {
    fail('GET /api/grocery_lists/' + list.id + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).items
}

function productNamed(name) {
  const response = http.get(base + '/api/products?itemsPerPage=200', {
    headers: authorized({ Accept: 'application/ld+json' }),
  })
  if (!response.ok) {
    fail('GET /api/products answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).member.filter(function (product) {
    return product.name === name
  })[0]
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
} else if (ACTION === 'aisles') {
  const store = storeId()
  const existing = aislesOf(store).map(function (aisle) { return aisle.name })
  aisleNames().forEach(function (name) {
    // A run that failed before its clean-up left them: taken as they are.
    if (existing.indexOf(name) !== -1) {
      return
    }
    const response = http.post(base + '/api/aisles', {
      headers: authorized({ 'Content-Type': 'application/json', Accept: 'application/ld+json' }),
      body: JSON.stringify({ store: '/api/stores/' + store, name: name }),
    })
    if (!response.ok) {
      fail('POST /api/aisles "' + name + '" answered ' + response.status + ': ' + response.body)
    }
  })
} else if (ACTION === 'assign') {
  const store = storeId()
  const aisle = aislesOf(store).filter(function (candidate) { return candidate.name === AISLE })[0]
  if (!aisle) {
    fail('no aisle "' + AISLE + '" in "' + STORE + '" — run the `aisles` action first')
  }
  const line = waitFor(function (item) { return item !== undefined }, 'the line never became findable')
  const productId = idOf(line.product)
  if (!productId) {
    fail('the line has no product, so no aisle')
  }
  // Polled like the list: a product the add just created may not be indexed yet.
  let product
  for (;;) {
    product = http.get(base + '/api/products/' + productId, {
      headers: authorized({ Accept: 'application/ld+json' }),
    })
    if (product.ok) {
      break
    }
    if (Date.now() >= deadline) {
      fail('GET /api/products/' + productId + ' answered ' + product.status + ': ' + product.body)
    }
    pause(300)
  }
  // The list is replaced whole: the product's aisles in the other shops are sent back.
  const aisles = (JSON.parse(product.body).aisles || []).filter(function (entry) {
    return entry.store !== store
  })
  aisles.push({ store: store, aisle: aisle.id })
  const response = http.request(base + '/api/products/' + productId, {
    method: 'PATCH',
    headers: authorized({ 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' }),
    body: JSON.stringify({ aisles: aisles }),
  })
  if (!response.ok) {
    fail('PATCH /api/products/' + productId + ' answered ' + response.status + ': ' + response.body)
  }
  for (;;) {
    const assigned = linesWithAisles().filter(function (item) {
      return item.label === LABEL && item.aisle && item.aisle.name === AISLE
    })[0]
    if (assigned) {
      break
    }
    if (Date.now() >= deadline) {
      fail('the list never carried the aisle "' + AISLE + '" within 30 s')
    }
    pause(300)
  }
} else if (ACTION === 'drop-aisles') {
  const store = storeId()
  const names = aisleNames()
  aislesOf(store).filter(function (aisle) { return names.indexOf(aisle.name) !== -1 }).forEach(function (aisle) {
    const response = http.request(base + '/api/aisles/' + aisle.id, {
      method: 'DELETE',
      headers: authorized(),
    })
    if (!response.ok) {
      fail('DELETE /api/aisles/' + aisle.id + ' answered ' + response.status + ': ' + response.body)
    }
  })
} else if (ACTION === 'product') {
  const known = productNamed(LABEL)
  if (!known) {
    const response = http.post(base + '/api/products', {
      headers: authorized({ 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }),
      body: JSON.stringify({
        name: LABEL,
        category: 'grain',
        packagingUnit: 'pack',
        packagingSize: 500,
        packagingSizeUnit: 'g',
        restockQuantity: 2,
        autoRestock: true,
      }),
    })
    if (!response.ok) {
      fail('POST /api/products answered ' + response.status + ': ' + response.body)
    }
  }
  // Findable before the script returns: the app loads its products at launch.
  for (;;) {
    if (productNamed(LABEL)) {
      break
    }
    if (Date.now() >= deadline) {
      fail('the product never became findable within 30 s')
    }
    pause(300)
  }
} else if (ACTION === 'drop-product') {
  const known = productNamed(LABEL)
  if (known) {
    const response = http.request(base + '/api/products/' + known.id, {
      method: 'DELETE',
      headers: authorized(),
    })
    if (!response.ok) {
      fail('DELETE /api/products/' + known.id + ' answered ' + response.status + ': ' + response.body)
    }
  }
} else if (ACTION === 'expect-line') {
  const needs = typeof NEEDED_QUANTITY !== 'undefined' && NEEDED_QUANTITY !== ''
  waitFor(function (line) {
    if (line === undefined || line.quantity !== Number(QUANTITY) || line.unit !== UNIT) {
      return false
    }
    if (!needs) {
      return true
    }
    return (line.needed || []).length === 1
      && line.needed[0].quantity === Number(NEEDED_QUANTITY)
      && line.needed[0].unit === NEEDED_UNIT
  }, 'the line does not hold ' + QUANTITY + ' ' + UNIT + (needs ? ' with a need of ' + NEEDED_QUANTITY + ' ' + NEEDED_UNIT : ''))
} else {
  fail('unknown ACTION')
}
})()
