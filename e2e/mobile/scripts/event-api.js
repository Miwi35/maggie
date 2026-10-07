// An event of the signed-in account, played over HTTP (MAG-310).
//
// The overlay journey needs something for Maggie to delete: `create` makes an
// event on the account's "Perso" agenda, far in the future so it cannot appear in
// any other flow's agenda, and hands its id back in `output.eventId` — the fake
// model's scenario (`73-delete-event-held.yaml`) reads the id from the sentence.
// `expect` reads it back from the database, which is how the flow proves the
// deletion really happened once the card is gone from the screen.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION    create | expect
//   SUMMARY   create: the event's title
//   EVENT_ID  expect: the id `create` returned
//   STATE     expect: present | absent
//   EMAIL     the account, default the one the app signs in as (e2e@maggie.local)

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'

function fail(message) {
  throw new Error('[event-api] ' + ACTION + ': ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + email + ': ' + login.body)
}
const token = JSON.parse(login.body).token
const authorized = function (extra) {
  return Object.assign({ Authorization: 'Bearer ' + token }, extra || {})
}

if (ACTION === 'create') {
  const agendas = http.get(base + '/api/agendas', {
    headers: authorized({ Accept: 'application/ld+json' }),
  })
  if (!agendas.ok) {
    fail('GET /api/agendas answered ' + agendas.status + ': ' + agendas.body)
  }
  const perso = JSON.parse(agendas.body).member.filter(function (agenda) {
    return agenda.name === 'Perso'
  })[0]
  if (!perso) {
    fail('the account has no "Perso" agenda — did the seed run?')
  }
  const created = http.post(base + '/api/events', {
    headers: authorized({ 'Content-Type': 'application/json', Accept: 'application/ld+json' }),
    body: JSON.stringify({
      summary: SUMMARY,
      startAt: '2099-01-05T10:00:00+01:00',
      endAt: '2099-01-05T11:00:00+01:00',
      timeZone: 'Europe/Paris',
      agenda: '/api/agendas/' + perso.id,
    }),
  })
  if (!created.ok) {
    fail('POST /api/events answered ' + created.status + ': ' + created.body)
  }
  output.eventId = JSON.parse(created.body).id
} else if (ACTION === 'expect') {
  const response = http.get(base + '/api/events/' + EVENT_ID, {
    headers: authorized({ Accept: 'application/ld+json' }),
  })
  if (STATE === 'present' && response.status !== 200) {
    fail('the event should exist but GET answered ' + response.status)
  }
  if (STATE === 'absent' && response.status !== 404) {
    fail('the event should be gone but GET answered ' + response.status)
  }
  if (STATE !== 'present' && STATE !== 'absent') {
    fail('unknown STATE "' + STATE + '"')
  }
} else {
  fail('unknown ACTION')
}
})()
