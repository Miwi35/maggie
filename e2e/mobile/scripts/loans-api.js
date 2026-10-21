// The data side of the loans journey, played over HTTP (MAG-523).
//
// The seed gives the stock user one loan, « Prêt immo » (98 000 € at 1,80 %, 625 €/month), whose early
// repayment penalty is `exempt`. The journey needs it at the legal cap to see an IRA on the sheet, so
// this script sets it before and puts the seed back after: a run that failed half-way is taken as it
// was left.
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  legal-cap | reset

(function () {
const base = E2E_BASE_URL
const email = 'e2e-stock@maggie.local'
const LOAN = 'Prêt immo'

function fail(message) {
  throw new Error('[loans-api] ' + ACTION + ': ' + message)
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

const list = http.get(base + '/api/finance/loans', {
  headers: authorized({ Accept: 'application/ld+json' }),
})
if (!list.ok) {
  fail('GET /api/finance/loans answered ' + list.status + ': ' + list.body)
}
const loan = JSON.parse(list.body).member.find(function (item) { return item.name === LOAN })
if (!loan) {
  fail('no loan « ' + LOAN + ' » — is the e2e seed loaded? (task e2e:seed)')
}

let mode
if (ACTION === 'legal-cap') {
  mode = 'legal_cap'
} else if (ACTION === 'reset') {
  mode = 'exempt'
} else {
  fail('unknown ACTION')
}

const patch = http.request(base + '/api/finance/loans/' + loan.id, {
  method: 'PATCH',
  headers: authorized({ 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' }),
  body: JSON.stringify({ prepaymentPenaltyMode: mode }),
})
if (!patch.ok) {
  fail('PATCH « ' + LOAN + ' » answered ' + patch.status + ': ' + patch.body)
}
})()
