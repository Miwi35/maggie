// The evening of the tasks journey, over HTTP (MAG-500).
//
// `setup` gives the day five tasks of its own, three of them done — « 3 sur 5 » — and moves the
// evening time to 00:00, so the review is due at any hour the phone runs at. It tells the flow in
// `output.reviewed` whether today's review is already done: a retry on the same stack meets it
// done, there is no undoing « Bonne nuit », and only checks that it does not open again.
// `expect-reviewed` reads the review back: done, with « Commencer petit » only. `cleanup` deletes
// every task of the journey (its titles carry MAG-500), empties the day and puts the evening back
// at 21:00; `setup` runs it first, so a failed run leaves nothing behind for the next.
//
// Everything is read from plain JSON endpoints backed by the database: nothing waits on
// Elasticsearch.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  setup | expect-reviewed | cleanup
//   TODAY   the seed's anchor day, YYYY-MM-DD: the day the phone opens on
//   EMAIL   the account, default the one the app signs in as (e2e@maggie.local)

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'
const marker = 'MAG-500'

function fail(message) {
  throw new Error('[day-review-api] ' + ACTION + ': ' + message)
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

function read(path) {
  const response = http.get(base + path, { headers: JSON_HEADERS })
  if (!response.ok) {
    fail('GET ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

function send(method, path, body, contentType) {
  const headers = { Authorization: 'Bearer ' + token, Accept: 'application/json', 'Content-Type': contentType || 'application/json' }
  const response = http.request(base + path, { method: method, headers: headers, body: JSON.stringify(body) })
  if (!response.ok) {
    fail(method + ' ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

function writeDay(taskIds) {
  return send('PUT', '/api/day-plans/' + TODAY, { slots: taskIds.map(function (id) { return { type: 'task', taskId: id } }) })
}

function createTask(title) {
  const response = http.post(base + '/api/tasks', {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/ld+json', 'Content-Type': 'application/ld+json' },
    body: JSON.stringify({ title: title }),
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

function cleanup() {
  // The done tasks leave the day once it is emptied: they are found in « Faites » as well.
  const day = read('/api/day-plans/' + TODAY)
  const done = read('/api/tasks/view/done?itemsPerPage=100').member
  const ours = day.slots.map(function (slot) { return slot.task }).concat(day.reserve, done)
    .filter(function (task) { return task.title.indexOf(marker) !== -1 })
  writeDay([])
  ours.forEach(function (task) {
    const response = http.request(base + '/api/tasks/' + task.id, { method: 'DELETE', headers: JSON_HEADERS })
    if (!response.ok && response.status !== 404) {
      fail('DELETE /api/tasks/' + task.id + ' answered ' + response.status + ': ' + response.body)
    }
  })
  send('PUT', '/api/task-settings/me', { eveningTime: '21:00' })
}

if (ACTION === 'setup') {
  cleanup()
  const ids = [1, 2, 3, 4, 5].map(function (n) { return createTask('Soir ' + n + ' ' + marker) })
  writeDay(ids)
  ids.slice(0, 3).forEach(function (id) {
    send('PATCH', '/api/tasks/' + id, { completedAt: TODAY + 'T08:00:00+00:00' }, 'application/merge-patch+json')
  })
  const day = read('/api/day-plans/' + TODAY)
  if (day.total !== 5 || day.done !== 3) {
    fail('the day is ' + day.done + ' sur ' + day.total + ' after setup, expected 3 sur 5')
  }
  send('PUT', '/api/task-settings/me', { eveningTime: '00:00' })
  output.reviewed = read('/api/day-reviews/' + TODAY).reviewedAt !== null
} else if (ACTION === 'expect-reviewed') {
  // Polled: the phone writes the review, then reads the day again, while this runs.
  const deadline = Date.now() + 20000
  for (;;) {
    const review = read('/api/day-reviews/' + TODAY)
    let problem = null
    if (review.reviewedAt === null) {
      problem = 'the review of ' + TODAY + ' is not done'
    } else if (JSON.stringify(review.helped) !== JSON.stringify(['start_small'])) {
      problem = 'the review holds ' + JSON.stringify(review.helped) + ', expected ["start_small"]'
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
