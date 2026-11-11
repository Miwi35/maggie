// The lunch of the Santé › Nutrition journey, planned over HTTP (MAG-748), and its fast (MAG-770).
//
// The journey's user is new, so it has no recipe: this creates an ingredient with figures
// (515 kcal, 20 g of protein, 50 g of carbs, 25 g of fat per 100 g), a recipe of one serving
// with 100 g of it — 515 kcal a serving — and plans it for TODAY's lunch. « Mangé » then
// copies one serving: Mangé 515, Prévu 0. TODAY is the stack's day, YYYY-MM-DD, never this
// host's clock. Sets output.nutritionMealId, the meal's id, which the flow taps « Mangé » by.
//
// The fast (MAG-770): the profile is set to 16:8 with its window opening at the stack's Paris hour
// minus one, and an apple of 50 kcal is eaten a minute ago — so the window is open whatever the hour
// the run starts at: Mangé 50 before « Mangé », 565 after. « Now » is the stack's, never this host's:
// the `iat` of the token the stack signed (its clock follows E2E_NOW).
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   EMAIL   the journey's user (`${output.journeyEmail}`)
//   TODAY   the stack's day (`${TODAY}`)
//
// Polled, never read once: the planned meal shows in the day's balance once the API has it, and the
// window opens once the API has the apple.

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

// The stack's « now », in milliseconds: the `iat` of the token it just signed.
function base64UrlDecode(text) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'
  let bits = 0
  let value = 0
  let out = ''
  for (let i = 0; i < text.length; i++) {
    const index = alphabet.indexOf(text.charAt(i))
    if (index < 0) continue
    value = (value << 6) | index
    bits += 6
    if (bits >= 8) {
      bits -= 8
      out += String.fromCharCode((value >> bits) & 0xff)
    }
  }
  return out
}
const claims = JSON.parse(base64UrlDecode(token.split('.')[1] || ''))
if (typeof claims.iat !== 'number') {
  fail('the test login\'s token carries no iat to read the stack\'s clock from: ' + JSON.stringify(claims))
}
const nowMs = claims.iat * 1000

// Paris's offset at an instant: +02:00 from the last Sunday of March to the last Sunday of October,
// 01:00 UTC both, +01:00 otherwise — a rule, so no offset is written by hand.
function lastSundayAt1Utc(year, month) {
  const last = new Date(Date.UTC(year, month + 1, 0))
  return Date.UTC(year, month, last.getUTCDate() - last.getUTCDay(), 1)
}
function parisOffsetHours(ms) {
  const year = new Date(ms).getUTCFullYear()
  return ms >= lastSundayAt1Utc(year, 2) && ms < lastSundayAt1Utc(year, 9) ? 2 : 1
}
function two(n) {
  return (n < 10 ? '0' : '') + n
}
function parisIso(ms) {
  const offset = parisOffsetHours(ms)
  const local = new Date(ms + offset * 3600000)
  return local.getUTCFullYear() + '-' + two(local.getUTCMonth() + 1) + '-' + two(local.getUTCDate()) +
    'T' + two(local.getUTCHours()) + ':' + two(local.getUTCMinutes()) + ':' + two(local.getUTCSeconds()) +
    '+' + two(offset) + ':00'
}

const parisHour = new Date(nowMs + parisOffsetHours(nowMs) * 3600000).getUTCHours()
const windowStart = two((parisHour + 23) % 24) + ':00'
const profile = http.request(base + '/api/health/profile', {
  method: 'PATCH',
  headers: { 'Content-Type': 'application/json', Accept: 'application/json', Authorization: 'Bearer ' + token },
  body: JSON.stringify({ fastingProtocol: '16:8', fastingWindowStart: windowStart }),
})
if (!profile.ok) {
  fail('PATCH /api/health/profile answered ' + profile.status + ': ' + profile.body)
}
post('/api/health/food-intakes', { eatenAt: parisIso(nowMs - 60000), label: 'Pomme', kcal: 50 })

const fastDeadline = Date.now() + 30000
for (;;) {
  const response = http.get(base + '/api/health/fasting?date=' + TODAY, {
    headers: { Accept: 'application/json', Authorization: 'Bearer ' + token },
  })
  if (!response.ok) {
    fail('GET /api/health/fasting answered ' + response.status + ': ' + response.body)
  }
  const fasting = JSON.parse(response.body)
  if (fasting.protocol === '16:8' && fasting.state === 'open') {
    break
  }
  if (Date.now() >= fastDeadline) {
    fail('the window never opened within 30 s (start ' + windowStart + '): ' + response.body)
  }
  pause(300)
}

output.nutritionMealId = meal.id
})()
