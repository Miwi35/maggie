// The day of the tasks journey, over HTTP (MAG-497).
//
// `setup` gives the day five tasks of its own — the five slots taken, so a task added from the
// phone has to wait in the reserve — and leaves a sixth in the reserve, due three days before
// TODAY, so it reads « reportée ». `expect-reserve` reads the day back: the five still hold their
// slots and TITLE waits in the reserve. `cleanup` deletes every task of the journey (its titles
// carry MAG-497) and empties the day; `setup` runs it first, so a failed run leaves nothing
// behind for the next.
//
// Everything is read from `/api/day-plans/{day}`, plain JSON from the database: no collection,
// so nothing waits on Elasticsearch.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  setup | expect-reserve | cleanup
//   TODAY   the seed's anchor day, YYYY-MM-DD: the day the phone opens on
//   TITLE   expect-reserve: the title expected in the reserve
//   EMAIL   the account, default the one the app signs in as (e2e@maggie.local)

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'
const marker = 'MAG-497'

function fail(message) {
  throw new Error('[day-plan-api] ' + ACTION + ': ' + message)
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

function shift(day, days) {
  const date = new Date(day + 'T12:00:00Z')
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().substring(0, 10)
}

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

function createTask(title, dueDate) {
  const response = http.post(base + '/api/tasks', {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/ld+json', 'Content-Type': 'application/ld+json' },
    body: JSON.stringify({ title: title, dueDate: dueDate }),
  })
  if (!response.ok) {
    fail('POST /api/tasks answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).id
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

function tasksOf(day) {
  return day.slots.map(function (slot) { return slot.task }).concat(day.reserve)
}

function cleanup() {
  writeDay([])
  tasksOf(readDay())
    .filter(function (task) { return task.title.indexOf(marker) !== -1 })
    .forEach(function (task) {
      const response = http.request(base + '/api/tasks/' + task.id, { method: 'DELETE', headers: JSON_HEADERS })
      if (!response.ok && response.status !== 404) {
        fail('DELETE /api/tasks/' + task.id + ' answered ' + response.status + ': ' + response.body)
      }
    })
}

if (ACTION === 'setup') {
  cleanup()
  const ids = [1, 2, 3, 4, 5].map(function (n) { return createTask('Jour ' + n + ' ' + marker, null) })
  createTask('Passée ' + marker, shift(TODAY, -3))
  const day = writeDay(ids)
  if (day.total !== 5) {
    fail('the day holds ' + day.total + ' slots after the PUT, expected 5')
  }
} else if (ACTION === 'expect-reserve') {
  // Polled: the phone creates the task, then reads the day again, while this runs.
  const deadline = Date.now() + 20000
  for (;;) {
    const day = readDay()
    const slotted = day.slots.map(function (slot) { return slot.task.title })
    const waiting = day.reserve.filter(function (task) { return task.title === TITLE })
    let problem = null
    if (day.total !== 5) {
      problem = 'the day holds ' + day.total + ' slots (' + slotted.join(', ') + '), expected the five of setup'
    } else if (slotted.indexOf(TITLE) !== -1) {
      problem = '"' + TITLE + '" took a slot of a full day'
    } else if (waiting.length !== 1) {
      problem = '"' + TITLE + '" is not in the reserve: ' + day.reserve.map(function (task) { return task.title }).join(', ')
    }
    if (problem === null) {
      break
    }
    if (Date.now() > deadline) {
      fail(problem)
    }
    pause(1000)
  }
} else if (ACTION === 'cleanup') {
  cleanup()
} else {
  fail('unknown action')
}
})()
