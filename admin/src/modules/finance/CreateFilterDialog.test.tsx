import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { CreateFilterDialog } from './CreateFilterDialog'

const CATEGORIES = [
  { id: '/api/categories/1', name: 'Alimentation', obligation: 'essential' },
  { id: '/api/categories/2', name: 'Loisirs', obligation: 'optional' },
]

const TRANSACTION = {
  id: '/api/transactions/01TX',
  label: 'CARTE 12/10 CARREFOUR MARKET',
  amountCents: -4250,
  category: '/api/categories/1',
}

const fetchMock = vi.fn()
const respond = (ok: boolean, body: unknown) => Promise.resolve({ ok, json: async () => body } as Response)
const create = vi.fn()
const onClose = vi.fn()

const previewPatterns = () =>
  fetchMock.mock.calls.map(([, init]) => (JSON.parse((init as RequestInit).body as string) as { labelPattern: string }).labelPattern)

const renderDialog = (record: { id: string; [key: string]: unknown } = TRANSACTION) =>
  render(
    <AdminContext
      dataProvider={testDataProvider({
        getList: (() => Promise.resolve({ data: CATEGORIES, total: 2 })) as unknown as DataProvider['getList'],
        create: create as unknown as DataProvider['create'],
      })}
    >
      <CreateFilterDialog record={record} onClose={onClose} />
      <Notification />
    </AdminContext>,
  )

describe('CreateFilterDialog', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockImplementation((_url: string, init: RequestInit) => {
      const { labelPattern } = JSON.parse(init.body as string) as { labelPattern: string }

      return respond(true, {
        total: 1,
        changeCount: 1,
        matches: [{ transactionId: 't', label: `Trouvée pour ${labelPattern}`, amountCents: -4250, bookedAt: '2026-10-02', currentCategoryId: null, wouldChange: true }],
      })
    })
    create.mockResolvedValue({ data: { id: '/api/categorization_rules/01R' } })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
    create.mockReset()
    onClose.mockReset()
  })

  test('opens filled from the transaction: the pattern cleaned of its variable parts, the direction, the category', async () => {
    renderDialog()

    const dialog = await screen.findByRole('dialog', { name: 'Créer un filtre' })
    expect(within(dialog).getByLabelText('…ce texte')).toHaveValue('CARREFOUR MARKET')
    expect(within(dialog).getByLabelText('Le libellé…')).toHaveTextContent('Contient')
    expect(within(dialog).getByLabelText('Sens du mouvement')).toHaveTextContent('Dépense')
    await waitFor(() => expect(within(dialog).getByLabelText('Catégorie')).toHaveValue('Alimentation'))
    expect(await within(dialog).findByText('Trouvée pour CARREFOUR MARKET')).toBeInTheDocument()
  })

  test('a credit opens on the « Revenu » direction, a line with no category on an empty one', async () => {
    renderDialog({ ...TRANSACTION, amountCents: 5000, category: null })

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText('Sens du mouvement')).toHaveTextContent('Revenu')
    expect(within(dialog).getByLabelText('Catégorie')).toHaveValue('')
    expect(within(dialog).getByRole('button', { name: 'Créer le filtre' })).toBeDisabled()
  })

  test('the matching lines follow the pattern as it is edited', async () => {
    const user = userEvent.setup()
    renderDialog()
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('Trouvée pour CARREFOUR MARKET')

    const pattern = within(dialog).getByLabelText('…ce texte')
    await user.clear(pattern)
    await user.type(pattern, 'LECLERC')

    expect(await within(dialog).findByText('Trouvée pour LECLERC')).toBeInTheDocument()
    expect(within(dialog).queryByText('Trouvée pour CARREFOUR MARKET')).not.toBeInTheDocument()
    expect(previewPatterns().at(-1)).toBe('LECLERC')
  })

  test('creating posts the rule, says so and closes', async () => {
    const user = userEvent.setup()
    renderDialog()
    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(within(dialog).getByLabelText('Catégorie')).toHaveValue('Alimentation'))

    await user.click(within(dialog).getByRole('button', { name: 'Créer le filtre' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][0]).toBe('categorization_rules')
    expect(create.mock.calls[0][1].data).toMatchObject({
      labelPattern: 'CARREFOUR MARKET',
      matchType: 'contains',
      direction: 'debit',
      category: '/api/categories/1',
      applyToExisting: false,
      isActive: true,
    })
    expect(await screen.findByText('Filtre créé')).toBeInTheDocument()
    expect(onClose).toHaveBeenCalled()
  })

  test('without a label, a direction and an amount bound are enough, and the label is sent as absent', async () => {
    const user = userEvent.setup()
    renderDialog({ ...TRANSACTION, label: 'VIR SEPA XYZ', amountCents: 465000 })
    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(within(dialog).getByLabelText('Catégorie')).toHaveValue('Alimentation'))

    await user.clear(within(dialog).getByLabelText('…ce texte'))
    expect(within(dialog).getByText(/Indiquez un texte à reconnaître, ou un sens/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Créer le filtre' })).toBeDisabled()

    await user.type(within(dialog).getByLabelText('Montant minimum (€)'), '4275')
    await user.type(within(dialog).getByLabelText('Montant maximum (€)'), '5225')
    await user.click(within(dialog).getByRole('button', { name: 'Créer le filtre' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][1].data).toMatchObject({
      labelPattern: null,
      direction: 'credit',
      minAmountCents: 427500,
      maxAmountCents: 522500,
    })
  })

  test('a bound alone is not enough when the direction is open', async () => {
    const user = userEvent.setup()
    renderDialog({ ...TRANSACTION, amountCents: 0 })
    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(within(dialog).getByLabelText('Catégorie')).toHaveValue('Alimentation'))

    await user.clear(within(dialog).getByLabelText('…ce texte'))
    await user.type(within(dialog).getByLabelText('Montant minimum (€)'), '4275')

    expect(within(dialog).getByRole('button', { name: 'Créer le filtre' })).toBeDisabled()
  })

  test('the qualification of the line is offered, can be changed and is sent with the rule', async () => {
    const user = userEvent.setup()
    renderDialog({ ...TRANSACTION, qualification: { mandatory: true, kind: 'repayment' } })
    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(within(dialog).getByLabelText('Catégorie')).toHaveValue('Alimentation'))
    const mandatory = within(dialog).getByRole('switch', { name: 'Obligatoire' })
    expect(mandatory).toBeChecked()
    expect(within(dialog).queryByText(/je rembourse/)).toBeNull()

    await user.click(mandatory)
    await user.click(within(dialog).getByRole('button', { name: 'Créer le filtre' }))

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
    expect(create.mock.calls[0][1].data).toMatchObject({ qualification: { mandatory: false, kind: 'repayment' } })
  })

  test('an API refusal is shown and the dialog stays open', async () => {
    create.mockRejectedValue(new Error('Cette règle existe déjà.'))
    const user = userEvent.setup()
    renderDialog()
    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(within(dialog).getByLabelText('Catégorie')).toHaveValue('Alimentation'))

    await user.click(within(dialog).getByRole('button', { name: 'Créer le filtre' }))

    expect(await within(dialog).findByText('Cette règle existe déjà.')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
