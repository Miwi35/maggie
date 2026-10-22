// The data side of the projects journey, played over HTTP (MAG-519).
//
// The seed gives the main e2e user two projects: « Déménagement » (position 0, a fixed date) and
// « Canapé » (position 1, no date, waiting for the move). The journey drags « Canapé » to the top
// and gives it a date limite on the phone; this script puts the seed back before and after, and
// reads what the API stored in between — the reorder is a custom controller no contract records,
// so this is where the phone's request meets the server's answer.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  reset | expect-order | expect-deadline | fund-car | reset-car
//           The last two are the funding journey (MAG-525), on the stock user's « Voiture ».
//   ORDER   expect-order only: the names expected first, `|`-separated (« Canapé|Déménagement »)

(function () {
const base = E2E_BASE_URL
const FUNDING = ACTION === 'fund-car' || ACTION === 'reset-car'
const email = FUNDING ? 'e2e-stock@maggie.local' : 'e2e@maggie.local'
const CAR = 'Voiture'
const SAVINGS_ACCOUNT = 'Livret jeune'
const MOVE = 'Déménagement'
const SOFA = 'Canapé'

function fail(message) {
  throw new Error('[projects-api] ' + ACTION + ': ' + message)
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
  fail('the test login answered ' + login.status + ': ' + login.body)
}
const token = JSON.parse(login.body).token
const authorized = function (extra) {
  return Object.assign({ Authorization: 'Bearer ' + token }, extra || {})
}

function projects() {
  const response = http.get(base + '/api/finance/projects?pagination=false', {
    headers: authorized({ Accept: 'application/ld+json' }),
  })
  if (!response.ok) {
    fail('GET /api/finance/projects answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).member
}

function named(list, name) {
  const found = list.find(function (project) { return project.name === name })
  if (!found) {
    fail('no project « ' + name + ' » — is the e2e seed loaded? (task e2e:seed)')
  }
  return found
}

// Polled: the phone's request may still be on its way when the flow gets here.
function until(check, what) {
  let last = null
  for (let attempt = 0; attempt < 20; attempt++) {
    last = projects()
    if (check(last)) return last
    pause(500)
  }
  fail(what + ' — the API holds: ' + last.map(function (p) { return p.position + '. ' + p.name + ' (' + p.dateMode + ')' }).join(', '))
}

function putFunding(project, sources) {
  const response = http.request(base + '/api/finance/projects/' + project.id + '/funding', {
    method: 'PUT',
    headers: authorized({ 'Content-Type': 'application/json', Accept: 'application/json' }),
    body: JSON.stringify({ sources: sources }),
  })
  if (!response.ok) {
    fail('PUT funding of « ' + project.name + ' » answered ' + response.status + ': ' + response.body)
  }
}

if (ACTION === 'fund-car') {
  // 1 000 € of savings and 6 000 € borrowed over 24 months at 4 %: 3 000 € are left on the salary.
  const accounts = http.get(base + '/api/accounts?pagination=false', { headers: authorized({ Accept: 'application/ld+json' }) })
  if (!accounts.ok) {
    fail('GET /api/accounts answered ' + accounts.status + ': ' + accounts.body)
  }
  const livret = JSON.parse(accounts.body).member.find(function (account) { return account.name === SAVINGS_ACCOUNT })
  if (!livret) {
    fail('no account « ' + SAVINGS_ACCOUNT + ' » — is the e2e seed loaded? (task e2e:seed)')
  }
  putFunding(named(projects(), CAR), [
    { kind: 'savings', amountCents: 100000, account: livret.id },
    { kind: 'loan', amountCents: 600000, loan: { annualRateBasisPoints: 400, durationMonths: 24 } },
  ])
} else if (ACTION === 'reset-car') {
  putFunding(named(projects(), CAR), [])
} else if (ACTION === 'reset') {
  const list = projects()
  const move = named(list, MOVE)
  const sofa = named(list, SOFA)

  const patch = http.request(base + '/api/finance/projects/' + sofa.id, {
    method: 'PATCH',
    headers: authorized({ 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' }),
    body: JSON.stringify({ dateMode: 'none', targetOn: null }),
  })
  if (!patch.ok) {
    fail('PATCH « ' + SOFA + ' » answered ' + patch.status + ': ' + patch.body)
  }

  const others = list
    .filter(function (p) { return p.id !== move.id && p.id !== sofa.id })
    .sort(function (a, b) { return a.position - b.position })
    .map(function (p) { return p.id })
  const reorder = http.post(base + '/api/finance/projects/reorder', {
    headers: authorized({ 'Content-Type': 'application/json', Accept: 'application/json' }),
    body: JSON.stringify({ ids: [move.id, sofa.id].concat(others) }),
  })
  if (!reorder.ok) {
    fail('POST /api/finance/projects/reorder answered ' + reorder.status + ': ' + reorder.body)
  }
} else if (ACTION === 'expect-order') {
  const expected = ORDER.split('|')
  until(function (list) {
    const sorted = list.slice().sort(function (a, b) { return a.position - b.position })
    return expected.every(function (name, index) { return sorted[index] && sorted[index].name === name })
  }, 'the order is not ' + expected.join(', '))
} else if (ACTION === 'expect-deadline') {
  until(function (list) {
    const sofa = named(list, SOFA)
    return sofa.dateMode === 'deadline' && !!sofa.targetOn
  }, '« ' + SOFA + ' » has no date limite')
} else {
  fail('unknown ACTION')
}
})()
