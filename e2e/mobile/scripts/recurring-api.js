// The data side of the recurring-operations journey, played over HTTP (MAG-309).
//
// The journey reads and accepts on the phone; this script only sets the table before
// (a rent series anchored two months back, its one line booked eight days late, which
// the pass only proposes) and reads the database after (the line is attached).
// Same login and polling as `meal-api.js`.
//
// Parameters (flow `env:`, plus TODAY, E2E_BASE_URL and E2E_LOGIN_TOKEN from `run.sh`):
//   ACTION  create | expect-attached | expect-requalified | qualify-line | expect-manual-kept
//           | expect-detached | remove
//           create-salary | expect-salary-attached | remove-salary   (MAG-475)
//
// Everything lives on an account of its own, so `remove` is one delete: the series and
// the lines go with it. Dates come from TODAY, the day the stack runs at.

(function () {
const base = E2E_BASE_URL
const email = 'e2e@maggie.local'
const accountName = 'Courant MAG-309'
const categoryName = 'Loyer MAG-309'
const transactionLabel = 'LOYER SCI VERDA'
// MAG-475: a credit the series « Salaire » must recognise by its amount alone.
const salaryAccountName = 'Courant MAG-475'
const salaryCategoryName = 'Revenu MAG-475'
const salaryLabel = 'VIR SEPA XYZ'

function fail(message) {
  throw new Error('[recurring-api] ' + ACTION + ': ' + message)
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
const LD = { 'Content-Type': 'application/ld+json', Accept: 'application/ld+json' }
const deadline = Date.now() + 30000

function collection(path) {
  const response = http.get(base + path, { headers: authorized({ Accept: 'application/ld+json' }) })
  if (!response.ok) {
    fail('GET ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body).member
}

function post(path, body) {
  const response = http.post(base + path, { headers: authorized(LD), body: JSON.stringify(body) })
  if (!response.ok) {
    fail('POST ' + path + ' answered ' + response.status + ': ' + response.body)
  }
  return JSON.parse(response.body)
}

function removeLeftovers(account, category) {
  collection('/api/accounts?itemsPerPage=200')
    .filter(function (row) { return row.name === (account || accountName) })
    .forEach(function (row) { http.request(base + row['@id'], { method: 'DELETE', headers: authorized() }) })
  collection('/api/categories?itemsPerPage=200')
    .filter(function (row) { return row.name === (category || categoryName) })
    .forEach(function (row) { http.request(base + row['@id'], { method: 'DELETE', headers: authorized() }) })
}

// The 5th of the month two months before TODAY: the first due date of the series.
function firstDue() {
  const year = Number(TODAY.substring(0, 4))
  const month = Number(TODAY.substring(5, 7)) - 1 // 0-based
  const back = month - 2
  const y = back < 0 ? year - 1 : year
  const m = (back + 12) % 12 + 1
  return y + '-' + (m < 10 ? '0' : '') + m + '-05'
}

function series() {
  return collection('/api/recurring_operations?itemsPerPage=200').filter(function (row) { return row.label === 'Loyer MAG-309' })
}

function waitUntil(done, message) {
  for (;;) {
    if (done()) {
      return
    }
    if (Date.now() >= deadline) {
      fail(message)
    }
    pause(300)
  }
}

function transactions() {
  return collection('/api/transactions?itemsPerPage=200').filter(function (row) { return row.label === transactionLabel })
}

if (ACTION === 'create') {
  removeLeftovers() // a retried run starts from nothing
  const anchorOn = firstDue()
  const account = post('/api/accounts', { name: accountName, type: 'checking', currency: 'EUR', balanceCents: 0 })
  // Optional: the series starts without « Obligatoire », which the journey then switches on.
  const category = post('/api/categories', { name: categoryName, obligation: 'optional' })
  post('/api/recurring_operations', {
    label: 'Loyer MAG-309',
    counterpartyName: transactionLabel, // the line's payee is its whole label: the key must be the same
    account: account['@id'],
    category: category['@id'],
    period: 'monthly',
    anchorOn: anchorOn,
    referenceAmountCents: -90000,
    amountTolerancePercent: 20,
    dateToleranceDays: 5,
  })
  // Eight days after the due date, past the tolerance: proposed, never attached on its own.
  const line = post('/api/transactions', {
    account: account['@id'],
    // The payee is read from the label: the line has no writable counterparty.
    label: transactionLabel,
    amountCents: -90000,
    currency: 'EUR',
    bookedAt: anchorOn.substring(0, 8) + '13',
    status: 'spent',
  })
  if (line.recurringOperation) {
    fail('the late line was attached on its own, so there is nothing to accept')
  }
} else if (ACTION === 'expect-attached') {
  for (;;) {
    const lines = transactions()
    if (lines.length === 1 && lines[0].recurringOperation) {
      break
    }
    if (Date.now() >= deadline) {
      fail('the line "' + transactionLabel + '" was never attached within 30 s (found ' + lines.length + ' line(s))')
    }
    pause(300)
  }
} else if (ACTION === 'expect-requalified') {
  // MAG-479: the series saved from the phone holds the qualification, and the line it set carries it.
  waitUntil(function () {
    const rows = series()
    const lines = transactions()
    return rows.length === 1 && rows[0].qualification.mandatory === true && rows[0].qualification.kind === 'repayment' &&
      lines.length === 1 && lines[0].qualification.mandatory === true && lines[0].qualification.kind === 'repayment' &&
      lines[0].qualifiedBy === 'series'
  }, 'the series and its line never both read mandatory / repayment within 30 s')
} else if (ACTION === 'qualify-line') {
  const line = transactions()[0]
  const response = http.request(base + line['@id'], {
    method: 'PATCH',
    headers: authorized({ 'Content-Type': 'application/merge-patch+json', Accept: 'application/ld+json' }),
    body: JSON.stringify({ category: line.category, qualification: { mandatory: false, kind: 'saving' } }),
  })
  if (!response.ok) {
    fail('PATCH ' + line['@id'] + ' answered ' + response.status + ': ' + response.body)
  }
  if (JSON.parse(response.body).qualifiedBy !== 'manual') {
    fail('the line qualified by hand is not manual')
  }
} else if (ACTION === 'expect-manual-kept') {
  // The series moved to « loan »; the line set by hand stays as the owner left it.
  waitUntil(function () {
    const rows = series()
    return rows.length === 1 && rows[0].qualification.kind === 'loan'
  }, 'the series never read kind loan within 30 s')
  const line = transactions()[0]
  if (line.qualifiedBy !== 'manual' || line.qualification.kind !== 'saving' || line.qualification.mandatory !== false) {
    fail('the line set by hand was touched: ' + JSON.stringify(line.qualification) + ' by ' + line.qualifiedBy)
  }
} else if (ACTION === 'expect-detached') {
  for (;;) {
    const lines = transactions()
    if (lines.length === 1 && !lines[0].recurringOperation) {
      break
    }
    if (Date.now() >= deadline) {
      fail('the line "' + transactionLabel + '" was still attached after 30 s (found ' + lines.length + ' line(s))')
    }
    pause(300)
  }
} else if (ACTION === 'remove') {
  removeLeftovers()
} else if (ACTION === 'create-salary') {
  removeLeftovers(salaryAccountName, salaryCategoryName)
  const account = post('/api/accounts', { name: salaryAccountName, type: 'checking', currency: 'EUR', balanceCents: 0 })
  post('/api/categories', { name: salaryCategoryName, obligation: 'income' })
  // 4 650 EUR with a label and no payee the series will know: only its amount can recognise it.
  post('/api/transactions', {
    account: account['@id'],
    label: salaryLabel,
    amountCents: 465000,
    currency: 'EUR',
    bookedAt: TODAY,
    status: 'spent',
  })
} else if (ACTION === 'expect-salary-attached') {
  for (;;) {
    const lines = collection('/api/transactions?itemsPerPage=200').filter(function (row) { return row.label === salaryLabel })
    if (lines.length === 1 && lines[0].recurringOperation) {
      break
    }
    if (Date.now() >= deadline) {
      fail('the line "' + salaryLabel + '" was never attached within 30 s (found ' + lines.length + ' line(s))')
    }
    pause(300)
  }
  // The series was written without any identity: the amount and its sign are all it holds.
  const series = collection('/api/recurring_operations?itemsPerPage=200').filter(function (row) { return row.label === 'Salaire' })
  if (series.length !== 1 || series[0].counterpartyName || series[0].labelPattern ||
      series[0].referenceAmountCents !== 475000 || series[0].amountTolerancePercent !== 10) {
    fail('the series "Salaire" is not the one the journey wrote: ' + JSON.stringify(series))
  }
} else if (ACTION === 'remove-salary') {
  removeLeftovers(salaryAccountName, salaryCategoryName)
} else {
  fail('unknown ACTION')
}
})()
