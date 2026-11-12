import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Form, Notification, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { NatureSection } from './NatureSection'
import { advanceSourceChoices, useTransactionTransfer } from './useTransactionTransfer'

const RECORD = { id: '/api/transactions/01OUT', label: 'Virement vers Courant', amountCents: -300000 }

/** The section as the sheet mounts it: the transfer state read once, above, and the form around. */
const Harness = () => {
  const transferApi = useTransactionTransfer(RECORD.id)

  return (
    <Form defaultValues={{ nature: 'expense' }}>
      <NatureSection
        transferApi={transferApi}
        kind={transferApi.transfer?.transferKind ?? 'none'}
        amountCents={RECORD.amountCents}
      />
    </Form>
  )
}

const LEG = {
  id: '01IN',
  label: 'Virement du Livret',
  amountCents: 300000,
  currency: 'EUR',
  bookedAt: '2026-09-13',
  accountId: '01CHK',
  accountName: 'Courant',
}

const NONE = { transferKind: 'none', transferSource: 'auto', counterpart: null }
const PAIRED = { transferKind: 'internal', transferSource: 'auto', counterpart: LEG }

const fetchMock = vi.fn()

const respond = (status: number, body: unknown) =>
  Promise.resolve({ ok: status < 400, status, json: async () => body } as Response)

const renderPanel = (dataProvider: DataProvider = testDataProvider()) =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <Harness />
      <Notification />
    </AdminContext>,
  )

const bodiesOf = (method: string) =>
  fetchMock.mock.calls
    .filter(([, init]) => (init as RequestInit | undefined)?.method === method)
    .map(([url, init]) => ({ url, body: JSON.parse((init as RequestInit).body as string) }))

describe('NatureSection', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'jwt')
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('a line that is a transfer shows the badge, who decided and the other leg', async () => {
    fetchMock.mockImplementation(() => respond(200, PAIRED))

    renderPanel()

    expect(await screen.findByText('Virement interne', { selector: '.MuiChip-label' })).toBeInTheDocument()
    expect(screen.getByText('Détecté automatiquement')).toBeInTheDocument()
    expect(screen.getByText(/Courant · 13\/09\/2026 · Virement du Livret/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/finance/transactions/01OUT/transfer',
      expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer jwt' }) }),
    )
    expect(screen.getByRole('button', { name: 'Ce n’est pas un virement interne' })).toBeInTheDocument()
  })

  test('an advance is shown as a transfer, never offered as an ordinary line (MAG-276)', async () => {
    fetchMock.mockImplementation(() => respond(200, { ...PAIRED, transferKind: 'advance' }))

    renderPanel()

    expect(await screen.findByText('Avance d’épargne', { selector: '.MuiChip-label' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'C’est un virement interne' })).not.toBeInTheDocument()
    expect(screen.queryByText(/compte comme une dépense ordinaire/)).not.toBeInTheDocument()
  })

  test('an ordinary line shows no badge and offers to mark it', async () => {
    fetchMock.mockImplementation(() => respond(200, NONE))

    renderPanel()

    expect(await screen.findByRole('button', { name: 'C’est un virement interne' })).toBeInTheDocument()
    expect(screen.queryByText('Virement interne', { selector: '.MuiChip-label' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ce n’est pas un virement interne' })).not.toBeInTheDocument()
  })

  test('taking the marking off puts the owner’s word on the line and drops the badge', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'PUT'
        ? respond(200, { success: true, transferKind: 'none', transferSource: 'manual', counterpart: null })
        : respond(200, PAIRED),
    )

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: 'Ce n’est pas un virement interne' }))

    expect(await screen.findByRole('button', { name: 'C’est un virement interne' })).toBeInTheDocument()
    expect(screen.queryByText('Virement interne', { selector: '.MuiChip-label' })).not.toBeInTheDocument()
    expect(bodiesOf('PUT')).toEqual([
      { url: '/api/finance/transactions/01OUT/transfer', body: { transferKind: 'none' } },
    ])
    expect(await screen.findByText('Ce n’est plus un virement interne')).toBeInTheDocument()
  })

  test('marking by hand offers the candidates and sends the one chosen', async () => {
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') return respond(200, { success: true, ...PAIRED, transferSource: 'manual' })
      if (url.endsWith('/transfer-candidates')) return respond(200, { candidates: [LEG] })
      return respond(200, NONE)
    })

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: 'C’est un virement interne' }))

    const dialog = await screen.findByRole('dialog')
    await userEvent.click(await within(dialog).findByRole('radio', { name: /Virement du Livret/ }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Marquer comme virement interne' }))

    await waitFor(() =>
      expect(bodiesOf('PUT')).toEqual([
        {
          url: '/api/finance/transactions/01OUT/transfer',
          body: { transferKind: 'internal', counterpartId: '01IN' },
        },
      ]),
    )
    expect(await screen.findByText('Marqué à la main')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  test('with no candidate, the owner can still mark a single leg', async () => {
    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        return respond(200, { success: true, transferKind: 'internal', transferSource: 'manual', counterpart: null })
      }
      if (url.endsWith('/transfer-candidates')) return respond(200, { candidates: [] })
      return respond(200, NONE)
    })

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: 'C’est un virement interne' }))

    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText(/Aucune ligne d’un autre de vos comptes ne correspond/)).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: 'Marquer comme virement interne' }))

    await waitFor(() => expect(bodiesOf('PUT')[0].body).toEqual({ transferKind: 'internal' }))
    expect(await screen.findByText(/Sans contrepartie/)).toBeInTheDocument()
  })

  test('an API refusal is shown and the line keeps its marking', async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      init?.method === 'PUT' ? respond(400, { error: 'An internal transfer goes between two different accounts.' }) : respond(200, PAIRED),
    )

    renderPanel()
    await userEvent.click(await screen.findByRole('button', { name: 'Ce n’est pas un virement interne' }))

    expect(
      await screen.findByText('An internal transfer goes between two different accounts.'),
    ).toBeInTheDocument()
    expect(screen.getByText('Détecté automatiquement')).toBeInTheDocument()
  })

  describe('rejections', () => {
    const CREDIT = { ...LEG, id: '01BACK', label: 'Rejet virement Courant', accountId: '01LIV', accountName: 'Livret' }
    const REJECTED = { transferKind: 'rejected', transferSource: 'manual', counterpart: CREDIT }

    test('a rejected payment shows its badge, who decided, what gave it back, and offers to release it', async () => {
      fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
        init?.method === 'PUT'
          ? respond(200, { success: true, transferKind: 'none', transferSource: 'manual', counterpart: null })
          : respond(200, REJECTED),
      )

      renderPanel()

      expect(await screen.findByText('Rejeté', { selector: '.MuiChip-label' })).toBeInTheDocument()
      expect(screen.getByText('Marqué à la main')).toBeInTheDocument()
      expect(screen.getByText(/Rendu par :/)).toBeInTheDocument()
      expect(screen.getByText(/Livret · 13\/09\/2026 · Rejet virement Courant/)).toBeInTheDocument()
      expect(screen.getByText('Régularisé')).toBeInTheDocument()

      await userEvent.click(screen.getByRole('button', { name: 'Ce n’est pas un rejet' }))

      expect(await screen.findByRole('button', { name: 'C’est un rejet' })).toBeInTheDocument()
      expect(bodiesOf('PUT')).toEqual([
        { url: '/api/finance/transactions/01OUT/transfer', body: { transferKind: 'none' } },
      ])
      expect(await screen.findByText('Ce n’est plus un rejet')).toBeInTheDocument()
    })

    test('« C’est un rejet » offers the lines of the same account and sends the one chosen', async () => {
      fetchMock.mockImplementation((url: string, init?: RequestInit) => {
        if (init?.method === 'PUT') return respond(200, { success: true, ...REJECTED })
        if (url.endsWith('/transfer-candidates?kind=rejected')) return respond(200, { candidates: [CREDIT] })
        return respond(200, NONE)
      })

      renderPanel()
      await userEvent.click(await screen.findByRole('button', { name: 'C’est un rejet' }))

      const dialog = await screen.findByRole('dialog')
      expect(await within(dialog).findByText('Les lignes du même compte, au montant opposé, à quinze jours près.')).toBeInTheDocument()
      expect(within(dialog).getByRole('radio', { name: 'Aucune contrepartie : le paiement rejeté n’est pas ici' })).toBeInTheDocument()
      await userEvent.click(within(dialog).getByRole('radio', { name: /Rejet virement Courant/ }))
      await userEvent.click(within(dialog).getByRole('button', { name: 'Marquer comme rejet' }))

      await waitFor(() =>
        expect(bodiesOf('PUT')).toEqual([
          {
            url: '/api/finance/transactions/01OUT/transfer',
            body: { transferKind: 'rejected', counterpartId: '01BACK' },
          },
        ]),
      )
      expect(fetchMock).toHaveBeenCalledWith(
        '/api/finance/transactions/01OUT/transfer-candidates?kind=rejected',
        expect.anything(),
      )
      expect(await screen.findByText('Marqué comme rejet')).toBeInTheDocument()
      expect(screen.getByText('Rejeté', { selector: '.MuiChip-label' })).toBeInTheDocument()
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    test('with no line to pair, a rejection can still be marked alone', async () => {
      fetchMock.mockImplementation((url: string, init?: RequestInit) => {
        if (init?.method === 'PUT') {
          return respond(200, { success: true, transferKind: 'rejected', transferSource: 'manual', counterpart: null })
        }
        if (url.endsWith('/transfer-candidates?kind=rejected')) return respond(200, { candidates: [] })
        return respond(200, NONE)
      })

      renderPanel()
      await userEvent.click(await screen.findByRole('button', { name: 'C’est un rejet' }))

      const dialog = await screen.findByRole('dialog')
      expect(
        await within(dialog).findByText('Aucune ligne du même compte ne correspond (montant opposé, dans les quinze jours).'),
      ).toBeInTheDocument()
      await userEvent.click(within(dialog).getByRole('button', { name: 'Marquer comme rejet' }))

      await waitFor(() => expect(bodiesOf('PUT')[0].body).toEqual({ transferKind: 'rejected' }))
      expect(await screen.findByText('Sans contrepartie')).toBeInTheDocument()
      expect(screen.getByText('Non régularisé')).toBeInTheDocument()
    })

    test('a refused rejection is shown and the dialog stays open', async () => {
      fetchMock.mockImplementation((url: string, init?: RequestInit) => {
        if (init?.method === 'PUT') return respond(400, { error: 'A rejection stays on one account.' })
        if (url.endsWith('/transfer-candidates?kind=rejected')) return respond(200, { candidates: [CREDIT] })
        return respond(200, NONE)
      })

      renderPanel()
      await userEvent.click(await screen.findByRole('button', { name: 'C’est un rejet' }))

      const dialog = await screen.findByRole('dialog')
      await userEvent.click(await within(dialog).findByRole('radio', { name: /Rejet virement Courant/ }))
      await userEvent.click(within(dialog).getByRole('button', { name: 'Marquer comme rejet' }))

      expect(await screen.findByText('A rejection stays on one account.')).toBeInTheDocument()
      expect(screen.getByRole('dialog')).toBeInTheDocument()
      expect(screen.queryByText('Rejeté', { selector: '.MuiChip-label' })).not.toBeInTheDocument()
    })
  })

  describe('the source of a single-legged advance (MAG-806)', () => {
    // As the accounts collection is recorded (api/contract/responses/accounts.collection.json).
    const ACCOUNTS = [
      { id: '/api/accounts/01CHK', name: 'Courant', bank: 'CA', currency: 'EUR', type: 'checking' },
      { id: '/api/accounts/01LIVA', name: 'Livret A', bank: null, currency: 'EUR', type: 'savings' },
      { id: '/api/accounts/01PEA', name: 'PEA', bank: 'Bourso', currency: 'EUR', type: 'investment' },
    ]
    const withAccounts = () =>
      testDataProvider({
        getList: () => Promise.resolve({ data: ACCOUNTS, total: ACCOUNTS.length }),
      } as Partial<DataProvider>)

    const ALONE = { transferKind: 'advance', transferSource: 'manual', counterpart: null, advanceSource: null }
    const SOURCED = { ...ALONE, advanceSource: { id: '01LIVA', name: 'Livret A' } }

    test('offers only the savings and investment accounts, and sends the bare ULID of the one chosen', async () => {
      fetchMock.mockImplementation((url: string, init?: RequestInit) =>
        init?.method === 'PUT' && url.endsWith('/advance-source')
          ? respond(200, { success: true, ...SOURCED })
          : respond(200, ALONE),
      )

      renderPanel(withAccounts())
      await userEvent.click(await screen.findByRole('combobox', { name: 'Source' }))

      const listbox = await screen.findByRole('listbox')
      expect(within(listbox).getByRole('option', { name: 'Aucune' })).toBeInTheDocument()
      expect(within(listbox).getByRole('option', { name: 'Bourso · EUR · PEA' })).toBeInTheDocument()
      expect(within(listbox).queryByRole('option', { name: /Courant/ })).not.toBeInTheDocument()
      await userEvent.click(within(listbox).getByRole('option', { name: 'EUR · Livret A' }))

      await waitFor(() =>
        expect(bodiesOf('PUT')).toEqual([
          { url: '/api/finance/transactions/01OUT/advance-source', body: { accountId: '01LIVA' } },
        ]),
      )
      expect(await screen.findByText('Source enregistrée')).toBeInTheDocument()
      expect(screen.getByRole('combobox', { name: 'Source' })).toHaveTextContent('Livret A')
    })

    test('« Aucune » takes the source off', async () => {
      fetchMock.mockImplementation((url: string, init?: RequestInit) =>
        init?.method === 'PUT' && url.endsWith('/advance-source')
          ? respond(200, { success: true, ...ALONE })
          : respond(200, SOURCED),
      )

      renderPanel(withAccounts())
      const field = await screen.findByRole('combobox', { name: 'Source' })
      await waitFor(() => expect(field).toHaveTextContent('Livret A'))
      await userEvent.click(field)
      await userEvent.click(await screen.findByRole('option', { name: 'Aucune' }))

      await waitFor(() =>
        expect(bodiesOf('PUT')).toEqual([
          { url: '/api/finance/transactions/01OUT/advance-source', body: { accountId: null } },
        ]),
      )
      expect(await screen.findByText('Source retirée')).toBeInTheDocument()
    })

    test('the source already named is shown before the accounts answer', async () => {
      fetchMock.mockImplementation(() => respond(200, SOURCED))

      renderPanel()

      expect(await screen.findByRole('combobox', { name: 'Source' })).toHaveTextContent('Livret A')
    })

    test('an API refusal is shown and the source stays as it was', async () => {
      fetchMock.mockImplementation((url: string, init?: RequestInit) =>
        init?.method === 'PUT' && url.endsWith('/advance-source')
          ? respond(400, { error: 'The source of an advance is one of your savings or investment accounts.' })
          : respond(200, ALONE),
      )

      renderPanel(withAccounts())
      await userEvent.click(await screen.findByRole('combobox', { name: 'Source' }))
      await userEvent.click(await screen.findByRole('option', { name: 'EUR · Livret A' }))

      expect(
        await screen.findByText('The source of an advance is one of your savings or investment accounts.'),
      ).toBeInTheDocument()
      expect(screen.getByRole('combobox', { name: 'Source' })).not.toHaveTextContent('Livret A')
    })

    test('a repayment without counterpart has the field too', async () => {
      fetchMock.mockImplementation(() => respond(200, { ...ALONE, transferKind: 'repayment' }))

      renderPanel()

      expect(await screen.findByRole('combobox', { name: 'Source' })).toBeInTheDocument()
    })

    test('an advance with its other leg, or an internal transfer, has no source to name', async () => {
      fetchMock.mockImplementation(() => respond(200, { ...PAIRED, transferKind: 'advance' }))
      const { unmount } = renderPanel()
      expect(await screen.findByText('Avance d’épargne', { selector: '.MuiChip-label' })).toBeInTheDocument()
      expect(screen.queryByRole('combobox', { name: 'Source' })).not.toBeInTheDocument()
      unmount()

      fetchMock.mockImplementation(() => respond(200, { ...ALONE, transferKind: 'internal' }))
      renderPanel()
      expect(await screen.findByText('Virement interne', { selector: '.MuiChip-label' })).toBeInTheDocument()
      expect(screen.queryByRole('combobox', { name: 'Source' })).not.toBeInTheDocument()
    })

    test('advanceSourceChoices keeps the named source even when it is not in the list', () => {
      expect(advanceSourceChoices([], { id: '01X', name: 'Livret fermé' })).toEqual([{ id: '01X', name: 'Livret fermé' }])
      expect(advanceSourceChoices(ACCOUNTS, null).map((choice) => choice.id)).toEqual(['01LIVA', '01PEA'])
      expect(advanceSourceChoices(ACCOUNTS, { id: '01LIVA', name: 'Livret A' })).toHaveLength(2)
    })
  })

  test('a failing read says so instead of showing a wrong state', async () => {
    fetchMock.mockImplementation(() => respond(500, {}))

    renderPanel()

    expect(await screen.findByText('Impossible de lire le virement interne')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'C’est un virement interne' })).not.toBeInTheDocument()
  })
})
