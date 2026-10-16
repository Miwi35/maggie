import { test, expect, seedDate, seedId } from '../fixtures/index.js'
import { waitForIndexed } from '../helpers/api.js'
import { FinanceContactPage } from '../pages/FinanceContactPage.js'
import { FinanceToSortPage } from '../pages/FinanceToSortPage.js'

/**
 * The people behind the transfers (MAG-450), extending the finance journey of
 * MAG-102: a transfer from Tom is associated from « À trier » by creating Tom,
 * and the next transfer carrying the same label is Tom's without a gesture.
 *
 * A label belongs to one contact only and linking it claims every transfer
 * that carries it, so a retry must not meet the previous attempt's Tom: the
 * label and the name end with letters unique per attempt — letters, because
 * the label without its references keeps them, where digits would read as a
 * reference.
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
  const label = `VIR. DE TOM MARTIN ${suffix}`
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
