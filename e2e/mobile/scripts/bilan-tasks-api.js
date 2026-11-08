// The tasks of the Bilan de la journée journey, over HTTP (MAG-752).
//
//   setup   one task due today for the journey's user: `output.taskId`
//   expect  the task, read back, is due tomorrow (`EXPECT: tomorrow`) or has no due day (`EXPECT: later`)
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  setup | expect
//   TODAY   the seed's anchor day, YYYY-MM-DD
//   EMAIL   the journey's user (`${output.journeyEmail}`)
//   TASK_ID (expect) the id `setup` gave
//   EXPECT  (expect) tomorrow | later

(function () {
const base = E2E_BASE_URL

function fail(message) {
  throw new Error('[bilan-tasks-api] ' + ACTION + ': ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: EMAIL }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + EMAIL + ': ' + login.body)
}
const headers = { Authorization: 'Bearer ' + JSON.parse(login.body).token, Accept: 'application/ld+json', 'Content-Type': 'application/ld+json' }

function tomorrowOf(day) {
  const d = new Date(day + 'T12:00:00Z')
  d.setUTCDate(d.getUTCDate() + 1)
  return d.toISOString().slice(0, 10)
}

if (ACTION === 'setup') {
  const response = http.post(base + '/api/tasks', { headers: headers, body: JSON.stringify({ title: 'Appeler le garage MAG-752', dueDate: TODAY }) })
  if (!response.ok) {
    fail('POST /api/tasks answered ' + response.status + ': ' + response.body)
  }
  output.taskId = JSON.parse(response.body).id
} else if (ACTION === 'expect') {
  const wanted = EXPECT === 'tomorrow' ? tomorrowOf(TODAY) : null
  const deadline = Date.now() + 20000
  for (;;) {
    const response = http.get(base + '/api/tasks/' + TASK_ID, { headers: headers })
    if (!response.ok) {
      fail('GET /api/tasks/' + TASK_ID + ' answered ' + response.status + ': ' + response.body)
    }
    const due = JSON.parse(response.body).dueDate || null
    if (due === wanted) {
      break
    }
    if (Date.now() > deadline) {
      fail('the task is due ' + due + ', expected ' + wanted)
    }
    const end = Date.now() + 1000
    while (Date.now() < end) {
      // The script sandbox has no timers.
    }
  }
} else {
  fail('unknown action')
}
})()
