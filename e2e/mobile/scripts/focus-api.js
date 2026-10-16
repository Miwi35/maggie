// The Focus journey, over HTTP (MAG-499).
//
// `setup` gives today's day two tasks of its own: « Focus 1 » with a first step and two subtasks
// (the petits pas), « Focus 2 » after it. `expect-done` reads the day back: « Focus 1 » is done, both
// its petits pas are as the phone left them, and « Maintenant » is « Focus 2 ». `cleanup` deletes every
// task of the journey (titles carry MAG-499; the petits pas go with their parent, by cascade) and empties the day; `setup` runs it first.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  setup | expect-done | cleanup
//   TODAY   the seed's anchor day, YYYY-MM-DD
//   EMAIL   the account (default e2e@maggie.local)

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'
const marker = 'MAG-499'

function fail(message) {
  throw new Error('[focus-api] ' + ACTION + ': ' + message)
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
const LD_HEADERS = { Authorization: 'Bearer ' + token, Accept: 'application/ld+json', 'Content-Type': 'application/ld+json' }

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

function createTask(fields) {
  const response = http.post(base + '/api/tasks', { headers: LD_HEADERS, body: JSON.stringify(fields) })
  if (!response.ok) {
    fail('POST /api/tasks answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
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

function remove(id) {
  const response = http.request(base + '/api/tasks/' + id, { method: 'DELETE', headers: JSON_HEADERS })
  if (!response.ok && response.status !== 404) {
    fail('DELETE /api/tasks/' + id + ' answered ' + response.status + ': ' + response.body)
  }
}

function cleanup() {
  writeDay([])
  tasksOf(readDay())
    .filter(function (task) { return task.title.indexOf(marker) !== -1 })
    .forEach(function (task) { remove(task.id) })
}

if (ACTION === 'setup') {
  cleanup()
  const first = createTask({ title: 'Focus 1 ' + marker, firstStep: 'Ouvrir le courrier' })
  createTask({ title: 'Pas A ' + marker, parent: '/api/tasks/' + first.id })
  createTask({ title: 'Pas B ' + marker, parent: '/api/tasks/' + first.id })
  const second = createTask({ title: 'Focus 2 ' + marker })
  const day = writeDay([first.id, second.id])
  if (day.total !== 2) {
    fail('the day holds ' + day.total + ' slots after the PUT, expected 2')
  }
} else if (ACTION === 'expect-done') {
  // Polled: the phone's last call may still be in flight.
  const deadline = Date.now() + 20000
  for (;;) {
    const day = readDay()
    const first = day.slots.filter(function (slot) { return slot.task.title === 'Focus 1 ' + marker })[0]
    let problem = null
    if (!first) {
      problem = '"Focus 1" lost its slot'
    } else if (!first.task.completedAt) {
      problem = '"Focus 1" is not done'
    } else if (!day.now || day.now.task.title !== 'Focus 2 ' + marker) {
      problem = '« Maintenant » is ' + (day.now ? day.now.task.title : 'empty') + ', expected "Focus 2"'
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
