// The routines journey, over HTTP (MAG-511).
//
// `cleanup` deletes the journey's routine (its title, « Sortir les poubelles ») and its ticks;
// the flow runs it first, so a failed run leaves nothing behind, and last. `find` hands the flow
// the id of the routine the app created, as `output.routineId`, and checks what the app sent:
// weekly, on today's weekday, after dinner. `expect-checked` reads it back after the tick: ticked
// today, once in all.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION   cleanup | find | expect-checked
//   TODAY    the seed's anchor day, YYYY-MM-DD: the day the phone opens on
//   EMAIL    the account, default the one the app signs in as (e2e@maggie.local)

(function () {
const base = E2E_BASE_URL
const title = 'Sortir les poubelles'
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'

function fail(message) {
  throw new Error('[routines-api] ' + ACTION + ': ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + email + ': ' + login.body)
}
const HEADERS = { Authorization: 'Bearer ' + JSON.parse(login.body).token, Accept: 'application/json' }

function ours() {
  const response = http.get(base + '/api/routines', { headers: HEADERS })
  if (!response.ok) {
    fail('GET /api/routines answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).member.filter(function (routine) { return routine.title === title })
}

// ISO weekday of TODAY, Monday = 1, read as a calendar date whatever the runner's zone.
function weekdayOf(day) {
  const parts = day.split('-')
  const sunday0 = new Date(Date.UTC(+parts[0], +parts[1] - 1, +parts[2])).getUTCDay()
  return sunday0 === 0 ? 7 : sunday0
}

if (ACTION === 'cleanup') {
  ours().forEach(function (routine) {
    const response = http.request(base + '/api/routines/' + routine.id, { method: 'DELETE', headers: HEADERS })
    if (!response.ok && response.status !== 404) {
      fail('DELETE /api/routines/' + routine.id + ' answered ' + response.status + ': ' + response.body)
    }
  })
} else if (ACTION === 'find') {
  const found = ours()
  if (found.length !== 1) {
    fail(found.length + ' routines titled « ' + title + ' », expected the one the app created')
  }
  const routine = found[0]
  if (routine.recurrence.kind !== 'weekly' || routine.recurrence.weekday !== weekdayOf(TODAY)) {
    fail('the routine repeats ' + JSON.stringify(routine.recurrence) + ', expected weekly on ' + weekdayOf(TODAY) + ' (' + TODAY + ')')
  }
  if (routine.anchor !== 'afterDinner') {
    fail('the routine is anchored on ' + routine.anchor + ', expected afterDinner')
  }
  output.routineId = routine.id
} else if (ACTION === 'expect-checked') {
  const found = ours()
  if (found.length !== 1 || !found[0].checkedToday || found[0].totalCount !== 1) {
    fail('expected one routine ticked today, once in all; read ' + JSON.stringify(found))
  }
} else {
  fail('unknown ACTION')
}
})()
