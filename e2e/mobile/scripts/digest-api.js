// The morning digest of the signed-in account, played over HTTP (MAG-710).
//
// The digest only exists as a message Maggie sends when its hour comes, so this plays what the web
// journey `agent-digest.spec.ts` plays: the instruction, the planning, the digest's event moved to a
// minute ago, then the wait for the written message on the `digest` screen (the executor claims what
// is due once a minute). It also gives the account an event today, for the Agenda card.
//
// Parameters (flow `env:`, plus E2E_BASE_URL, E2E_LOGIN_TOKEN and TODAY from `run.sh`):
//   SUMMARY  the title of today's event
//   EMAIL    the account (the journey's own user)
//
// Sets output.digestMessageId: the written digest, which the deep link `digest?message=` opens.

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'
const INSTRUCTION = 'Tous les matins à 7 h, fais-moi le digest de ma journée, par écrit et en vocal (digest MAG-709).'

function fail(message) {
  throw new Error('[digest-api] ' + message)
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + email + ': ' + login.body)
}
const token = JSON.parse(login.body).token
const headers = function (extra) {
  return Object.assign({ Authorization: 'Bearer ' + token }, extra || {})
}

const agendas = http.get(base + '/api/agendas', { headers: headers({ Accept: 'application/ld+json' }) })
if (!agendas.ok) {
  fail('GET /api/agendas answered ' + agendas.status + ': ' + agendas.body)
}
const perso = JSON.parse(agendas.body).member.filter(function (agenda) {
  return agenda.name === 'Perso'
})[0]
if (!perso) {
  fail('the account has no "Perso" agenda')
}
const event = http.post(base + '/api/events', {
  headers: headers({ 'Content-Type': 'application/json', Accept: 'application/ld+json' }),
  body: JSON.stringify({
    summary: SUMMARY,
    startAt: TODAY + 'T20:00:00Z',
    endAt: TODAY + 'T21:00:00Z',
    timeZone: 'Europe/Paris',
    agenda: '/api/agendas/' + perso.id,
  }),
})
if (!event.ok) {
  fail('POST /api/events answered ' + event.status + ': ' + event.body)
}

const instruction = http.post(base + '/agent/instructions', {
  headers: headers({ 'Content-Type': 'application/json' }),
  body: JSON.stringify({ content: INSTRUCTION, kind: 'planning' }),
})
if (!instruction.ok) {
  fail('POST /agent/instructions answered ' + instruction.status + ': ' + instruction.body)
}
const planning = http.post(base + '/agent/journal/planning', { headers: headers() })
if (!planning.ok) {
  fail('POST /agent/journal/planning answered ' + planning.status + ': ' + planning.body)
}
const scheduled = JSON.parse(planning.body).scheduled.filter(function (entry) {
  return entry.summary === 'Digest du matin'
})[0]
if (!scheduled || !scheduled.id) {
  fail('the planning did not schedule the digest: ' + planning.body)
}
const moved = http.request(base + '/api/events/' + scheduled.id, {
  method: 'PATCH',
  headers: headers({ 'Content-Type': 'application/merge-patch+json' }),
  body: JSON.stringify({ startAt: new Date(Date.now() - 60000).toISOString() }),
})
if (!moved.ok) {
  fail('PATCH /api/events/' + scheduled.id + ' answered ' + moved.status + ': ' + moved.body)
}

const deadline = Date.now() + 180000
for (;;) {
  const messages = http.get(base + '/agent/messages?limit=20', { headers: headers() })
  if (messages.ok) {
    const written = JSON.parse(messages.body).filter(function (message) {
      return message.role === 'assistant' && message.screen === 'digest' && !message.audio && message.content.indexOf('digest MAG-709') >= 0
    })[0]
    if (written) {
      output.digestMessageId = written.id
      break
    }
  }
  if (Date.now() >= deadline) {
    fail('the digest was never run within 180 s')
  }
  pause(2000)
}
})()
