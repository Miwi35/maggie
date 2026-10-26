// The three tasks of the day of the Accueil's Tâches card, over HTTP (MAG-561).
//
// `setup` gives the account three tasks due today, none done. `expect-done` reads the first one
// back from the API (by id, found through the « today » task view: the tasks collection is served
// from an index that lags). `cleanup` deletes the tasks of the day; the account holds nothing else,
// and `setup` runs it first, so a failed run leaves nothing behind.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  setup | expect-done | cleanup
//   TODAY   the seed's anchor day, YYYY-MM-DD: the day the phone opens on
//   EMAIL   the account, e2e-home-tasks-mobile@maggie.local

(function () {
const base = E2E_BASE_URL
const titles = ['Appeler le garagiste', 'Renvoyer le formulaire', 'Arroser le basilic']

function fail(message) {
  throw new Error('[home-tasks-api] ' + ACTION + ': ' + message)
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
const LD_HEADERS = { Authorization: 'Bearer ' + token, Accept: 'application/ld+json', 'Content-Type': 'application/ld+json' }

function cardTasks() {
  const response = http.get(base + '/api/tasks/view/today', { headers: LD_HEADERS })
  if (!response.ok) {
    fail('GET /api/tasks/view/today answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).member
}

function cleanup() {
  cardTasks().forEach(function (task) {
    const response = http.request(base + '/api/tasks/' + task.id, { method: 'DELETE', headers: JSON_HEADERS })
    if (!response.ok && response.status !== 404) {
      fail('DELETE /api/tasks/' + task.id + ' answered ' + response.status + ': ' + response.body)
    }
  })
}

if (ACTION === 'setup') {
  cleanup()
  titles.forEach(function (title) {
    const created = http.post(base + '/api/tasks', {
      headers: LD_HEADERS,
      body: JSON.stringify({ title: title, dueDate: TODAY }),
    })
    if (!created.ok) {
      fail('POST /api/tasks answered ' + created.status + ': ' + created.body)
    }
  })
} else if (ACTION === 'expect-done') {
  const first = cardTasks().filter(function (task) { return task.title === titles[0] })[0]
  if (!first) {
    fail('« ' + titles[0] + ' » is not on the card')
  }
  const response = http.get(base + '/api/tasks/' + first.id, { headers: JSON_HEADERS })
  if (!response.ok) {
    fail('GET /api/tasks/' + first.id + ' answered ' + response.status + ': ' + response.body)
  }
  if (!JSON.parse(response.body).completedAt) {
    fail('« ' + titles[0] + ' » is not done: ' + response.body)
  }
} else if (ACTION === 'cleanup') {
  cleanup()
} else {
  fail('unknown action')
}
})()
