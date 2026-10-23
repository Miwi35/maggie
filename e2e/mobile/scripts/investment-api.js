// The data side of the investment journey, played over HTTP (MAG-557).
//
// The e2e seed has no investment, and the API alone writes one (the phone only reads it): this script
// puts a rented box on the stock user — « Box à louer », 18 000 €, 3 000 € of down payment, the rest
// borrowed over 20 years at 3 %, 650 € of rent a month, resold in 30 months for 4 000 € net to pay the
// down payment of « Studio » — and takes both projects away afterwards. Both names are the journey's
// own: a project with one of them is taken to be a leftover of an earlier run and deleted first, so a
// run that failed half-way is taken as it was left. Nothing else of the stock user is touched
// (« Voiture » is the funding journey's: 23-finance-projects).
//
// Parameters (flow `env:`, plus E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  setup | cleanup

(function () {
const base = E2E_BASE_URL
const email = 'e2e-stock@maggie.local'
const BOX = 'Box à louer'
const TARGET = 'Studio'

function fail(message) {
  throw new Error('[investment-api] ' + ACTION + ': ' + message)
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

function create(name, amountCents) {
  const response = http.post(base + '/api/finance/projects', {
    headers: authorized({ 'Content-Type': 'application/json', Accept: 'application/ld+json' }),
    body: JSON.stringify({ name: name, amountCents: amountCents, wellbeing: 'medium', dateMode: 'none', targetOn: null, dependsOn: [], note: null }),
  })
  if (!response.ok) {
    fail('POST « ' + name + ' » answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

// The box first: deleting it takes back the resale it set aside for the studio.
function cleanup() {
  [BOX, TARGET].forEach(function (name) {
    projects()
      .filter(function (project) { return project.name === name })
      .forEach(function (project) {
        const response = http.request(base + '/api/finance/projects/' + project.id, {
          method: 'DELETE',
          headers: authorized({ Accept: 'application/ld+json' }),
        })
        if (!response.ok) {
          fail('DELETE « ' + name + ' » answered ' + response.status + ': ' + response.body)
        }
      })
  })
}

if (ACTION === 'cleanup') {
  cleanup()
} else if (ACTION === 'setup') {
  cleanup()
  const target = create(TARGET, 2000000)
  const box = create(BOX, 1800000)

  const now = new Date()
  const resale = new Date(now.getFullYear(), now.getMonth() + 30, 1)
  const month = resale.getFullYear() + '-' + String(resale.getMonth() + 1).padStart(2, '0')

  const response = http.request(base + '/api/finance/projects/' + box.id + '/investment', {
    method: 'PUT',
    headers: authorized({ 'Content-Type': 'application/json', Accept: 'application/json' }),
    body: JSON.stringify({
      downPaymentCents: 300000,
      monthlyRentCents: 65000,
      loan: { annualRateBasisPoints: 300, durationMonths: 240 },
      resale: { month: month, netAmountCents: 400000, targetProjectId: target.id },
    }),
  })
  if (!response.ok) {
    fail('PUT the investment of « ' + BOX + ' » answered ' + response.status + ': ' + response.body)
  }
} else {
  fail('unknown ACTION')
}
})()
