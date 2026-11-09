// The tasks of « Aujourd'hui », over HTTP (MAG-766).
//
// `create` gives the journey's own user two tasks planned today: « Garage TAG » at 10:00, critical
// enough to be « Haute » (high, due in two days), and « Méditer TAG », this evening, timed, 20 min.
// It sets output.garageId and output.meditateId. `expect-done` polls the garage task until the API
// holds it done — polled, never read once.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION     create | expect-done
//   EMAIL      the journey's user (`${output.journeyEmail}`)
//   TAG        what makes the titles the journey's own
//   TODAY      the day the phone opens on, YYYY-MM-DD
//   TASK_ID    expect-done: the task's id

(function () {
const base = E2E_BASE_URL

function fail(message) {
  throw new Error('[today-tasks-api] ' + ACTION + ': ' + message)
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

// TODAY + days, computed on the date alone: no zone may move it.
function shift(day, days) {
  const parts = day.split('-').map(Number)
  return new Date(Date.UTC(parts[0], parts[1] - 1, parts[2] + days)).toISOString().substring(0, 10)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: EMAIL }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + EMAIL + ': ' + login.body)
}
const token = JSON.parse(login.body).token
const JSON_HEADERS = { Authorization: 'Bearer ' + token, Accept: 'application/json' }
const LD_HEADERS = { Authorization: 'Bearer ' + token, Accept: 'application/ld+json', 'Content-Type': 'application/ld+json' }

function create(task) {
  const response = http.post(base + '/api/tasks', { headers: LD_HEADERS, body: JSON.stringify(task) })
  if (!response.ok) {
    fail('POST /api/tasks answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).id
}

if (ACTION === 'create') {
  output.garageId = create({
    title: 'Garage ' + TAG,
    criticality: 'high',
    dueDate: shift(TODAY, 2),
    scheduledDate: TODAY,
    scheduledMoment: 'time',
    scheduledTime: '10:00',
    reminders: [],
  })
  output.meditateId = create({
    title: 'Méditer ' + TAG,
    scheduledDate: TODAY,
    scheduledMoment: 'evening',
    estimatedMinutes: 20,
    timed: true,
  })
} else if (ACTION === 'expect-done') {
  let last = null
  for (let attempt = 0; attempt < 20; attempt++) {
    const response = http.get(base + '/api/tasks/' + TASK_ID, { headers: JSON_HEADERS })
    if (response.ok) {
      last = JSON.parse(response.body)
      if (last.completedAt) {
        return
      }
    }
    pause(1000)
  }
  fail('task ' + TASK_ID + ' is not done: ' + JSON.stringify(last))
} else {
  fail('unknown action')
}
})()
