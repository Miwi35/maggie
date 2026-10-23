import { beforeEach, describe, expect, test, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DndContextProps } from '@dnd-kit/core'
import { emitMercure } from '../../test/mercure'
import { ScenariosPanel } from './ScenariosPanel'

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

// A drag needs a layout engine jsdom lacks: it is finished by calling the `onDragEnd` the picker gave the context.
const dnd: { props?: DndContextProps } = {}
vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>()
  return {
    ...actual,
    DndContext: (props: DndContextProps) => {
      dnd.props = props
      return <actual.DndContext {...props} />
    },
  }
})

type Scenario = Record<string, unknown> & { id: string; name: string; current: boolean; nDate: string | null }

const PROJECTS = [
  { id: 'MOVE', name: 'Déménagement', amountCents: 250000, currency: 'EUR', position: 0, wellbeing: 'essential' },
  { id: 'SOFA', name: 'Canapé', amountCents: 120000, currency: 'EUR', position: 1, wellbeing: 'comfort' },
]

const scenario = (id: string, name: string, nDate: string | null, fields: Record<string, unknown> = {}): Scenario => ({
  id,
  name,
  kind: 'auto',
  status: 'draft',
  current: false,
  projects: [
    { projectId: 'MOVE', position: 0 },
    { projectId: 'SOFA', position: 1 },
  ],
  repayments: [],
  placements: [],
  monthlySaving: null,
  nDate,
  metrics: { projectsDoneBy: '2028-05', debtsEndDate: null, interestPaid: 12345, wellbeing: 7 },
  ...fields,
})

const offers = (): Scenario[] => [
  scenario('auto-rapid', 'Rapide', '2040-06'),
  scenario('auto-balanced', 'Équilibré', '2041-03'),
  scenario('auto-quality', 'Qualité de vie', '2042-09'),
]

const mine = (): Scenario => scenario('01MINE', 'Mon plan', '2041-12', { kind: 'user', status: 'saved' })

interface Call {
  method: string
  url: string
  body: unknown
}

/** The scenarios API over a list in memory. */
function stubApi(initial: Scenario[], options: { failList?: boolean; refuse?: { status: number; error: string }; failComparison?: boolean } = {}) {
  let stored = initial.map((row) => ({ ...row }))
  const calls: Call[] = []
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      const method = init?.method ?? 'GET'
      const body = init?.body ? JSON.parse(String(init.body)) : undefined
      calls.push({ method, url, body })
      const path = url.split('?')[0] as string

      if (url.startsWith('/api/finance/projects')) return json({ member: PROJECTS })
      if (url.startsWith('/api/finance/placements')) return json({ member: [{ id: 'PEA', name: 'PEA long terme', frequency: 'monthly' }] })
      if (url.startsWith('/api/loans')) return json({ member: [{ id: 'LOAN', name: 'Crédit voiture' }] })

      if (path === '/api/finance/scenario-comparison') {
        if (options.failComparison) return json({ error: 'Scenario not found: x' }, 404)
        const ids = (new URL(url, 'http://x').searchParams.get('ids') ?? '').split(',')
        const picked = ids.map((id) => stored.find((row) => row.id === id) as Scenario)
        const reference = picked[0] as Scenario

        return json({
          referenceId: reference.id,
          scenarios: picked.map((row) => ({
            ...row,
            curve: [
              { month: '2026-10', coverage: 0.1 },
              { month: '2026-11', coverage: 0.4 },
              { month: '2026-12', coverage: 1 },
            ],
            milestones: { '25': '2026-11', '50': null, '75': null, '100': '2026-12' },
          })),
          differences: picked.slice(1).map((row) => ({
            scenarioId: row.id,
            againstId: reference.id,
            months: -9,
            sentence: `N 9 mois plus tôt que « ${reference.name} »`,
          })),
        })
      }

      if (method === 'GET' && path === '/api/finance/scenarios') {
        return options.failList ? json({ error: 'boom' }, 500) : json({ scenarios: stored })
      }
      if (method !== 'GET' && options.refuse) return json({ error: options.refuse.error }, options.refuse.status)

      if (method === 'POST' && path === '/api/finance/scenarios') {
        const fields = body as { name: string; projectIds?: string[] }
        const created = scenario('01NEW', fields.name, '2043-01', {
          kind: 'user',
          status: 'saved',
          projects: (fields.projectIds ?? []).map((projectId, position) => ({ projectId, position })),
        })
        stored = [...stored, created]
        return json({ success: true, scenario: created }, 201)
      }
      const id = path.split('/')[4] as string
      const target = stored.find((row) => row.id === id)
      if (!target) return json({ error: `Scenario not found: ${id}` }, 404)

      if (method === 'POST' && path.endsWith('/make-current')) {
        // An offer made current is stored under an id of its own, and its offer goes.
        const kept = target.id.startsWith('auto-') ? { ...target, id: `01${target.id.toUpperCase()}`, status: 'saved' } : target
        stored = stored.filter((row) => row.id !== target.id).map((row) => ({ ...row, current: false }))
        stored = [...stored, { ...kept, current: true }]
        return json({ success: true, scenario: { ...kept, current: true } })
      }
      if (method === 'POST' && path.endsWith('/duplicate')) {
        const copy = scenario('01COPY', `${target.name} (copie)`, target.nDate, { kind: 'user', status: 'saved' })
        stored = [...stored, copy]
        return json({ success: true, scenario: copy }, 201)
      }
      if (method === 'PATCH') {
        const updated = { ...target, ...(body as Record<string, unknown>) }
        stored = stored.map((row) => (row.id === id ? updated : row))
        return json({ success: true, scenario: updated })
      }
      if (method === 'DELETE') {
        stored = stored.filter((row) => row.id !== id)
        return json({ success: true })
      }
      return json({}, 404)
    }),
  )

  return { calls, writes: () => calls.filter((call) => call.method !== 'GET' && !call.url.startsWith('/api/finance/scenario-comparison')) }
}

const cards = () => screen.queryAllByTestId('scenario-card').map((card) => card.getAttribute('data-scenario'))
const card = (name: string) => screen.getAllByTestId('scenario-card').find((candidate) => candidate.getAttribute('data-scenario') === name) as HTMLElement
const loaded = async () => screen.findAllByTestId('scenario-card')

describe('ScenariosPanel', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    localStorage.setItem('token', 'test-jwt')
  })

  test('lists the three offered scenarios and the user own, with N, wellbeing and when the projects are done', async () => {
    stubApi([...offers(), mine()])
    render(<ScenariosPanel />)
    await loaded()

    expect(cards()).toEqual(['Rapide', 'Équilibré', 'Qualité de vie', 'Mon plan'])
    expect(within(card('Rapide')).getByTestId('scenario-n')).toHaveTextContent('N : juin 2040')
    expect(card('Rapide')).toHaveTextContent('Bien-être 7')
    expect(card('Rapide')).toHaveTextContent('Projets bouclés en mai 2028')
    expect(within(card('Rapide')).getByText('Brouillon')).toBeInTheDocument()
    expect(within(card('Mon plan')).queryByText('Brouillon')).not.toBeInTheDocument()
  })

  test('says in a word that no scenario is current, and puts no Courant badge anywhere', async () => {
    stubApi([...offers(), mine()])
    render(<ScenariosPanel />)
    await loaded()

    expect(screen.getByTestId('scenario-current')).toHaveTextContent('Aucun scénario courant')
    expect(screen.queryByText('Courant')).not.toBeInTheDocument()
    expect(screen.queryByText(/Suivre/)).not.toBeInTheDocument()
  })

  test('puts the Courant badge on the current scenario only, and the banner names it with its N', async () => {
    stubApi([...offers(), { ...mine(), current: true }])
    render(<ScenariosPanel />)
    await loaded()

    expect(screen.getByTestId('scenario-current')).toHaveTextContent('Scénario courant · Mon plan · N : décembre 2041')
    expect(screen.getAllByText('Courant')).toHaveLength(1)
    expect(within(card('Mon plan')).getByText('Courant')).toBeInTheDocument()
    expect(within(card('Mon plan')).queryByRole('button', { name: 'Rendre courant' })).not.toBeInTheDocument()
    expect(within(card('Rapide')).getByTestId('scenario-gap')).toHaveTextContent('N 1 an et 6 mois plus tôt que le courant')
  })

  test('makes an offered scenario current: POST make-current, then the list is read again', async () => {
    const api = stubApi([...offers(), mine()])
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    await user.click(within(card('Rapide')).getByRole('button', { name: 'Rendre courant' }))

    await waitFor(() => expect(screen.getByTestId('scenario-current')).toHaveTextContent('Scénario courant · Rapide · N : juin 2040'))
    expect(api.writes().map((call) => `${call.method} ${call.url}`)).toEqual(['POST /api/finance/scenarios/auto-rapid/make-current'])
    expect(within(card('Rapide')).getByText('Courant')).toBeInTheDocument()
    expect(screen.getAllByText('Courant')).toHaveLength(1)
  })

  test('picks the current scenario from the banner when none is', async () => {
    const api = stubApi([...offers(), mine()])
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    await user.click(screen.getByRole('button', { name: 'Choisir le courant' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Mon plan' }))

    await waitFor(() => expect(screen.getByTestId('scenario-current')).toHaveTextContent('Scénario courant · Mon plan'))
    expect(api.writes()[0]?.url).toBe('/api/finance/scenarios/01MINE/make-current')
  })

  test('removes the current scenario: DELETE current-scenario, and none is current again', async () => {
    const api = stubApi([...offers(), { ...mine(), current: true }])
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()
    expect(api.writes()).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Retirer le courant' }))

    await waitFor(() => expect(api.writes()[0]).toMatchObject({ method: 'DELETE', url: '/api/finance/current-scenario' }))
  })

  test.each([
    [404, 'Scenario not found: auto-rapid'],
    [400, 'Un seul scénario peut être courant.'],
  ])('shows the API sentence when making a scenario current is refused with a %i', async (status, error) => {
    stubApi([...offers(), mine()], { refuse: { status, error } })
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    await user.click(within(card('Rapide')).getByRole('button', { name: 'Rendre courant' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(error)
    expect(screen.getByTestId('scenario-current')).toHaveTextContent('Aucun scénario courant')
  })

  test('compares the scenarios in the order they are listed, the current one first, with the API sentences untouched', async () => {
    const api = stubApi([...offers(), { ...mine(), current: true }])
    render(<ScenariosPanel />)

    const table = await screen.findByRole('table', { name: 'Comparaison des scénarios' })

    const asked = api.calls.find((call) => call.url.startsWith('/api/finance/scenario-comparison'))
    expect(asked?.url).toBe('/api/finance/scenario-comparison?ids=01MINE,auto-rapid,auto-balanced,auto-quality')
    const rows = within(table).getAllByTestId('comparison-row')
    expect(rows.map((row) => row.getAttribute('data-scenario'))).toEqual(['Mon plan', 'Rapide', 'Équilibré', 'Qualité de vie'])
    expect(within(rows[0] as HTMLElement).getByText('Référence')).toBeInTheDocument()
    expect(within(rows[1] as HTMLElement).getByText('N 9 mois plus tôt que « Mon plan »')).toBeInTheDocument()
    expect(rows[1]).toHaveTextContent('juin 2040')
    expect(rows[1]).toHaveTextContent('mai 2028')
    expect(rows[1]).toHaveTextContent('123,45')
    for (const heading of ['N', 'Écart de N', 'Bien-être', 'Projets bouclés', 'Fin des dettes', 'Intérêts payés']) {
      expect(within(table).getByRole('columnheader', { name: heading })).toBeInTheDocument()
    }
  })

  test('draws one curve per compared scenario over the same months, and never calls one better', async () => {
    stubApi([...offers(), mine()])
    render(<ScenariosPanel />)

    const curves = await screen.findByTestId('scenario-curves')

    expect(within(curves).getAllByTestId('scenario-curve').map((line) => line.getAttribute('data-scenario'))).toEqual([
      'Rapide',
      'Équilibré',
      'Qualité de vie',
      'Mon plan',
    ])
    expect(screen.getByTestId('scenario-curves-legend')).toHaveTextContent('Qualité de vie')
    expect(screen.getByTestId('scenarios-panel').textContent).not.toMatch(/recommand|meilleur|conseill.*meilleur/i)
  })

  test('compares the chosen scenarios: unchecking one asks the comparison again without it, in the order kept', async () => {
    const api = stubApi([...offers(), mine()])
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await screen.findByTestId('scenario-comparison')

    await user.click(screen.getByRole('checkbox', { name: 'Comparer Équilibré' }))

    await waitFor(() => {
      const last = api.calls.filter((call) => call.url.startsWith('/api/finance/scenario-comparison')).at(-1)
      expect(last?.url).toBe('/api/finance/scenario-comparison?ids=auto-rapid,auto-quality,01MINE')
    })
    await waitFor(() => expect(screen.getAllByTestId('comparison-row')).toHaveLength(3))
  })

  test('compares nothing below two scenarios', async () => {
    stubApi([...offers(), mine()])
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await screen.findByTestId('scenario-comparison')

    for (const name of ['Équilibré', 'Qualité de vie', 'Mon plan']) {
      await user.click(screen.getByRole('checkbox', { name: `Comparer ${name}` }))
    }

    await waitFor(() => expect(screen.queryByTestId('scenario-comparison')).not.toBeInTheDocument())
  })

  test('shows the API sentence when the comparison fails', async () => {
    stubApi([...offers(), mine()], { failComparison: true })
    render(<ScenariosPanel />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Scenario not found: x')
  })

  test('cannot delete or rename an offered scenario, only duplicate it or make it current', async () => {
    stubApi(offers())
    render(<ScenariosPanel />)
    await loaded()

    const rapide = within(card('Rapide'))

    expect(rapide.getByRole('button', { name: 'Dupliquer' })).toBeInTheDocument()
    expect(rapide.getByRole('button', { name: 'Rendre courant' })).toBeInTheDocument()
    expect(rapide.queryByRole('button', { name: 'Supprimer' })).not.toBeInTheDocument()
    expect(rapide.queryByRole('button', { name: 'Modifier' })).not.toBeInTheDocument()
  })

  test('duplicates a scenario: POST duplicate, and the copy appears', async () => {
    const api = stubApi([...offers(), mine()])
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    await user.click(within(card('Rapide')).getByRole('button', { name: 'Dupliquer' }))

    await waitFor(() => expect(cards()).toContain('Rapide (copie)'))
    expect(api.writes()[0]).toMatchObject({ method: 'POST', url: '/api/finance/scenarios/auto-rapid/duplicate' })
    expect(within(card('Rapide (copie)')).queryByText('Courant')).not.toBeInTheDocument()
  })

  test('deletes a scenario of the user after a confirmation, and keeps it when the confirmation is cancelled', async () => {
    const api = stubApi([...offers(), mine()])
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    await user.click(within(card('Mon plan')).getByRole('button', { name: 'Supprimer' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('Supprimer le scénario « Mon plan » ?')
    await user.click(within(dialog).getByRole('button', { name: 'Annuler' }))
    expect(api.writes()).toEqual([])
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(cards()).toContain('Mon plan')

    await user.click(within(card('Mon plan')).getByRole('button', { name: 'Supprimer' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(cards()).not.toContain('Mon plan'))
    expect(api.writes()[0]).toMatchObject({ method: 'DELETE', url: '/api/finance/scenarios/01MINE' })
  })

  test('creates a scenario from every project, in the order of the user, with a monthly saving in cents', async () => {
    const api = stubApi(offers())
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    await user.click(screen.getByRole('button', { name: 'Scénario' }))
    const form = await screen.findByRole('form', { name: 'Nouveau scénario' })
    expect(within(form).getAllByTestId('scenario-project').map((row) => row.getAttribute('data-project'))).toEqual(['Déménagement', 'Canapé'])
    await user.type(within(form).getByLabelText(/^Nom/), 'Japon d’abord')
    await user.type(within(form).getByLabelText('Épargne mensuelle (€)'), '300,50')
    await user.click(within(form).getByRole('checkbox', { name: 'PEA long terme' }))
    await user.click(within(form).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(cards()).toContain('Japon d’abord'))
    expect(api.writes()[0]).toMatchObject({
      method: 'POST',
      url: '/api/finance/scenarios',
      body: { name: 'Japon d’abord', projectIds: ['MOVE', 'SOFA'], placementIds: ['PEA'], monthlySaving: 30050, repayments: [] },
    })
    expect(screen.queryByRole('form')).not.toBeInTheDocument()
  })

  test('reorders, removes and adds back the projects of a scenario, and sends the order kept', async () => {
    const api = stubApi([...offers(), mine()])
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    await user.click(within(card('Mon plan')).getByRole('button', { name: 'Modifier' }))
    const form = await screen.findByRole('form', { name: 'Modifier « Mon plan »' })
    await act(async () => {
      await dnd.props?.onDragEnd?.({ active: { id: 'SOFA' }, over: { id: 'MOVE' } } as never)
    })
    expect(within(form).getAllByTestId('scenario-project').map((row) => row.getAttribute('data-project'))).toEqual(['Canapé', 'Déménagement'])

    await user.click(within(form).getByRole('button', { name: 'Retirer Déménagement' }))
    await user.click(within(form).getByRole('button', { name: 'Déménagement' }))
    await user.click(within(form).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(screen.queryByRole('form')).not.toBeInTheDocument())
    expect(api.writes()[0]).toMatchObject({ method: 'PATCH', url: '/api/finance/scenarios/01MINE', body: { name: 'Mon plan', projectIds: ['SOFA', 'MOVE'] } })
  })

  test('renames a scenario of the user', async () => {
    const api = stubApi([...offers(), mine()])
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    await user.click(within(card('Mon plan')).getByRole('button', { name: 'Modifier' }))
    const form = await screen.findByRole('form', { name: 'Modifier « Mon plan »' })
    const name = within(form).getByLabelText(/^Nom/)
    await user.clear(name)
    await user.type(name, 'Plan B')
    await user.click(within(form).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(cards()).toContain('Plan B'))
    expect(cards()).not.toContain('Mon plan')
    expect(api.writes()[0]).toMatchObject({ method: 'PATCH', body: { name: 'Plan B' } })
  })

  test('sends an early repayment of a loan, a month and an amount, or the whole capital', async () => {
    const api = stubApi(offers())
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    await user.click(screen.getByRole('button', { name: 'Scénario' }))
    const form = await screen.findByRole('form', { name: 'Nouveau scénario' })
    await user.type(within(form).getByLabelText(/^Nom/), 'Je rembourse')
    await user.click(within(form).getByRole('button', { name: 'Ajouter un remboursement' }))
    await user.click(within(form).getByLabelText('Prêt 1'))
    await user.click(await screen.findByRole('option', { name: 'Crédit voiture' }))
    await user.type(within(form).getByLabelText('Mois 1'), '2027-04')
    await user.type(within(form).getByLabelText('Montant 1 (€)'), '3000')
    await user.click(within(form).getByRole('button', { name: 'Ajouter un remboursement' }))
    await user.click(within(form).getAllByLabelText('Prêt 2')[0] as HTMLElement)
    await user.click(await screen.findByRole('option', { name: 'Crédit voiture' }))
    await user.type(within(form).getByLabelText('Mois 2'), '2028-01')
    await user.click(within(form).getAllByRole('checkbox', { name: 'Tout le capital' })[1] as HTMLElement)
    await user.click(within(form).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(cards()).toContain('Je rembourse'))
    expect((api.writes()[0]?.body as { repayments: unknown[] }).repayments).toEqual([
      { loanId: 'LOAN', month: '2027-04', amountCents: 300000 },
      { loanId: 'LOAN', month: '2028-01', amountCents: null },
    ])
  })

  test('refuses a scenario without a name, an incomplete repayment or a wrong saving, and writes nothing', async () => {
    const api = stubApi(offers())
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    await user.click(screen.getByRole('button', { name: 'Scénario' }))
    const form = await screen.findByRole('form', { name: 'Nouveau scénario' })
    await user.click(within(form).getByRole('button', { name: 'Ajouter un remboursement' }))
    await user.type(within(form).getByLabelText('Épargne mensuelle (€)'), 'beaucoup')
    await user.click(within(form).getByRole('button', { name: 'Enregistrer' }))

    expect(await within(form).findByText('Donnez un nom au scénario.')).toBeInTheDocument()
    expect(within(form).getByText('Indiquez un montant positif en euros.')).toBeInTheDocument()
    expect(within(form).getByText(/Chaque remboursement anticipé demande un prêt/)).toBeInTheDocument()
    expect(api.writes()).toEqual([])
  })

  test('keeps the editor open with the API sentence when a save is refused', async () => {
    stubApi([...offers(), mine()], { refuse: { status: 400, error: 'A repayment is above the capital.' } })
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    await user.click(within(card('Mon plan')).getByRole('button', { name: 'Modifier' }))
    const form = await screen.findByRole('form', { name: 'Modifier « Mon plan »' })
    await user.click(within(form).getByRole('button', { name: 'Enregistrer' }))

    expect(await within(form).findByRole('alert')).toHaveTextContent('A repayment is above the capital.')
    expect(screen.getByRole('form')).toBeInTheDocument()
  })

  test('shows an advisor suggestion as a draft: Voir opens it, Ignorer deletes it without asking', async () => {
    const advisor = scenario('01ADVISOR', 'Rembourser d’abord', '2040-01', { kind: 'advisor' })
    const api = stubApi([...offers(), advisor])
    const user = userEvent.setup()
    render(<ScenariosPanel />)
    await loaded()

    const suggestion = within(card('Rembourser d’abord'))
    expect(suggestion.getByText('Suggestion du conseiller · brouillon')).toBeInTheDocument()
    expect(suggestion.queryByRole('button', { name: 'Supprimer' })).not.toBeInTheDocument()

    await user.click(suggestion.getByRole('button', { name: 'Voir' }))
    expect(await screen.findByRole('form', { name: 'Modifier « Rembourser d’abord »' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Annuler' }))

    await user.click(suggestion.getByRole('button', { name: 'Ignorer' }))

    await waitFor(() => expect(cards()).not.toContain('Rembourser d’abord'))
    expect(api.writes()[0]).toMatchObject({ method: 'DELETE', url: '/api/finance/scenarios/01ADVISOR' })
  })

  test('reads the list again when a scenario is published on Mercure', async () => {
    const api = stubApi(offers())
    render(<ScenariosPanel />)
    await loaded()
    const reads = () => api.calls.filter((call) => call.method === 'GET' && call.url === '/api/finance/scenarios').length
    expect(reads()).toBe(1)

    await emitMercure('/api/finance/scenarios/{id}', { '@id': '/api/finance/scenarios/01MINE' })

    await waitFor(() => expect(reads()).toBe(2))
  })

  test('shows a retry when the scenarios cannot be loaded, and loads them on retry', async () => {
    stubApi(offers(), { failList: true })
    const user = userEvent.setup()
    render(<ScenariosPanel />)

    expect(await screen.findByText("Les scénarios n'ont pas pu être chargés.")).toBeInTheDocument()

    stubApi(offers())
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))

    expect(await loaded()).toHaveLength(3)
  })

  test('says there is no scenario when the list is empty', async () => {
    stubApi([])
    render(<ScenariosPanel />)

    expect(await screen.findByText("Aucun scénario pour l'instant.")).toBeInTheDocument()
  })

  test('shows a skeleton while the scenarios load', () => {
    stubApi(offers())
    render(<ScenariosPanel />)

    expect(screen.getByTestId('scenarios-loading')).toBeInTheDocument()
  })
})
