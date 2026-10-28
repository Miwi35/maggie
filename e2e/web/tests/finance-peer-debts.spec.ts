import type { APIRequestContext } from '@playwright/test'
import { test, expect, seedAnchorDate, seedDate, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { euros, overBudget } from '../helpers/money.js'
import { FinanceBudgetPage } from '../pages/FinanceBudgetPage.js'
import { FinanceCushionPage } from '../pages/FinanceCushionPage.js'
import { FinanceContactPage } from '../pages/FinanceContactPage.js'
import { FinanceOverviewPage } from '../pages/FinanceOverviewPage.js'
import { FinancePeerDebtsPage } from '../pages/FinancePeerDebtsPage.js'
import { FinanceReviewPage } from '../pages/FinanceReviewPage.js'
import { FinanceTransactionSheetPage } from '../pages/FinanceTransactionSheetPage.js'

/**
 * The people behind the transfers (MAG-450), extending the finance journey of
 * MAG-102: a transfer from Tom is associated from Revue › À qualifier by creating Tom,
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

  // When I associate it from Revue › À qualifier by creating Tom
  const review = new FinanceReviewPage(page)
  await review.open({ state: 'unrecognised', period: 'all' })
  await review.select(label)
  await review.associateWithNewContact(name)
  await expect(review.sheet.getByText(name, { exact: true }), 'the line now shows its contact').toBeVisible()
  await expect(review.associateContact).toHaveCount(0)

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
 *
 * Serial: the home's « On te doit / Tu dois » pill (MAG-455) is the net of
 * every créance and dette of this account, so two of these journeys must
 * not hold theirs at the same time.
 */
test.describe('a shared spend', () => {
  // The cushion journey reads the season account's créances as a whole: no other test of this describe may write one beside it.
  test.describe.configure({ mode: 'serial' })

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

  /**
   * « Affecter un virement reçu » (MAG-453): Tom owes 200 € on the Gîte de
   * Crozon, then sends 400 €. Allocated from « À trier », the gîte takes 200 €,
   * Tom's share is settled and the other 200 € stay « Non affecté », an income.
   *
   * The transfer is filed in a category, so « Ce virement est » is all its card
   * asks and answering it takes the line out of the pile. Its label is linked
   * to Tom first, which attaches the transfer to him when it is written.
   */
  test('a transfer of 400 € from Tom allocated to the gîte settles his 200 € and leaves 200 € « Non affecté »', async ({
    seasonUser,
  }) => {
    const { api, page } = seasonUser
    const suffix = letters(6)
    const tom = `Tom ${suffix}`
    const gite = `Gîte de Crozon ${suffix}`
    const label = `VIR. DE TOM-${suffix}`

    // Given the créance « Gîte de Crozon », of which Tom owes 200 €
    const accountId = await create(api, '/api/accounts', {
      name: `Compte MAG-453 ${suffix}`,
      type: 'checking',
      currency: 'EUR',
      balanceCents: 0,
    })
    const tomId = await create(api, '/api/contacts', { name: tom })
    const categoryId = await create(api, '/api/categories', { name: `Virements reçus ${suffix}`, obligation: 'income' })
    const spend = await create(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      label: `GITE DE CROZON ${suffix}`,
      amountCents: -40000,
      currency: 'EUR',
      bookedAt: seedDate(-7),
      status: 'spent',
    })
    const shared = await api.put(`/api/finance/transactions/${spend}/my-share`, {
      data: { myShareCents: 20000, object: gite, shares: [{ contactId: tomId }] },
    })
    expect(shared.status(), await shared.text()).toBe(200)

    // And a transfer received from Tom of +400 €, filed but not answered yet
    await create(api, '/api/contact_counterparties', { contact: `/api/contacts/${tomId}`, label })
    const transfer = await create(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      category: `/api/categories/${categoryId}`,
      label,
      amountCents: 40000,
      currency: 'EUR',
      bookedAt: seedDate(),
      status: 'spent',
    })
    // Removed first: a créance someone gave money back on outlives its spend, and holds Tom.
    created.unshift({ path: `/api/finance/transactions/${transfer}/allocation` })
    const before = await api.get(`/api/finance/transactions/${transfer}/allocation`)
    expect(before.status(), await before.text()).toBe(200)
    expect(((await before.json()) as { contact?: { id?: string } }).contact?.id, 'the transfer is Tom\'s').toBe(tomId)

    // When I open it from Revue › À qualifier and allocate it to the gîte
    const review = new FinanceReviewPage(page)
    await review.open({ state: 'unrecognised', period: 'all' })
    await review.select(label)
    await expect(review.otherCategory, 'a filed transfer asks no category').toHaveCount(0)
    await review.peerTransfer.cover([gite])
    await expect(review.peerTransfer.amountFor(gite), 'the gîte takes what Tom owes').toHaveValue('200')
    await expect(review.peerTransfer.unallocated).toContainText(`Non affecté : ${euros(20000)}`)
    await review.peerTransfer.save()

    // Then the line is Reconnue, and stays on its row until the list is read again
    await expect(review.rowState(label)).toHaveText('Reconnue')

    // And the transfer's sheet keeps the gîte covered, 200 € « Non affecté »
    const sheet = new FinanceTransactionSheetPage(page)
    await sheet.openPeerTransfer(transfer)
    await expect(sheet.peerTransfer.share(gite)).toBeChecked()
    await expect(sheet.peerTransfer.unallocated).toContainText(`Non affecté : ${euros(20000)}`)

    // And Tom's share is settled: nothing left « En cours » on his page
    const contact = new FinanceContactPage(page)
    await contact.open(tomId)
    await expect(contact.openDebts.getByText('Rien en cours.')).toBeVisible()
    await expect(contact.openDebt(gite)).toHaveCount(0)

    // And the transfer is in the history of Tom's page, with the 200 € it settled
    await expect(contact.allocatedTransfer(label)).toContainText(euros(20000))
    await expect(contact.allocatedTransfer(label)).toContainText(gite)
  })

  /**
   * « Créances et dettes » (MAG-455): Tom owes 200 € on a gîte and I owe him
   * 280 € of petrol. The Comptes tile of the home says « Tu dois 80 € », touching
   * it opens the screen, and Tom's row says the two cancel out.
   */
  test('Tom owes 200 € and I owe him 280 €: the home says « Tu dois 80 € » and the screen shows the compensation', async ({
    seasonUser,
  }) => {
    const { api, page } = seasonUser
    const suffix = letters(6)
    const tom = `Tom ${suffix}`
    const gite = `Gîte de Crozon ${suffix}`
    const petrol = `Essence du road trip ${suffix}`

    // Given a gîte of which Tom owes 200 €
    const accountId = await create(api, '/api/accounts', {
      name: `Compte MAG-455 ${suffix}`,
      type: 'checking',
      currency: 'EUR',
      balanceCents: 0,
    })
    const tomId = await create(api, '/api/contacts', { name: tom })
    const spend = await create(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      label: `GITE DE CROZON ${suffix}`,
      amountCents: -40000,
      currency: 'EUR',
      bookedAt: seedDate(-7),
      status: 'spent',
    })
    const shared = await api.put(`/api/finance/transactions/${spend}/my-share`, {
      data: { myShareCents: 20000, object: gite, shares: [{ contactId: tomId }] },
    })
    expect(shared.status(), await shared.text()).toBe(200)

    // And 280 € of petrol that I owe Tom
    const transfer = await create(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      label: `VIR AVANCE ESSENCE ${suffix}`,
      amountCents: 28000,
      currency: 'EUR',
      bookedAt: seedDate(),
      status: 'spent',
    })
    const debt = await api.post(`/api/finance/transactions/${transfer}/debt`, {
      data: { contactId: tomId, amountCents: 28000, object: petrol },
    })
    expect(debt.status(), await debt.text()).toBe(201)

    // When I open the finance home
    const overview = new FinanceOverviewPage(page)
    await overview.open()

    // Then the Comptes tile says « Tu dois 80 € »
    await expect(overview.owedPill).toHaveText(`Tu dois ${euros(8000)}`)

    // When I touch it, Then « Créances et dettes » opens with its two tiles
    await overview.owedPill.click()
    await expect(page).toHaveURL(/#\/finance\/peer-debts$/)
    const screen = new FinancePeerDebtsPage(page)
    await expect(screen.heading).toBeVisible()
    await expect(screen.owedToMeTile).toContainText(euros(20000))
    await expect(screen.iOweTile).toContainText(euros(28000))

    // And Tom's row says the two cancel out, one transfer of 80 € settles all
    await expect(screen.person(tom)).toContainText(`tu lui dois ${euros(8000)}`)
    await expect(screen.person(tom)).toContainText(
      `Les deux se compensent : un seul virement de ${euros(8000)} à ${tom} solde tout`,
    )

    // And opening Tom's row shows a line per object, and « Par objet » shows both
    await screen.person(tom).getByRole('button', { name: tom }).click()
    await expect(screen.person(tom)).toContainText(gite)
    await expect(screen.person(tom)).toContainText(petrol)
    await screen.byObjectSwitch.click()
    await expect(screen.object(gite)).toContainText(`on te doit ${euros(20000)}`)
    await expect(screen.object(petrol)).toContainText(`tu dois ${euros(28000)}`)
  })
  /**
   * « Avancé à des proches » (MAG-454): Julie owes 200 € on a gîte, so a Livret A
   * of 5 200 € held as cushion counts 5 000 €; her repayment gives the 200 € back.
   */
  test('a 200 € créance on Julie lowers the cushion to 5 000 €, and her repayment brings it back to 5 200 €', async ({
    seasonUser,
  }) => {
    const { api, page } = seasonUser
    const suffix = letters(6)
    const julie = `Julie ${suffix}`
    const gite = `Gîte de Crozon ${suffix}`
    const label = `VIR. DE JULIE-${suffix}`

    // Given a Livret A of 5 200 € counted in the cushion, and a target of 3 months of 2 500 €
    await create(api, '/api/accounts', {
      name: `Livret MAG-454 ${suffix}`,
      type: 'savings',
      currency: 'EUR',
      balanceCents: 520000,
      isCushion: true,
    })
    const config = await api.patch('/api/finance/cushion-config', {
      headers: { 'Content-Type': 'application/json' },
      data: { targetMonths: 3, monthlyNetIncomeCents: 250000 },
    })
    expect(config.status(), await config.text()).toBe(200)

    // And a créance of 200 € on Julie
    const accountId = await create(api, '/api/accounts', {
      name: `Compte MAG-454 ${suffix}`,
      type: 'checking',
      currency: 'EUR',
      balanceCents: 0,
    })
    const julieId = await create(api, '/api/contacts', { name: julie })
    const spend = await create(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      label: `GITE DE CROZON ${suffix}`,
      amountCents: -40000,
      currency: 'EUR',
      bookedAt: seedDate(-7),
      status: 'spent',
    })
    const shared = await api.put(`/api/finance/transactions/${spend}/my-share`, {
      data: { myShareCents: 20000, object: gite, shares: [{ contactId: julieId }] },
    })
    expect(shared.status(), await shared.text()).toBe(200)

    // When I open the cushion
    const cushion = new FinanceCushionPage(page)
    await cushion.open()

    // Then the Livret A counts 5 000 €, the 200 € being « Avancé à des proches »
    await expect(cushion.summary).toContainText(`${euros(500000)} sur ${euros(750000)}`)
    await expect(cushion.peerClaims).toContainText('Avancé à des proches')
    await expect(cushion.peerClaims).toContainText(gite)
    await expect(cushion.peerClaims).toContainText('revient au remboursement')
    await expect(cushion.peerClaims).toContainText(`−${euros(20000)}`)
    await expect(cushion.summary).toContainText(`Il manque ${euros(250000)}, dont ${euros(20000)} avancés à des proches`)

    // When Julie repays her 200 €
    await create(api, '/api/contact_counterparties', { contact: `/api/contacts/${julieId}`, label })
    const transfer = await create(api, '/api/transactions', {
      account: `/api/accounts/${accountId}`,
      label,
      amountCents: 20000,
      currency: 'EUR',
      bookedAt: seedDate(),
      status: 'spent',
    })
    // Removed first: a créance someone gave money back on outlives its spend, and holds Julie.
    created.unshift({ path: `/api/finance/transactions/${transfer}/allocation` })
    const candidates = await api.get(`/api/finance/transactions/${transfer}/allocation`)
    expect(candidates.status(), await candidates.text()).toBe(200)
    const share = ((await candidates.json()) as { candidates: Array<{ shareId: string; object: string }> }).candidates.find(
      (candidate) => candidate.object === gite,
    )
    expect(share, 'the gîte is offered to the transfer').toBeDefined()
    const repaid = await api.put(`/api/finance/transactions/${transfer}/allocation`, {
      data: { kind: 'repayment', allocations: [{ shareId: share?.shareId, amountCents: 20000 }] },
    })
    expect(repaid.status(), await repaid.text()).toBe(200)

    // Then the cushion is back to 5 200 € and the row is gone
    await cushion.open()
    await expect(cushion.summary).toContainText(`${euros(520000)} sur ${euros(750000)}`)
    await expect(cushion.peerClaims).toHaveCount(0)
  })
})
