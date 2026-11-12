// A period of the signed-in account, played over HTTP (MAG-815).
//
// `create` makes an all-day period on the account's "Perso" agenda, from today for DAYS days (the end is
// exclusive, as the API reads it), and hands its id back in `output.periodId`. `expect` reads the periods back
// from the database, which is how the flow proves a deletion or a creation done through the screen happened.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION    create | expect
//   NAME      create: the period's name · expect: the name to look for
//   TODAY     create: `YYYY-MM-DD`, the first day
//   DAYS      create: how many days it lasts, default 5
//   STATE     expect: present | absent
//   EMAIL     the account, default the one the app signs in as (e2e@maggie.local)

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'

function fail(message) {
  throw new Error('[period-api] ' + ACTION + ': ' + message)
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

function get(path) {
  const response = http.get(base + path, { headers: authorized({ Accept: 'application/ld+json' }) })
  if (!response.ok) {
    fail('GET ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

if (ACTION === 'create') {
  const perso = get('/api/agendas').member.filter(function (agenda) {
    return agenda.name === 'Perso'
  })[0]
  if (!perso) {
    fail('the account has no "Perso" agenda — did the seed run?')
  }
  const days = typeof DAYS !== 'undefined' && DAYS ? parseInt(DAYS, 10) : 5
  const end = new Date(TODAY + 'T00:00:00Z')
  end.setUTCDate(end.getUTCDate() + days)
  const created = http.post(base + '/api/periods', {
    headers: authorized({ 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }),
    body: JSON.stringify({
      name: NAME,
      allDay: true,
      startDate: TODAY,
      endDate: end.toISOString().substring(0, 10),
      timeZone: 'Europe/Paris',
      agenda: '/api/agendas/' + perso.id,
    }),
  })
  if (!created.ok) {
    fail('POST /api/periods answered ' + created.status + ': ' + created.body)
  }
  output.periodId = JSON.parse(created.body).id
} else if (ACTION === 'expect') {
  if (STATE !== 'present' && STATE !== 'absent') {
    fail('unknown STATE "' + STATE + '"')
  }
  const found = get('/api/periods').member.filter(function (period) {
    return period.name === NAME
  }).length
  if (STATE === 'present' && found === 0) {
    fail('the period "' + NAME + '" should exist but the API does not list it')
  }
  if (STATE === 'absent' && found > 0) {
    fail('the period "' + NAME + '" should be gone but the API still lists it')
  }
} else {
  fail('unknown ACTION')
}
})()
