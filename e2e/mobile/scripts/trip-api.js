// A kept trip to an event, played over HTTP (MAG-658).
//
// The agenda's trip line shows what Maggie keeps with `set_trip`, and no REST route does that:
// the only way in is the MCP tool, so this script plays it the way the web journey does
// (`e2e/web/helpers/mcp.ts`): `initialize`, the session id in `Mcp-Session-Id`, `tools/call`.
// Google is WireMock: a walk to the car 4 min, the drive 14, the walk from the dentist's
// parking 3 — leaving at 13:34 for 14:00, at 14:34 for 15:00.
//
// Parameters (flow `env:`, plus E2E_BASE_URL, E2E_LOGIN_TOKEN and TODAY from `run.sh`):
//   ACTION    create | move
//   SUMMARY   create: the event's title
//   EVENT_ID  move: the id `create` returned
//   EMAIL     the account, default the one the app signs in as (e2e@maggie.local)
//
// `create` files the event today at 14:00 (Paris), counted from `TODAY` and not from the
// script's clock so a pinned `E2E_NOW` is followed, parks the car 300 m from home and keeps
// the drive from home. `move` pushes the event to 15:00 and keeps the trip again.

(function () {
const base = E2E_BASE_URL
const email = typeof EMAIL !== 'undefined' && EMAIL ? EMAIL : 'e2e@maggie.local'

function fail(message) {
  throw new Error('[trip-api] ' + ACTION + ': ' + message)
}

const login = http.post(base + '/api/auth/e2e/login', {
  headers: { 'Content-Type': 'application/json', 'X-E2E-Token': E2E_LOGIN_TOKEN },
  body: JSON.stringify({ email: email }),
})
if (!login.ok) {
  fail('the test login answered ' + login.status + ' for ' + email + ': ' + login.body)
}
const token = JSON.parse(login.body).token

const today = typeof TODAY !== 'undefined' && TODAY ? TODAY : new Date().toISOString().slice(0, 10)

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

let callId = 2
function tool(name, args) {
  const call = http.post(base + '/_mcp', {
    headers: withSession,
    body: JSON.stringify({ jsonrpc: '2.0', id: callId++, method: 'tools/call', params: { name: name, arguments: args } }),
  })
  if (!call.ok) {
    fail(name + ' answered ' + call.status + ': ' + call.body)
  }
  const result = JSON.parse(payload(call.body).result.content[0].text)
  if (result.error) {
    fail(name + ' refused: ' + JSON.stringify(result))
  }
  return result
}

function rest(method, path, body) {
  const response = http.request(base + path, {
    method: method,
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    fail(method + ' ' + path + ' answered ' + response.status + ': ' + response.body)
  }
}

function keepTheDriveFromHome(eventId) {
  const home = tool('list_places', {}).places.filter(function (place) {
    return place.isHome
  })[0]
  if (!home) {
    fail('the account has no home place — did the seed run?')
  }
  tool('set_trip', { eventId: eventId, mode: 'drive', origin: 'place:' + home.id })
}

if (ACTION === 'create') {
  const created = tool('create_event', {
    title: SUMMARY,
    start_date: today,
    start_time: '14:00',
    end_date: today,
    end_time: '15:00',
    location: '5 allée du Sourire Fictif, 35000 Rennes',
  })
  // The car is 300 m north of home: far enough to walk to (the 4 min of the first step).
  const parts = today.split('-').map(Number)
  const parkedAt = new Date(Date.UTC(parts[0], parts[1] - 1, parts[2] - 1, 12)).toISOString()
  rest('PUT', '/api/me/car', { latitude: 48.1127, longitude: -1.68, parkedAt: parkedAt })
  keepTheDriveFromHome(created.event.id)
  output.eventId = created.event.id
} else if (ACTION === 'move') {
  tool('update_event', {
    id: EVENT_ID,
    start_date: today,
    start_time: '15:00',
    end_date: today,
    end_time: '16:00',
  })
  keepTheDriveFromHome(EVENT_ID)
} else {
  fail('unknown ACTION')
}
})()
