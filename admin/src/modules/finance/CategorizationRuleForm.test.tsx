import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  AdminContext,
  Create,
  Edit,
  ResourceContextProvider,
  testDataProvider,
} from 'react-admin'
import type { DataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { messages } from '../../i18n/messages'
import { CategorizationRuleForm } from './CategorizationRuleForm'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const CATEGORIES = [{ id: '/api/categories/1', name: 'Alimentation' }]

const RULE = {
  id: '/api/categorization_rules/01RULE',
  matchType: 'contains',
  labelPattern: 'CARREFOUR',
  category: '/api/categories/1',
  direction: 'any',
  minAmountCents: null,
  maxAmountCents: null,
  priority: 0,
  isActive: true,
}

const MATCHES = [
  {
    transactionId: 't1',
    label: 'CARREFOUR MARKET 123',
    amountCents: -4250,
    bookedAt: '2026-10-02',
    currentCategoryId: null,
    wouldChange: true,
  },
]

const fetchMock = vi.fn()
const create = vi.fn()
const update = vi.fn()

const respond = (ok: boolean, body: unknown) =>
  Promise.resolve({ ok, json: async () => body } as Response)

const previewBodies = () =>
  fetchMock.mock.calls.map(([, init]) => JSON.parse((init as RequestInit).body as string))

const dataProvider = testDataProvider({
  getList: (() =>
    Promise.resolve({ data: CATEGORIES, total: CATEGORIES.length })) as unknown as DataProvider['getList'],
  getOne: ((resource: string) =>
    Promise.resolve({
      data: resource === 'categorization_rules' ? RULE : CATEGORIES[0],
    })) as unknown as DataProvider['getOne'],
  getMany: (() => Promise.resolve({ data: CATEGORIES })) as unknown as DataProvider['getMany'],
  create: ((...args: unknown[]) => {
    create(...args)

    return Promise.resolve({ data: { id: '/api/categorization_rules/01NEW' } })
  }) as unknown as DataProvider['create'],
  update: ((...args: unknown[]) => {
    update(...args)

    return Promise.resolve({ data: RULE })
  }) as unknown as DataProvider['update'],
})

const renderForm = () =>
  render(
    <AdminContext dataProvider={dataProvider} i18nProvider={i18nProvider}>
      <ResourceContextProvider value="categorization_rules">
        <CategorizationRuleForm withDefaults />
      </ResourceContextProvider>
    </AdminContext>,
  )

const renderCreate = () =>
  render(
    <AdminContext dataProvider={dataProvider} i18nProvider={i18nProvider}>
      <Create resource="categorization_rules" redirect={false}>
        <CategorizationRuleForm withDefaults />
      </Create>
    </AdminContext>,
  )

const renderEdit = () =>
  render(
    <AdminContext dataProvider={dataProvider} i18nProvider={i18nProvider}>
      <Edit
        resource="categorization_rules"
        id={RULE.id}
        mutationMode="pessimistic"
        redirect={false}
      >
        <CategorizationRuleForm />
      </Edit>
    </AdminContext>,
  )

const applyCheckbox = () =>
  screen.findByRole('checkbox', { name: /Appliquer aux transactions existantes/ })

describe('CategorizationRuleForm', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockImplementation(() =>
      respond(true, { total: 1, changeCount: 1, matches: MATCHES }),
    )
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
    create.mockReset()
    update.mockReset()
  })

  test('walks through what the rule recognizes, where it files, and how it arbitrates', async () => {
    renderForm()

    expect(await screen.findByText('Ce que la règle reconnaît')).toBeInTheDocument()
    expect(screen.getByText('Où la classer')).toBeInTheDocument()
    expect(screen.getByText('Restreindre (facultatif)')).toBeInTheDocument()
    expect(screen.getByText('Arbitrage')).toBeInTheDocument()
  })

  test('spells out the two rules a person cannot guess', async () => {
    renderForm()

    // Amounts are stored unsigned, and a manual category wins over the engine.
    expect(
      await screen.findByText(/une dépense de 15,99 € vaut 15,99/i),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/ne sera jamais écrasée par cette règle/i),
    ).toBeInTheDocument()
    expect(screen.getByText(/Plus le nombre est grand/i)).toBeInTheDocument()
  })

  test('prompts for the missing pieces before anything is filled in', async () => {
    renderForm()

    expect(
      await screen.findByText(/Renseignez un texte à reconnaître et une catégorie/i),
    ).toBeInTheDocument()
  })

  test('restates the rule as a sentence once a pattern is typed', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.type(await screen.findByLabelText(/…ce texte/i), 'CARREFOUR')

    expect(await screen.findByText(/CARREFOUR/)).toBeInTheDocument()
  })

  describe('found transactions', () => {
    test('waits for a pattern before looking anything up', async () => {
      renderForm()

      expect(await screen.findByText('Transactions trouvées')).toBeInTheDocument()
      expect(screen.getByText(/Saisissez le texte à reconnaître/)).toBeInTheDocument()
      expect(fetchMock).not.toHaveBeenCalled()
    })

    test('updates the list as the pattern is typed', async () => {
      const user = userEvent.setup()
      renderForm()

      await user.type(await screen.findByLabelText(/…ce texte/i), 'CARREFOUR')

      expect(await screen.findByText('CARREFOUR MARKET 123')).toBeInTheDocument()
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(previewBodies()[0]).toMatchObject({
        labelPattern: 'CARREFOUR',
        matchType: 'contains',
        direction: 'any',
        priority: 0,
        isActive: true,
        ruleId: null,
      })
    })

    test('counts what would change in the checkbox label', async () => {
      fetchMock.mockImplementation(() =>
        respond(true, { total: 5, changeCount: 3, matches: MATCHES }),
      )
      const user = userEvent.setup()
      renderForm()

      expect(
        await screen.findByRole('checkbox', { name: 'Appliquer aux transactions existantes' }),
      ).toBeInTheDocument()
      await user.type(screen.getByLabelText(/…ce texte/i), 'CARREFOUR')

      expect(
        await screen.findByRole('checkbox', {
          name: 'Appliquer aux transactions existantes (3)',
        }),
      ).toBeInTheDocument()
    })

    test('reports a failed preview without blocking the save', async () => {
      fetchMock.mockImplementation(() => respond(false, { error: 'Motif invalide.' }))
      const user = userEvent.setup()
      renderCreate()

      await user.type(await screen.findByLabelText(/…ce texte/i), 'CARREFOUR')

      expect(await screen.findByText(/Motif invalide\./)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeEnabled()
      expect(await applyCheckbox()).toBeEnabled()
    })

    test('reports an unreachable preview without blocking the save', async () => {
      fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
      const user = userEvent.setup()
      renderCreate()

      await user.type(await screen.findByLabelText(/…ce texte/i), 'CARREFOUR')

      expect(await screen.findByText(/L'aperçu est indisponible/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Enregistrer' })).toBeEnabled()
    })
  })

  describe('apply to existing transactions', () => {
    const fillRule = async (user: ReturnType<typeof userEvent.setup>) => {
      await user.type(await screen.findByLabelText(/…ce texte/i), 'CARREFOUR')
      await user.click(screen.getByRole('combobox', { name: /Catégorie/ }))
      await user.click(await screen.findByRole('option', { name: 'Alimentation' }))
    }

    test('sits in the toolbar next to the save button, unchecked', async () => {
      renderCreate()

      const checkbox = await applyCheckbox()
      const save = screen.getByRole('button', { name: 'Enregistrer' })

      expect(checkbox).not.toBeChecked()
      expect(save.parentElement).toContainElement(checkbox)
      expect(screen.queryByRole('button', { name: 'Supprimer' })).not.toBeInTheDocument()
    })

    test('creates the rule without applying it when left unchecked', async () => {
      const user = userEvent.setup()
      renderCreate()
      await fillRule(user)

      await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
      expect(create.mock.calls[0][1].data).toMatchObject({
        labelPattern: 'CARREFOUR',
        category: '/api/categories/1',
        applyToExisting: false,
      })
    })

    test('creates the rule and asks to apply it when checked', async () => {
      const user = userEvent.setup()
      renderCreate()
      await fillRule(user)

      await user.click(await applyCheckbox())
      await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
      expect(create.mock.calls[0][1].data.applyToExisting).toBe(true)
    })

    test('edits with the same checkbox beside save and delete, and sends the rule id to the preview', async () => {
      const user = userEvent.setup()
      renderEdit()

      const checkbox = await applyCheckbox()
      const save = screen.getByRole('button', { name: 'Enregistrer' })
      expect(save.parentElement).toContainElement(checkbox)
      expect(save.parentElement).toContainElement(screen.getByRole('button', { name: 'Supprimer' }))
      expect(checkbox).not.toBeChecked()

      await waitFor(() =>
        expect(previewBodies()[0]).toMatchObject({
          labelPattern: 'CARREFOUR',
          categoryId: '1',
          ruleId: '01RULE',
        }),
      )

      await user.click(checkbox)
      await user.click(save)

      await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
      expect(update.mock.calls[0][1].data.applyToExisting).toBe(true)
    })

    test('edits without applying when the checkbox stays unchecked', async () => {
      const user = userEvent.setup()
      renderEdit()

      await user.type(await screen.findByLabelText(/…ce texte/i), '2')
      await user.click(screen.getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(update).toHaveBeenCalledTimes(1))
      expect(update.mock.calls[0][1].data).toMatchObject({
        labelPattern: 'CARREFOUR2',
        applyToExisting: false,
      })
    })
  })
})
