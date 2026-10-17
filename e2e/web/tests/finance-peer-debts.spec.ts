import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedAnchorDate, seedDate, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { euros, overBudget } from '../helpers/money.js'
import { FinanceBudgetPage } from '../pages/FinanceBudgetPage.js'
import { FinanceContactPage } from '../pages/FinanceContactPage.js'
import { FinanceToSortPage } from '../pages/FinanceToSortPage.js'
import { FinanceTransactionSheetPage } from '../pages/FinanceTransactionSheetPage.js'

/**
 * The people behind the transfers (MAG-450), extending the finance journey of
 * MAG-102: a transfer from Tom is associated from « À trier » by creating Tom,
 * and the next transfer carrying the same label is Tom's without a gesture.
 *
 * A label belongs to one contact only and linking it claims every transfer
 * that carries it, so a retry must not meet the previous attempt's Tom: the
 * label and the name end with letters unique per attempt — letters, because
 * digits would read as a reference, glued to the surname, because the
 * counterparty taken from a label keeps its first four words only: a fifth
 * word « VIR. DE TOM MARTIN ODPHES » became « VIR. DE TOM MARTIN », shared by
 * every attempt.
 */

interface StoredContact {
  id?: string
  name?: string
}

interface StoredTransaction {
  id?: string
  label?: string
  contact?: string | null
}

const letters = (length: number): string =>
  Array.from({ length }, () => String.fromCharCode(65 + Math.floor(Math.random() * 26))).join('')

test('a transfer associated from « À trier » makes its label Tom\'s, and the next one is attached to him', async ({
  page,
  api,
}) => {
  const suffix = letters(6)
  const label = `VIR. DE TOM MARTIN-${suffix}`
  const name = `Tom ${suffix}`

  const transfer = async (): Promise<string> => {
    const response = await api.post('/api/transactions', {
      headers: { 'Content-Type': 'application/ld+json' },
      data: {
        account: `/api/accounts/${seedId('e2e_account_checking')}`,
        label,
        amountCents: 40000,
        currency: 'EUR',
        bookedAt: seedDate(0),
        status: 'spent',
      },
    })
    expect(response.status(), await response.text()).toBe(201)

    return ((await response.json()) as { id: string }).id
  }

  // Given a transfer « VIR. DE TOM MARTIN » of +400 € that no contact claims
  await transfer()

  // When I associate it from « À trier » by creating Tom
  const toSort = new FinanceToSortPage(page)
  await toSort.open()
  await toSort.select(label)
  await toSort.associateWithNewContact(name)
  await expect(toSort.card.getByText(name, { exact: true }), 'the line now shows its contact').toBeVisible()
  await expect(toSort.associateContact).toHaveCount(0)

  // Then Tom's page lists the label with its one transfer
  const tom = await waitForIndexed<StoredContact>(api, '/api/contacts?itemsPerPage=100', (c) => c.name === name, {
    what: `The contact ${name}`,
  })
  const contact = new FinanceContactPage(page)
  await contact.open(String(tom.id))
  await expect(contact.label(label)).toContainText('1 virement')

  // And a second transfer with the same label, arriving like an import, is his
  const secondId = await transfer()
  const second = await waitForIndexed<StoredTransaction>(
    api,
    '/api/transactions?itemsPerPage=100',
    (candidate) => candidate.id === secondId && Boolean(candidate.contact),
    { what: `The second transfer ${label}, attached to ${name}` },
  )
  expect(second.contact).toBe(`/api/contacts/${tom.id}`)

  await contact.open(String(tom.id))
  await expect(contact.label(label)).toContainText('2 virements')
})

/**
 * « Ma part d'une dépense partagée » (MAG-451): a gîte of 800 € of which my
 * part is 200 €, the rest owed by Julie, Tom and Inès, 200 € each. Only my
 * part counts in the Voyages envelope, and Tom's page says what he owes.
 *
 * On the season account, whose finance no other journey reads: a Voyages
 * envelope on the owner's would add a gauge `finance-budget.spec.ts` counts.
 * Every name ends with letters unique per attempt, and what the journey
 * creates is removed after it, the transaction first — a contact with a share
 * cannot be deleted.
 */
test.describe('a shared spend', () => {
  const created: { path: string }[] = []

  const create = async (api: APIRequestContext, path: string, data: Record<string, unknown>): Promise<string> => {
    const response = await api.post(path, { headers: { 'Content-Type': 'application/ld+json' }, data })
    expect(response.status(), await response.text()).toBe(201)
    const id = String(((await response.json()) as { id?: string }).id ?? '')
    created.unshift({ path: `${path}/${id}` })

    return id
  }

  test.afterEach(async ({ seasonUser }) => {
    for (const { path } of created.splice(0)) {
      await seasonUser.api.delete(path)
    }
  })

  test('my part of the gîte is all the Voyages envelope counts, and Tom owes his 200 €', async ({ seasonUser }) => {
    const { api, page } = seasonUser
    const suffix = letters(6)
    const [year, month] = seedAnchorDate().split('-').map(Number)
    const voyages = `Voyages ${suffix}`
    const [julie, tom, ines] = ['Julie', 'Tom', 'Inès'].map((name) => `${name} ${suffix}`)

    // Given a gîte of 800 € filed in Voyages, with a 300 € envelope this month
    const categoryId = await create(api, '/api/categories', { name: voyages, obligation: 'optional' })
    await create(api, '/api/envelopes', {
      category: `/api/categories/${categoryId}`,
      mode: 'monthly',
      amountCents: 30000,
      currency: 'EUR',
      year,
      month,
    })
    const accountId = await create(api, '/api/accounts', {
      name: `Compte MAG-451 ${suffix}`,
      type: 'checking',
      currency: 'EUR',
      balanceCents: 0,
    })
    const contactIds: Record<string, string> = {}
    for (const name of [julie, tom, ines]) {
      contactIds[name] = await create(api, '/api/contacts', { name })
    }
    const gite = await create(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      category: `/api/categories/${categoryId}`,
      label: `GITE DES CEVENNES ${suffix}`,
      amountCents: -80000,
      currency: 'EUR',
      bookedAt: seedDate(),
      status: 'spent',
    })

    // When I declare my part of 200 € shared between Julie, Tom and Inès on its sheet
    const sheet = new FinanceTransactionSheetPage(page)
    await sheet.open(gite)
    await sheet.shareWith(200, [julie, tom, ines])
    for (const name of [julie, tom, ines]) {
      await expect(sheet.partOf(name), `${name} owes a third of the 600 €`).toHaveValue('200')
    }

    // Then the Voyages envelope has consumed my 200 €, not the 800 €
    const budget = new FinanceBudgetPage(page)
    await budget.open()
    await expect(budget.gauge(voyages)).toContainText(overBudget(20000, 30000))

    // And Tom's page shows the gîte, « te doit 200,00 € »
    const contact = new FinanceContactPage(page)
    await contact.open(contactIds[tom])
    await expect(contact.openDebt(`GITE DES CEVENNES ${suffix}`)).toContainText(`te doit ${euros(20000)}`)
  })

  /**
   * « Un virement reçu à rembourser » (MAG-452): Tom advances 400 € of which
   * 280 € are his. Declared on the transfer's sheet, the 280 € leave the month's
   * income — 120 € stay — and Tom's page says what is owed him.
   */
  test('a transfer of 400 € with 280 € to give back leaves 120 € of income, and I owe Tom 280 €', async ({
    seasonUser,
  }) => {
    const { api, page } = seasonUser
    const suffix = letters(6)
    const month = seedDate().slice(0, 7)
    const tom = `Tom ${suffix}`

    const monthIncome = async (): Promise<number> => {
      const response = await api.get('/api/finance/dashboard')
      expect(response.status(), await response.text()).toBe(200)
      const dashboard = (await response.json()) as { monthlyFlows: Array<{ month: string; incomeCents: number }> }

      return dashboard.monthlyFlows.find((flow) => flow.month === month)?.incomeCents ?? 0
    }

    // Given a transfer received from Tom of +400 €, in the month's income
    const accountId = await create(api, '/api/accounts', {
      name: `Compte MAG-452 ${suffix}`,
      type: 'checking',
      currency: 'EUR',
      balanceCents: 0,
    })
    const tomId = await create(api, '/api/contacts', { name: tom })
    const before = await monthIncome()
    const transfer = await create(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      label: `VIR AVANCE GITE ${suffix}`,
      amountCents: 40000,
      currency: 'EUR',
      bookedAt: seedDate(),
      status: 'spent',
    })
    expect(await monthIncome(), 'the transfer is an income').toBe(before + 40000)

    // When I declare 280 € of it « À rembourser » to Tom on its sheet
    const sheet = new FinanceTransactionSheetPage(page)
    await sheet.openReceived(transfer)
    await sheet.declareDebt(tom, 280)
    await expect(sheet.debt).toContainText(`Crée une dette de ${euros(28000)} envers ${tom}`)

    // Then only the 120 € left stay in the month's income
    expect(await monthIncome(), 'the 280 € to give back are no income').toBe(before + 12000)

    // And Tom's page says « tu lui dois 280,00 € » under « En cours »
    const contact = new FinanceContactPage(page)
    await contact.open(tomId)
    await expect(contact.openDebt(`VIR AVANCE GITE ${suffix}`)).toContainText(`tu lui dois ${euros(28000)}`)
  })
})
