// The task a « Programmée » journey schedules, over HTTP (MAG-718).
//
// `create` gives the journey's own user one task with no date at all, titled TITLE, and sets
// output.taskId and output.tomorrow (TODAY + 1 day, YYYY-MM-DD: the day « Demain » picks).
// `expect` polls the task until the API holds it scheduled tomorrow at 18:00 with the default
// reminder « À l'heure » ([0]) — polled, never read once.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
// MAG-887 adds three actions for the repeat journey (`31b-task-repeat`):
// `create-tuesday` gives one task scheduled on the first Tuesday strictly after TODAY, no rule, and sets
// output.taskId and output.tuesday; `expect-rule` polls until the task holds FREQ=WEEKLY;INTERVAL=2;BYDAY=TU;
// `expect-next` polls until an open task of the same title waits on tuesday + 14 with the same rule, and
// sets output.nextId.
// MAG-1033 adds `expect-rewound`: after « Annuler », polls until one open task only of that title is left,
// on tuesday, with the rule.
//
//   ACTION   create | expect | create-tuesday | expect-rule | expect-next | expect-rewound
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

// The first Tuesday strictly after `day`, and a day plus `n`, on the date alone.
function nextTuesday(day) {
  const parts = day.split('-').map(Number)
  const dow = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2])).getUTCDay()
  const delta = ((2 - dow + 7) % 7) || 7
  return new Date(Date.UTC(parts[0], parts[1] - 1, parts[2] + delta)).toISOString().substring(0, 10)
}

function plusDays(day, n) {
  const parts = day.split('-').map(Number)
  return new Date(Date.UTC(parts[0], parts[1] - 1, parts[2] + n)).toISOString().substring(0, 10)
}

const RULE = 'FREQ=WEEKLY;INTERVAL=2;BYDAY=TU'

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
} else if (ACTION === 'create-tuesday') {
  const tuesday = nextTuesday(TODAY)
  const created = http.post(base + '/api/tasks', {
    headers: LD_HEADERS,
    body: JSON.stringify({ title: TITLE, scheduledDate: tuesday }),
  })
  if (!created.ok) {
    fail('POST /api/tasks answered ' + created.status + ': ' + created.body)
  }
  output.taskId = JSON.parse(created.body).id
  output.tuesday = tuesday
} else if (ACTION === 'expect-rule') {
  let last = null
  for (let attempt = 0; attempt < 20; attempt++) {
    const response = http.get(base + '/api/tasks/' + TASK_ID, { headers: JSON_HEADERS })
    if (!response.ok) {
      fail('GET /api/tasks/' + TASK_ID + ' answered ' + response.status + ': ' + response.body)
    }
    last = JSON.parse(response.body)
    if (last.rrule === RULE) {
      return
    }
    pause(500)
  }
  fail('rrule is not ' + RULE + ': ' + JSON.stringify(last))
} else if (ACTION === 'expect-next') {
  const wanted = plusDays(nextTuesday(TODAY), 14)
  let last = []
  for (let attempt = 0; attempt < 20; attempt++) {
    const response = http.get(base + '/api/tasks?itemsPerPage=100', { headers: LD_HEADERS })
    if (!response.ok) {
      fail('GET /api/tasks answered ' + response.status + ': ' + response.body)
    }
    last = JSON.parse(response.body).member
    const next = last.find(function (task) {
      return task.title === TITLE && !task.completedAt && task.scheduledDate === wanted && task.rrule === RULE
    })
    if (next) {
      output.nextId = next.id
      return
    }
    pause(500)
  }
  fail('no open task on ' + wanted + ' with ' + RULE + ': ' + JSON.stringify(last))
} else if (ACTION === 'expect-rewound') {
  const tuesday = nextTuesday(TODAY)
  let open = []
  for (let attempt = 0; attempt < 20; attempt++) {
    const response = http.get(base + '/api/tasks?itemsPerPage=100', { headers: LD_HEADERS })
    if (!response.ok) {
      fail('GET /api/tasks answered ' + response.status + ': ' + response.body)
    }
    open = JSON.parse(response.body).member.filter(function (task) {
      return task.title === TITLE && !task.completedAt
    })
    if (open.length === 1 && open[0].scheduledDate === tuesday && open[0].rrule === RULE) {
      return
    }
    pause(500)
  }
  fail('not one open task on ' + tuesday + ' with ' + RULE + ': ' + JSON.stringify(open))
} else {
  fail('unknown action')
}
})()
