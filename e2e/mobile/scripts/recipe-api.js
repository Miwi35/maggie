// The other device's side of a recipe journey, played over HTTP (MAG-330).
//
// Same idea as `grocery-api.js`: Maestro drives one phone, so "changed elsewhere"
// is the request the admin (or Maggie) sends, signed in as the same user, and the
// server publishes to Mercure exactly as it does for a tab. The recipe is the
// journey's own, created and deleted here, so no seeded recipe or the grocery
// lines planned from it move.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION    create | create-method (both set output.recipeId) | set-quantity | set-notes | remove | expect
//             create-method: the steps spec's reference example (MAG-492) — boil water 10 min in the
//             background, cut the vegetables, cook the pasta 12 min in the background (waits 1),
//             sauté 8 min, drain and mix (waits 3 and 4) — with its utensils; 1 kg of pasta at
//             100 kcal per 100 g (500 kcal per serving, MAG-630)
//   RECIPE    the recipe's name
//   QUANTITY  create, set-quantity: grams of the recipe's single line
//   NOTES     set-notes: the notes
//   STATE     expect: present | absent
//   WAIT_MS   pause before acting, in ms — gives the app time to open its
//             Mercure subscription, which a write made at once would beat
//
// Polled, never read once: a write is indexed before the collection shows it.

(function () {
const base = E2E_BASE_URL
// MAG-577: a flow with a user of its own passes it as EMAIL; the seed's user is the fallback.
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'
const ingredientName = 'Orge MAG-330'
const methodIngredients = ['Pâtes MAG-492', 'Légumes MAG-492']
const methodUtensils = ['Casserole MAG-492', 'Poêle MAG-492', 'Mixeur MAG-492']

function fail(message) {
  throw new Error('[recipe-api] ' + ACTION + ' "' + RECIPE + '": ' + message)
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

if (typeof WAIT_MS !== 'undefined' && WAIT_MS) {
  pause(Number(WAIT_MS))
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

function findRecipe() {
  return collection('/api/recipes?itemsPerPage=200').filter(function (row) {
    return row.name === RECIPE
  })[0]
}

function waitFor(predicate, what) {
  for (;;) {
    const recipe = findRecipe()
    if (predicate(recipe)) {
      return recipe
    }
    if (Date.now() >= deadline) {
      fail(what + ' within 30 s')
    }
    pause(300)
  }
}

// The recipe as the edit form sends it back: the GET's own body, one part changed.
function patch(change) {
  const recipe = waitFor(function (row) { return row !== undefined }, 'the recipe never became findable')
  const read = http.get(base + recipe['@id'], { headers: authorized({ Accept: 'application/ld+json' }) })
  if (!read.ok) {
    fail('GET ' + recipe['@id'] + ' answered ' + read.status + ': ' + read.body)
  }
  const record = JSON.parse(read.body)
  change(record)
  const response = http.request(base + recipe['@id'], {
    method: 'PATCH',
    headers: authorized({ 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' }),
    body: JSON.stringify(record),
  })
  if (!response.ok) {
    fail('PATCH ' + recipe['@id'] + ' answered ' + response.status + ': ' + response.body)
  }
}

function removeLeftovers() {
  collection('/api/recipes?itemsPerPage=200')
    .filter(function (row) { return row.name === RECIPE })
    .forEach(function (row) {
      http.request(base + row['@id'], { method: 'DELETE', headers: authorized() })
    })
  collection('/api/ingredients?itemsPerPage=200')
    .filter(function (row) { return row.name === ingredientName || methodIngredients.indexOf(row.name) !== -1 })
    .forEach(function (row) {
      http.request(base + row['@id'], { method: 'DELETE', headers: authorized() })
    })
  // The utensils the journey created by name, or added from the phone.
  collection('/api/utensils?itemsPerPage=200')
    .filter(function (row) { return methodUtensils.indexOf(row.name) !== -1 })
    .forEach(function (row) {
      http.request(base + row['@id'], { method: 'DELETE', headers: authorized() })
    })
}

function createIngredient(name, nutrition) {
  const response = http.post(base + '/api/ingredients', {
    headers: authorized(LD),
    body: JSON.stringify(Object.assign({ name: name, category: 'grain' }, nutrition || {})),
  })
  if (!response.ok) {
    fail('POST /api/ingredients answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)['@id']
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const ingredient = http.post(base + '/api/ingredients', {
    headers: authorized(LD),
    body: JSON.stringify({ name: ingredientName, category: 'grain' }),
  })
  if (!ingredient.ok) {
    fail('POST /api/ingredients answered ' + ingredient.status + ': ' + ingredient.body)
  }
  const response = http.post(base + '/api/recipes', {
    headers: authorized(LD),
    body: JSON.stringify({
      name: RECIPE,
      servings: 2,
      notes: 'Notes de départ',
      ingredients: [{ ingredient: JSON.parse(ingredient.body)['@id'], quantity: Number(QUANTITY), unit: 'g' }],
    }),
  })
  if (!response.ok) {
    fail('POST /api/recipes answered ' + response.status + ': ' + response.body)
  }
  waitFor(function (row) { return row !== undefined }, 'the created recipe never became findable')
  output.recipeId = JSON.parse(response.body).id
} else if (ACTION === 'create-method') {
  removeLeftovers()
  // MAG-630: the pasta carries values, so the sheet's « Par personne » has figures to show.
  const pasta = createIngredient(methodIngredients[0], { kcalPer100g: 100, proteinPer100g: 4, carbsPer100g: 20, fatPer100g: 1 })
  const vegetables = createIngredient(methodIngredients[1])
  const response = http.post(base + '/api/recipes', {
    headers: authorized(LD),
    body: JSON.stringify({
      name: RECIPE,
      servings: 2,
      ingredients: [
        { ingredient: pasta, quantity: 1, unit: 'kg' },
        { ingredient: vegetables, quantity: 300, unit: 'g' },
      ],
      utensils: [{ name: methodUtensils[0] }, { name: methodUtensils[1] }],
      steps: [
        { text: "Mettre l'eau à bouillir", durationMinutes: 10, background: true, utensils: [{ name: methodUtensils[0] }] },
        { text: 'Couper les légumes', ingredientIds: [methodIngredients[1]] },
        { text: 'Cuire les pâtes', durationMinutes: 12, background: true, ingredientIds: [methodIngredients[0]], utensils: [{ name: methodUtensils[0] }], waitsFor: [1] },
        { text: 'Faire revenir les légumes', durationMinutes: 8, ingredientIds: [methodIngredients[1]], utensils: [{ name: methodUtensils[1] }] },
        { text: 'Égoutter, mélanger', waitsFor: [3, 4] },
      ],
    }),
  })
  if (!response.ok) {
    fail('POST /api/recipes answered ' + response.status + ': ' + response.body)
  }
  waitFor(function (row) { return row !== undefined }, 'the created recipe never became findable')
  output.recipeId = JSON.parse(response.body).id
} else if (ACTION === 'set-quantity') {
  patch(function (record) { record.ingredients[0].quantity = Number(QUANTITY) })
} else if (ACTION === 'set-notes') {
  patch(function (record) { record.notes = NOTES })
} else if (ACTION === 'remove') {
  const recipe = waitFor(function (row) { return row !== undefined }, 'the recipe never became findable')
  const response = http.request(base + recipe['@id'], { method: 'DELETE', headers: authorized() })
  if (!response.ok) {
    fail('DELETE ' + recipe['@id'] + ' answered ' + response.status + ': ' + response.body)
  }
  removeLeftovers()
} else if (ACTION === 'expect') {
  const states = {
    present: function (row) { return row !== undefined },
    absent: function (row) { return row === undefined },
  }
  if (!states[STATE]) {
    fail('unknown STATE "' + STATE + '"')
  }
  waitFor(states[STATE], 'the recipe is not ' + STATE + ' in the database')
} else {
  fail('unknown ACTION')
}
})()
