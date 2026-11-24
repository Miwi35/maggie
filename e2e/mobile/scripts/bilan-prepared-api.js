// The bilan Maggie prepared, over HTTP (MAG-967).
//
//   setup   « Trier les papiers » and « Ranger les photos », both without a date, and the day's bilan prepared (a status
//           with no `step`): tomorrowTasks = [Trier les papiers], tomorrowSpares = [Ranger les photos].
//           Sets `output.preparedTaskId` and `output.spareTaskId`.
//   expect  after « Bonne nuit »: the spare is planned tomorrow and has no due day, the first is not planned.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  setup | expect
//   TODAY   the seed's anchor day, YYYY-MM-DD
//   EMAIL   the journey's user (`${output.journeyEmail}`)

(function () {
const base = E2E_BASE_URL

function fail(message) {
  throw new Error('[bilan-prepared-api] ' + ACTION + ': ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: EMAIL }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + EMAIL + ': ' + login.body)
}
const token = JSON.parse(login.body).token
const headers = { Authorization: 'Bearer ' + token, Accept: 'application/ld+json', 'Content-Type': 'application/ld+json' }

function tomorrowOf(day) {
  const d = new Date(day + 'T12:00:00Z')
  d.setUTCDate(d.getUTCDate() + 1)
  return d.toISOString().slice(0, 10)
}

function createTask(title) {
  const response = http.post(base + '/api/tasks', { headers: headers, body: JSON.stringify({ title: title }) })
  if (!response.ok) {
    fail('POST /api/tasks answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).id
}

function readTask(id) {
  const response = http.get(base + '/api/tasks/' + id, { headers: headers })
  if (!response.ok) {
    fail('GET /api/tasks/' + id + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

if (ACTION === 'setup') {
  const prepared = createTask('Trier les papiers')
  const spare = createTask('Ranger les photos')
  const response = http.request(base + '/api/activity-status/bilan', {
    method: 'PUT',
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({
      origin: { kind: 'day', id: TODAY },
      state: {
        answers: {},
        instructions: [],
        tomorrowTasks: [{ taskId: prepared, title: 'Trier les papiers' }],
        tomorrowSpares: [{ taskId: spare, title: 'Ranger les photos' }],
      },
    }),
  })
  if (!response.ok) {
    fail('PUT /api/activity-status/bilan answered ' + response.status + ': ' + response.body)
  }
  output.preparedTaskId = prepared
  output.spareTaskId = spare
} else if (ACTION === 'expect') {
  const wanted = tomorrowOf(TODAY)
  const deadline = Date.now() + 20000
  for (;;) {
    const spare = readTask(output.spareTaskId)
    const prepared = readTask(output.preparedTaskId)
    if (spare.scheduledDate === wanted && !spare.dueDate && !prepared.scheduledDate) {
      break
    }
    if (Date.now() > deadline) {
      fail('« Ranger les photos » is planned ' + spare.scheduledDate + ' (due ' + spare.dueDate + '), « Trier les papiers » ' + prepared.scheduledDate + '; expected ' + wanted + ', no due day, and none')
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
