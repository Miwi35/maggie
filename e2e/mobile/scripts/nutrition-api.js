// The lunch of the Santé › Nutrition journey, planned over HTTP (MAG-748).
//
// The journey's user is new, so it has no recipe: this creates an ingredient with figures
// (515 kcal, 20 g of protein, 50 g of carbs, 25 g of fat per 100 g), a recipe of one serving
// with 100 g of it — 515 kcal a serving — and plans it for TODAY's lunch. « Mangé » then
// copies one serving: Mangé 515, Prévu 0. TODAY is the stack's day, YYYY-MM-DD, never this
// host's clock. Sets output.nutritionMealId, the meal's id, which the flow taps « Mangé » by.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   EMAIL   the journey's user (`${output.journeyEmail}`)
//   TODAY   the stack's day (`${TODAY}`)
//
// Polled, never read once: the planned meal shows in the day's balance once the API has it.

(function () {
const base = E2E_BASE_URL

function fail(message) {
  throw new Error('[nutrition-api] ' + message)
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: EMAIL }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + EMAIL + ': ' + login.body)
}
const token = JSON.parse(login.body).token
const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json', Authorization: 'Bearer ' + token }

function post(path, body) {
  const response = http.post(base + path, { headers: LD, body: JSON.stringify(body) })
  if (!response.ok) {
    fail('POST ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

const ingredient = post('/api/ingredients', {
  name: 'Bol complet MAG-748',
  category: 'grain',
  defaultUnit: 'g',
  kcalPer100g: 515,
  proteinPer100g: 20,
  carbsPer100g: 50,
  fatPer100g: 25,
})
const recipe = post('/api/recipes', {
  name: 'Bol MAG-748',
  servings: 1,
  ingredients: [{ ingredient: ingredient['@id'], quantity: 100, unit: 'g' }],
})
const meal = post('/api/meals', { date: TODAY, slot: 'lunch', recipes: [recipe['@id']] })

const deadline = Date.now() + 30000
for (;;) {
  const response = http.get(base + '/api/health/daily-balance?date=' + TODAY, {
    headers: { Accept: 'application/json', Authorization: 'Bearer ' + token },
  })
  if (!response.ok) {
    fail('GET /api/health/daily-balance answered ' + response.status + ': ' + response.body)
  }
  const balance = JSON.parse(response.body)
  const planned = (balance.plannedMeals || []).filter(function (row) { return row.mealId === meal.id })[0]
  if (planned && planned.kcal === 515) {
    break
  }
  if (Date.now() >= deadline) {
    fail('the lunch never reached the day\'s balance at 515 kcal within 30 s: ' + response.body)
  }
  pause(300)
}

output.nutritionMealId = meal.id
})()
