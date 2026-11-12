// The evening review's proaction, come due, over HTTP (MAG-826).
//
// The review only exists as the message Maggie writes when its proaction comes due, so this files the proaction the way
// `proaction-api.js` does (`schedule_proaction`), moves it to an hour before the stack's pinned clock (TODAY, 11:00 UTC)
// as `digest-api.js` does, and waits for the message the executor writes (it claims what is due once a minute). It
// also sets the review time to 00:00, so the Accueil's tile is offered whatever the phone's clock says.
//
// Parameters (flow `env:`, plus E2E_BASE_URL, E2E_LOGIN_TOKEN and TODAY from `run.sh`):
//   EMAIL  the account (the journey's own user)
//
// Sets output.bilanMessageId: the message that carries the `bilan` block.

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'
const PROMPT = "Fais le bilan de la journée avec l'utilisateur (compétence bilan-soir, ouverture par le rendez-vous du soir)."

function fail(message) {
  throw new Error('[bilan-proaction-api] ' + message)
}

function pause(ms) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    // The script sandbox has no timers.
  }
}

function header(response, name) {
  const headers = response.headers || {}
  const key = Object.keys(headers).filter(function (k) {
    return k.toLowerCase() === name.toLowerCase()
  })[0]
  return key ? headers[key] : undefined
}

function payload(body) {
  const line = body.split('\n').filter(function (l) {
    return l.indexOf('data:') === 0
  })[0]
  return JSON.parse(line ? line.slice(5).trim() : body)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + email + ': ' + login.body)
}
const token = JSON.parse(login.body).token

const preferences = http.request(base + '/api/user_preferences/me', {
  method: 'PATCH',
  headers: { Authorization: 'Bearer ' + token, Accept: 'application/json', 'Content-Type': 'application/merge-patch+json' },
  body: JSON.stringify({ eveningReviewEnabled: true, eveningReviewTime: '00:00' }),
})
if (!preferences.ok) {
  fail('PATCH /api/user_preferences/me answered ' + preferences.status + ': ' + preferences.body)
}

const mcp = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', Authorization: 'Bearer ' + token }
const init = http.post(base + '/_mcp', {
  headers: mcp,
  body: JSON.stringify({
    jsonrpc: '2.0',
    id: 1,
    method: 'initialize',
    params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'e2e-mobile', version: '1.0.0' } },
  }),
})
const session = header(init, 'Mcp-Session-Id')
if (!init.ok || !session) {
  fail('the MCP handshake answered ' + init.status + ' without a session id: ' + init.body)
}
const withSession = Object.assign({}, mcp, { 'Mcp-Session-Id': session })
http.post(base + '/_mcp', { headers: withSession, body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) })
const today = TODAY.split('-').map(Number)
const next = new Date(Date.UTC(today[0], today[1] - 1, today[2] + 1)).toISOString().slice(0, 10)
const call = http.post(base + '/_mcp', {
  headers: withSession,
  body: JSON.stringify({
    jsonrpc: '2.0',
    id: 2,
    method: 'tools/call',
    params: { name: 'schedule_proaction', arguments: { prompt: PROMPT, summary: 'Bilan de la journée', scheduled_at: next + 'T21:30:00' } },
  }),
})
if (!call.ok) {
  fail('schedule_proaction answered ' + call.status + ': ' + call.body)
}
const result = JSON.parse(payload(call.body).result.content[0].text)
if (!result.success) {
  fail('schedule_proaction refused: ' + JSON.stringify(result))
}
const moved = http.request(base + '/api/events/' + result.proaction.id, {
  method: 'PATCH',
  headers: { Authorization: 'Bearer ' + token, 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' },
  body: JSON.stringify({ startAt: TODAY + 'T10:00:00Z' }),
})
if (!moved.ok) {
  fail('PATCH /api/events/' + result.proaction.id + ' answered ' + moved.status + ': ' + moved.body)
}

const deadline = Date.now() + 180000
for (;;) {
  const messages = http.get(base + '/agent/messages?limit=20', { headers: { Authorization: 'Bearer ' + token } })
  if (messages.ok) {
    const written = JSON.parse(messages.body).filter(function (message) {
      return message.role === 'assistant' && JSON.stringify(message.blocks || []).indexOf('"bilan"') >= 0
    })[0]
    if (written) {
      output.bilanMessageId = written.id
      break
    }
  }
  if (Date.now() >= deadline) {
    fail('the review message was never written within 180 s')
  }
  pause(2000)
}
})()
