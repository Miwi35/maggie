// The day of the constellation journey, over HTTP (MAG-509).
//
// `setup` gives the day one task of its own, not done: the seed already gave the account four
// stars this month and seven last month. `expect-stars` reads `/api/stars` back after the tick:
// five this month, twelve in all. `cleanup` deletes the journey's task (its title carries
// MAG-509) and empties the day; `setup` runs it first, so a failed run leaves nothing behind.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  setup | expect-stars | cleanup
//   TODAY   the seed's anchor day, YYYY-MM-DD: the day the phone opens on
//   EMAIL   the account, e2e-stars-mobile@maggie.local

(function () {
const base = E2E_BASE_URL
const marker = 'MAG-509'

function fail(message) {
  throw new Error('[stars-api] ' + ACTION + ': ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: EMAIL }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + EMAIL + ': ' + login.body)
}
const token = JSON.parse(login.body).token
const JSON_HEADERS = { Authorization: 'Bearer ' + token, Accept: 'application/json', 'Content-Type': 'application/json' }

function readDay() {
  const response = http.get(base + '/api/day-plans/' + TODAY, { headers: JSON_HEADERS })
  if (!response.ok) {
    fail('GET /api/day-plans/' + TODAY + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

function writeDay(taskIds) {
  const response = http.request(base + '/api/day-plans/' + TODAY, {
    method: 'PUT',
    headers: JSON_HEADERS,
    body: JSON.stringify({ slots: taskIds.map(function (id) { return { type: 'task', taskId: id } }) }),
  })
  if (!response.ok) {
    fail('PUT /api/day-plans/' + TODAY + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

function cleanup() {
  const day = readDay()
  const ours = day.slots.map(function (slot) { return slot.task }).concat(day.reserve)
    .filter(function (task) { return task.title.indexOf(marker) !== -1 })
  writeDay([])
  ours.forEach(function (task) {
    const response = http.request(base + '/api/tasks/' + task.id, { method: 'DELETE', headers: JSON_HEADERS })
    if (!response.ok && response.status !== 404) {
      fail('DELETE /api/tasks/' + task.id + ' answered ' + response.status + ': ' + response.body)
    }
  })
}

if (ACTION === 'setup') {
  cleanup()
  const created = http.post(base + '/api/tasks', {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/ld+json', 'Content-Type': 'application/ld+json' },
    body: JSON.stringify({ title: 'Étoile ' + marker }),
  })
  if (!created.ok) {
    fail('POST /api/tasks answered ' + created.status + ': ' + created.body)
  }
  const day = writeDay([JSON.parse(created.body).id])
  if (day.total !== 1) {
    fail('the day holds ' + day.total + ' slots after the PUT, expected 1')
  }
} else if (ACTION === 'expect-stars') {
  const response = http.get(base + '/api/stars', { headers: JSON_HEADERS })
  if (!response.ok) {
    fail('GET /api/stars answered ' + response.status + ': ' + response.body)
  }
  const stars = JSON.parse(response.body)
  if (stars.count !== 5 || stars.total !== 12) {
    fail('expected 5 stars this month and 12 in all, got ' + response.body)
  }
} else if (ACTION === 'cleanup') {
  cleanup()
} else {
  fail('unknown action')
}
})()
