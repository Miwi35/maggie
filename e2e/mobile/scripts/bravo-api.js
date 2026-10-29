// The day of the bravo journey, over HTTP (MAG-508).
//
// `setup` gives the day five tasks of its own, the first two done, so a tick from « Mes 5 »
// is the third of five; the fourth has its minutes, for the day's next task. `expect-reward`
// reads back the reward the tick laid down: one star, a message. `cleanup` deletes every task of
// the journey (its titles carry MAG-508) and empties the day; `setup` runs it first, so a failed
// run leaves nothing behind for the next.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  setup | expect-reward | cleanup
//   TODAY   the seed's anchor day, YYYY-MM-DD: the day the phone opens on
//   EMAIL   the account, default the one the app signs in as (e2e@maggie.local)

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'
const marker = 'MAG-508'

function fail(message) {
  throw new Error('[bravo-api] ' + ACTION + ': ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + email + ': ' + login.body)
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

function createTask(title, estimatedMinutes) {
  const response = http.post(base + '/api/tasks', {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/ld+json', 'Content-Type': 'application/ld+json' },
    body: JSON.stringify({ title: title, estimatedMinutes: estimatedMinutes }),
  })
  if (!response.ok) {
    fail('POST /api/tasks answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).id
}

function tick(id) {
  const response = http.request(base + '/api/tasks/' + id, {
    method: 'PATCH',
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/ld+json', 'Content-Type': 'application/merge-patch+json' },
    body: JSON.stringify({ completedAt: new Date().toISOString() }),
  })
  if (!response.ok) {
    fail('PATCH /api/tasks/' + id + ' answered ' + response.status + ': ' + response.body)
  }
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
  const ids = [1, 2, 3, 4, 5].map(function (n) { return createTask('Bravo ' + n + ' ' + marker, n === 4 ? 15 : null) })
  const day = writeDay(ids)
  if (day.total !== 5) {
    fail('the day holds ' + day.total + ' slots after the PUT, expected 5')
  }
  tick(ids[0])
  tick(ids[1])
} else if (ACTION === 'expect-reward') {
  const third = readDay().slots[2]
  if (!third || third.task.completedAt === null) {
    fail('the third task of the day is not done')
  }
  const response = http.get(base + '/api/rewards/task/' + third.task.id, { headers: JSON_HEADERS })
  if (!response.ok) {
    fail('GET /api/rewards/task/' + third.task.id + ' answered ' + response.status + ': ' + response.body)
  }
  const reward = JSON.parse(response.body)
  if (reward.star !== true || !reward.message) {
    fail('the reward is not a star with a message: ' + response.body)
  }
} else if (ACTION === 'cleanup') {
  cleanup()
} else {
  fail('unknown action')
}
})()
