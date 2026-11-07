// The task a « Programmée » journey schedules, over HTTP (MAG-718).
//
// `create` gives the journey's own user one task with no date at all, titled TITLE, and sets
// output.taskId and output.tomorrow (TODAY + 1 day, YYYY-MM-DD: the day « Demain » picks).
// `expect` polls the task until the API holds it scheduled tomorrow at 18:00 with the default
// reminder « À l'heure » ([0]) — polled, never read once.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION   create | expect
//   EMAIL    the journey's user (`${output.journeyEmail}`)
//   TITLE    the task's title
//   TODAY    the day the phone opens on, YYYY-MM-DD
//   TASK_ID  expect: the task's id

(function () {
const base = E2E_BASE_URL

function fail(message) {
  throw new Error('[task-schedule-api] ' + ACTION + ' "' + TITLE + '": ' + message)
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

// TODAY + 1, computed on the date alone: no zone may move it.
function dayAfter(day) {
  const parts = day.split('-').map(Number)
  const next = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2] + 1))
  return next.toISOString().substring(0, 10)
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
const tomorrow = dayAfter(TODAY)

if (ACTION === 'create') {
  const created = http.post(base + '/api/tasks', {
    headers: LD_HEADERS,
    body: JSON.stringify({ title: TITLE }),
  })
  if (!created.ok) {
    fail('POST /api/tasks answered ' + created.status + ': ' + created.body)
  }
  output.taskId = JSON.parse(created.body).id
  output.tomorrow = tomorrow
} else if (ACTION === 'expect') {
  let last = null
  for (let attempt = 0; attempt < 20; attempt++) {
    const response = http.get(base + '/api/tasks/' + TASK_ID, { headers: JSON_HEADERS })
    if (!response.ok) {
      fail('GET /api/tasks/' + TASK_ID + ' answered ' + response.status + ': ' + response.body)
    }
    last = JSON.parse(response.body)
    const reminders = JSON.stringify(last.reminders || [])
    if (last.scheduledDate === tomorrow && last.scheduledMoment === 'time' && last.scheduledTime === '18:00' && reminders === '[0]') {
      return
    }
    pause(500)
  }
  fail('not scheduled on ' + tomorrow + ' at 18:00 with [0]: ' + JSON.stringify(last))
} else {
  fail('unknown action')
}
})()
