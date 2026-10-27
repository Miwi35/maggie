// The data side of the cushion share journey, played over HTTP (MAG-669).
//
// The seed gives the phone's cushion share account (69-finance-cushion-share.yaml) 2 000 € of
// capacity a month, a cushion past its critical threshold with 6 000 € missing, and one project,
// « Moto » (1 500 €). At the seeded 60 % share Moto is bought in M1; at 100 % the cushion takes
// everything until it is complete in M2, and Moto is bought in M3. The Gantt draws the month as a
// bar, which Maestro cannot read: this script reads the planning the phone drew from.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  reset | expect-share | expect-purchase
//           reset puts the share back to the seeded 60 %.
//   SHARE   expect-share only: the share the API must hold, once the phone has saved it
//   MONTHS  expect-purchase only: Moto's purchase month, counted from this month (0)

(function () {
const base = E2E_BASE_URL
const email = 'e2e-cushion-share-mobile@maggie.local'
const PROJECT = 'Moto'
const SEEDED_SHARE = 60

function fail(message) {
  throw new Error('[cushion-share-api] ' + ACTION + ': ' + message)
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
  fail('the test login answered ' + login.status + ' — is the e2e seed loaded? (task e2e:seed): ' + login.body)
}
const token = JSON.parse(login.body).token
const headers = { Authorization: 'Bearer ' + token, Accept: 'application/json' }

function read(path) {
  const response = http.get(base + path, { headers: headers })
  if (!response.ok) {
    fail('GET ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

// `YYYY-MM` to a count of months, so two labels subtract.
function monthNumber(label) {
  const parts = label.split('-')
  return parseInt(parts[0], 10) * 12 + parseInt(parts[1], 10) - 1
}

// Polled: the phone's request may still be on its way when the flow gets here.
function until(check, describe) {
  let last = null
  for (let attempt = 0; attempt < 20; attempt++) {
    last = check()
    if (last.ok) return
    pause(500)
  }
  fail(describe(last.value))
}

if (ACTION === 'reset') {
  const response = http.request(base + '/api/finance/cushion-config', {
    method: 'PATCH',
    headers: Object.assign({ 'Content-Type': 'application/json' }, headers),
    body: JSON.stringify({ cushionSharePercent: SEEDED_SHARE }),
  })
  if (!response.ok) {
    fail('PATCH /api/finance/cushion-config answered ' + response.status + ': ' + response.body)
  }
} else if (ACTION === 'expect-share') {
  const expected = parseInt(SHARE, 10)
  until(function () {
    const share = read('/api/finance/cushion-status').cushionSharePercent
    return { ok: share === expected, value: share }
  }, function (share) {
    return 'the cushion share is ' + share + ' %, not ' + expected + ' %'
  })
} else if (ACTION === 'expect-purchase') {
  const expected = parseInt(MONTHS, 10)
  until(function () {
    const planning = read('/api/finance/project-planning')
    const project = (planning.projects || []).find(function (p) { return p.name === PROJECT })
    if (!project) {
      fail('no project « ' + PROJECT + ' » in the planning — is the e2e seed loaded? (task e2e:seed)')
    }
    const at = null === project.purchaseMonth ? null : monthNumber(project.purchaseMonth) - monthNumber(planning.startMonth)
    return { ok: at === expected, value: { at: at, planning: planning } }
  }, function (last) {
    return '« ' + PROJECT + ' » is bought in M' + last.at + ', not M' + expected +
      ' — cushion ' + JSON.stringify(last.planning.cushion) + ', capacity ' + last.planning.netCapacityCents
  })
} else {
  fail('unknown ACTION')
}
})()
