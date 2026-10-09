// The data side of the meal-ingredient-choice journey, played over HTTP (MAG-297).
//
// The journey plans the meal on the phone; this script only sets the table before
// (a recipe whose two products have a stock and a packaging, which no seed holds)
// and reads the database after (the meal says the owner chose). Same login and
// polling as `recipe-api.js`.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create (sets output.recipeId) | expect-chosen | remove
//
// Everything carries the ticket's number and is gone after `remove`, meal and
// grocery lines included (deleting the meal takes its lines off the list).
//
// Polled, never read once: a write is indexed before the collection shows it.

(function () {
const base = E2E_BASE_URL
const email = 'e2e@maggie.local'
const recipeName = 'Riz au curry MAG-297'
const riceName = 'Riz MAG-297'
const vegetablesName = 'Légumes pour couscous MAG-297'

function fail(message) {
  throw new Error('[meal-api] ' + ACTION + ': ' + message)
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
const deadline = Date.now() + 30000

function collection(path) {
  const response = http.get(base + path, { headers: authorized({ Accept: 'application/ld+json' }) })
  if (!response.ok) {
    fail('GET ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).member
}

function named(path, name) {
  return collection(path).filter(function (row) { return row.name === name })
}

function meals() {
  return collection('/api/meals?itemsPerPage=200').filter(function (row) { return String(row.summary).includes(recipeName) })
}

function post(path, body) {
  const response = http.post(base + path, { headers: authorized(LD), body: JSON.stringify(body) })
  if (!response.ok) {
    fail('POST ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

function removeAll(rows) {
  rows.forEach(function (row) {
    http.request(base + row['@id'], { method: 'DELETE', headers: authorized() })
  })
}

// Meal first (its lines leave the list with it), then the recipe, then its products.
function removeLeftovers() {
  removeAll(meals())
  removeAll(named('/api/recipes?itemsPerPage=200', recipeName))
  removeAll(named('/api/ingredients?itemsPerPage=200', riceName))
  removeAll(named('/api/ingredients?itemsPerPage=200', vegetablesName))
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  // Rice is out and bought by the 500 g pack; the vegetables are in the cupboard, by the jar.
  const rice = post('/api/ingredients', {
    name: riceName,
    category: 'grain',
    packagingUnit: 'pack',
    packagingSize: 500,
    packagingSizeUnit: 'g',
    stockState: 'out',
  })
  const vegetables = post('/api/ingredients', {
    name: vegetablesName,
    category: 'produce',
    packagingUnit: 'jar',
    stockState: 'in_stock',
  })
  const recipe = post('/api/recipes', {
    name: recipeName,
    servings: 2,
    ingredients: [
      { ingredient: rice['@id'], quantity: 300, unit: 'g' },
      { ingredient: vegetables['@id'], quantity: 1, unit: 'jar' },
    ],
  })
  for (;;) {
    if (named('/api/recipes?itemsPerPage=200', recipeName).length === 1) {
      break
    }
    if (Date.now() >= deadline) {
      fail('the created recipe never became findable within 30 s')
    }
    pause(300)
  }
  output.recipeId = recipe.id
} else if (ACTION === 'expect-chosen') {
  for (;;) {
    const planned = meals()
    if (planned.length === 1 && planned[0].groceryChoiceMadeAt) {
      break
    }
    if (Date.now() >= deadline) {
      fail('the meal "' + recipeName + '" never carried a grocery choice within 30 s (found ' + meals().length + ' meal(s))')
    }
    pause(300)
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
