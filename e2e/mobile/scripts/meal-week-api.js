// The data side of the Cuisine › Repas journey (MAG-628), played over HTTP.
//
// Sets the table before the phone opens Cuisine: two recipes of 4 servings (400 g of one product
// each) and a meal of both, today, for 4 guests — the week's page shows it without a swipe.
// The recipes and the meal are the journey's own and gone after `remove`.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | expect-unchanged | remove
//           expect-unchanged: the meal still holds 4 guests — the portions « − » of the card are a
//           simulation on the screen and never a write
//
// Polled, never read once: a write is indexed before the collection shows it.

(function () {
const base = E2E_BASE_URL
const email = 'e2e@maggie.local'
const recipeNames = ['Soupe MAG-628', 'Tarte MAG-628']
const productName = 'Farine MAG-628'

function fail(message) {
  throw new Error('[meal-week-api] ' + ACTION + ': ' + message)
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

// Today in Paris, which is where the API reads a meal's day: UTC+1, UTC+2 between the last Sundays of March and October.
function parisToday() {
  const now = new Date()
  const year = now.getUTCFullYear()
  function lastSunday(month) {
    const last = new Date(Date.UTC(year, month + 1, 0, 1))
    return Date.UTC(year, month, last.getUTCDate() - last.getUTCDay(), 1)
  }
  const summer = now.getTime() >= lastSunday(2) && now.getTime() < lastSunday(9)
  return new Date(now.getTime() + (summer ? 2 : 1) * 3600 * 1000).toISOString().slice(0, 10)
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

function post(path, body) {
  const response = http.post(base + path, { headers: authorized(LD), body: JSON.stringify(body) })
  if (!response.ok) {
    fail('POST ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

function remove(rows) {
  rows.forEach(function (row) {
    http.request(base + row['@id'], { method: 'DELETE', headers: authorized() })
  })
}

function journeyMeals() {
  return collection('/api/meals?itemsPerPage=200').filter(function (row) {
    return String(row.summary).includes('MAG-628')
  })
}

// Meal first, then the recipes, then the product.
function removeLeftovers() {
  remove(journeyMeals())
  remove(collection('/api/recipes?itemsPerPage=200').filter(function (row) { return recipeNames.indexOf(row.name) !== -1 }))
  remove(collection('/api/ingredients?itemsPerPage=200').filter(function (row) { return row.name === productName }))
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const flour = post('/api/ingredients', { name: productName, category: 'grain' })
  const iris = recipeNames.map(function (name) {
    return post('/api/recipes', {
      name: name,
      servings: 4,
      ingredients: [{ ingredient: flour['@id'], quantity: 400, unit: 'g' }],
    })['@id']
  })
  const meal = post('/api/meals', {
    date: parisToday(),
    slot: 'dinner',
    guests: 4,
    recipes: iris,
  })
  for (;;) {
    const found = journeyMeals().length === 1 &&
      collection('/api/recipes?itemsPerPage=200').filter(function (row) { return recipeNames.indexOf(row.name) !== -1 }).length === 2
    if (found) {
      break
    }
    if (Date.now() >= deadline) {
      fail('the meal ' + meal.id + ' and its recipes never became findable within 30 s')
    }
    pause(300)
  }
} else if (ACTION === 'expect-unchanged') {
  // The debounce of a save is 300 ms: look after it, then check the meal.
  pause(2000)
  const planned = journeyMeals()
  if (planned.length !== 1 || planned[0].guests !== 4) {
    fail('expected the meal to keep 4 guests, found ' + JSON.stringify(planned.map(function (row) { return row.guests })))
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else {
  fail('unknown ACTION')
}
})()
