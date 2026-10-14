// A proaction of Maggie, played over HTTP (MAG-328).
//
// No REST route creates a proaction: the only way in is the MCP tool
// `schedule_proaction`, which is how Maggie files one. So `create` plays that tool the way
// the web journey does (`e2e/web/helpers/mcp.ts`): `initialize`, the session id the answer
// carries in `Mcp-Session-Id`, then `tools/call`. `expect` reads the collection the app and
// the admin read, which is how the flow proves the deletion happened on the server.
//
// Parameters (flow `env:`, plus E2E_BASE_URL, E2E_LOGIN_TOKEN and TODAY from `run.sh`):
//   ACTION   create | expect
//   SUMMARY  the proaction's label in the agenda
//   STATE    expect: present | absent
//   EMAIL    the account, default the one the app signs in as (e2e@maggie.local)
//
// `create` schedules tomorrow at 08:30 (Paris), counted from `TODAY` and not from the
// script's clock, so a pinned `E2E_NOW` is followed.

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'

function fail(message) {
  throw new Error('[proaction-api] ' + ACTION + ': ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + email + ': ' + login.body)
}
const token = JSON.parse(login.body).token

function tomorrow() {
  const today = typeof TODAY !== 'undefined' && TODAY ? TODAY : new Date().toISOString().slice(0, 10)
  const parts = today.split('-').map(Number)
  const next = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2] + 1))
  const pad = function (n) {
    return (n < 10 ? '0' : '') + n
  }
  return next.getUTCFullYear() + '-' + pad(next.getUTCMonth() + 1) + '-' + pad(next.getUTCDate())
}

function header(response, name) {
  const headers = response.headers || {}
  const key = Object.keys(headers).filter(function (k) {
    return k.toLowerCase() === name.toLowerCase()
  })[0]
  return key ? headers[key] : undefined
}

// The transport answers JSON or a server-sent event: take the JSON either way.
function payload(body) {
  const line = body.split('\n').filter(function (l) {
    return l.indexOf('data:') === 0
  })[0]
  return JSON.parse(line ? line.slice(5).trim() : body)
}

if (ACTION === 'create') {
  const mcp = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/event-stream',
    Authorization: 'Bearer ' + token,
  }
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
  http.post(base + '/_mcp', {
    headers: withSession,
    body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
  })
  const call = http.post(base + '/_mcp', {
    headers: withSession,
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 2,
      method: 'tools/call',
      params: {
        name: 'schedule_proaction',
        arguments: {
          prompt: 'Rappelle à Meven son rendez-vous de demain matin.',
          summary: SUMMARY,
          scheduled_at: tomorrow() + 'T08:30:00',
        },
      },
    }),
  })
  if (!call.ok) {
    fail('schedule_proaction answered ' + call.status + ': ' + call.body)
  }
  const result = JSON.parse(payload(call.body).result.content[0].text)
  if (!result.success) {
    fail('schedule_proaction refused: ' + JSON.stringify(result))
  }
  output.proactionId = result.proaction.id
} else if (ACTION === 'expect') {
  const list = http.get(base + '/api/proactions?pagination=false', {
    headers: { Authorization: 'Bearer ' + token, Accept: 'application/ld+json' },
  })
  if (!list.ok) {
    fail('GET /api/proactions answered ' + list.status + ': ' + list.body)
  }
  const present = JSON.parse(list.body).member.some(function (p) {
    return p.summary === SUMMARY
  })
  if (present !== (STATE === 'present')) {
    fail('"' + SUMMARY + '" is ' + (present ? 'still there' : 'missing') + ', expected ' + STATE)
  }
} else {
  fail('unknown action')
}
})()
