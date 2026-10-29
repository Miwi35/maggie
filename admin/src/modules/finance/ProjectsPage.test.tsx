import { beforeEach, describe, expect, test, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { DndContextProps } from '@dnd-kit/core'
import { emitMercure } from '../../test/mercure'
import { MemoryRouter } from 'react-router-dom'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { messages } from '../../i18n/messages'
import { ProjectsPage } from './ProjectsPage'

const mockNotify = vi.fn()
vi.mock('react-admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-admin')>()),
  useNotify: () => mockNotify,
  Title: () => null,
}))

const subscribed = vi.hoisted(() => new Set<string>())

vi.mock('../../hooks/useMercure', async () => {
  const stand = (await import('../../test/mercure')).mercureModule

  return {
    useMercure: (topics: string[], onMessage: (data?: string) => void) => {
      subscribed.add(topics.join('|'))
      stand.useMercure(topics, onMessage)
    },
  }
})

// The drag itself needs a layout engine jsdom does not have, so a mouse drag is
// finished the way dnd-kit would: by calling the `onDragEnd` the page gave the
// context. The real drag is the Playwright journey's (finance-projects.spec.ts).
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

type Stored = Record<string, unknown> & { id: string; position: number }

const iri = (id: string) => `/api/finance/projects/${id}`

const project = (id: string, fields: Record<string, unknown>): Stored => ({
  '@id': iri(id),
  id,
  currency: 'EUR',
  dependsOn: [],
  note: null,
  unmetDependencies: [],
  ...fields,
}) as unknown as Stored

const MOVE = project('MOVE', {
  name: 'Déménagement',
  amountCents: 250000,
  wellbeing: 'essential',
  wellbeingScore: 4,
  position: 0,
  dateMode: 'fixed',
  targetOn: '2027-03-01',
})
const SOFA = project('SOFA', {
  name: 'Canapé',
  amountCents: 120000,
  wellbeing: 'comfort',
  wellbeingScore: 1,
  position: 1,
  dateMode: 'none',
  targetOn: null,
  dependsOn: [iri('MOVE')],
  note: 'Un vrai, cette fois',
})
const BIKE = project('BIKE', {
  name: 'Vélo',
  amountCents: 90000,
  wellbeing: 'high',
  wellbeingScore: 3,
  position: 2,
  dateMode: 'deadline',
  targetOn: '2027-08-31',
})

const SCORES: Record<string, number> = { essential: 4, high: 3, medium: 2, comfort: 1 }

/** The 36-month planning over the stored order: each project saves two months, buys the third. */
const planningOf = (rows: Stored[], overrides: Record<string, unknown> = {}) => {
  const month = (offset: number) => {
    const total = 2026 * 12 + 9 + offset
    return `${Math.floor(total / 12)}-${String((total % 12) + 1).padStart(2, '0')}`
  }

  return {
    startMonth: '2026-10',
    horizonMonths: 36,
    currency: 'EUR',
    netCapacityCents: 50000,
    isIncomeKnown: true,
    cushion: { state: 'complete', completeMonth: null },
    months: [],
    projects: [...rows]
      .sort((a, b) => a.position - b.position)
      .map((row, index) => ({
        projectId: row.id,
        name: row.name,
        position: row.position,
        wellbeing: row.wellbeing,
        amountCents: row.amountCents,
        dateMode: row.dateMode,
        targetOn: row.targetOn,
        savingFromMonth: month(index * 3),
        purchaseMonth: month(index * 3 + 2),
        gatheredCents: 0,
        loan: null,
        isBeyondHorizon: false,
        isDeadlineMissed: false,
        delayMonths: 0,
        unmetDependencies: row.unmetDependencies,
      })),
    ...overrides,
  }
}

interface Write {
  method: string
  url: string
  body: unknown
}

/** The projects API over a list in memory; `refuse` answers every write but the reorder with that error. */
function stubApi(
  initial: Stored[],
  options: {
    failReorder?: boolean
    failLoad?: boolean
    refuse?: { status: number; body: unknown }
    /** Answers `project-planning` over the stored order; without it the answer is not a planning. */
    planning?: boolean | Record<string, unknown>
    failPlanning?: boolean
    /** Answers `/api/finance/independence` (MAG-558); without it the answer is not a counter. */
    independence?: unknown
    /** Answers `POST …/projects/preview` (MAG-553); without it a preview with no current scenario. */
    preview?: Record<string, unknown> | ((body: Record<string, unknown>) => Record<string, unknown>)
    failPreview?: { status: number; body: unknown }
    /** Answers `GET /api/finance/scenarios`. */
    scenarios?: Record<string, unknown>[]
  } = {},
) {
  let stored = initial.map((row) => ({ ...row }))
  const writes: Write[] = []
  const previews: Record<string, unknown>[] = []
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    if (method === 'POST' && url.endsWith('/projects/preview')) {
      previews.push(body)
      if (options.failPreview) return json(options.failPreview.body, options.failPreview.status)
      const answer = typeof options.preview === 'function' ? options.preview(body) : options.preview
      return json(
        answer ?? {
          scenario: null,
          project: { name: body.name, amountCents: body.amountCents, currency: 'EUR' },
          dates: stored.map((row) => ({ projectId: row.id, name: row.name, before: '2027-03', after: '2027-03' })),
          nDate: { before: '2041-03', after: '2041-03' },
          metrics: { realCost: body.amountCents, availableAt: '2027-09', nImpactMonths: 0 },
        },
      )
    }
    if (method === 'GET' && url.endsWith('/api/finance/scenarios')) return json({ scenarios: options.scenarios ?? [] })
    if (method !== 'GET') writes.push({ method, url, body })
    if (method === 'PATCH' && url.includes('/api/finance/scenarios/')) return json({ scenario: { id: url.split('/').pop() } })

    if (method === 'GET' && url.includes('/project-planning')) {
      if (options.failPlanning) return json({}, 500)
      if (options.planning) return json(planningOf(stored, options.planning === true ? {} : options.planning))
    }
    if (method === 'GET' && url.includes('/finance/independence') && options.independence !== undefined) {
      return json(options.independence)
    }
    if (method === 'GET') {
      if (options.failLoad) return json({}, 500)
      return json({ member: [...stored].sort((a, b) => a.position - b.position) })
    }
    if (url.endsWith('/reorder')) {
      if (options.failReorder) return json({ error: 'Le nouvel ordre ne nomme pas tous les projets.' }, 400)
      const ids = (body as { ids: string[] }).ids
      stored = ids.map((id, position) => ({ ...(stored.find((row) => row.id === id) as Stored), position }))
      return json({ success: true, projects: stored })
    }
    if (options.refuse) return json(options.refuse.body, options.refuse.status)
    if (url.endsWith('/investment')) {
      const id = url.split('/').slice(-2)[0]
      const investment =
        method === 'PUT'
          ? { priceCents: 120000, downPaymentCents: 20000, loanId: 'L1', loanPaymentCents: 1000, monthlyRentCents: 5000, monthlyBalanceCents: 4000, resale: null }
          : null
      stored = stored.map((row) => (row.id === id ? { ...row, investment } : row))
      return json({ success: true, project: stored.find((row) => row.id === id) })
    }
    if (method === 'POST') {
      const fields = body as Record<string, unknown>
      const created = project('NEW', { ...fields, wellbeingScore: SCORES[String(fields.wellbeing)], position: stored.length })
      stored.push(created)
      return json(created, 201)
    }
    const id = url.split('/').pop() as string
    if (method === 'PATCH') {
      const fields = body as Record<string, unknown>
      const updated = { ...(stored.find((row) => row.id === id) as Stored), ...fields, wellbeingScore: SCORES[String(fields.wellbeing)] }
      stored = stored.map((row) => (row.id === id ? updated : row))
      return json(updated)
    }
    stored = stored.filter((row) => row.id !== id)
    return new Response(null, { status: 204 })
  })
  vi.stubGlobal('fetch', fetchMock)

  return { writes, previews, fetchMock }
}

const shown = () => screen.queryAllByTestId('project-row').map((row) => row.getAttribute('data-project'))
const row = (name: string) => screen.getAllByTestId('project-row').find((candidate) => candidate.getAttribute('data-project') === name) as HTMLElement
const handle = (name: string) => screen.getByRole('button', { name: new RegExp(`^Déplacer ${name},`) })

const loaded = async (count: number) => waitFor(() => expect(shown()).toHaveLength(count))

const drag = (active: string, over: string) =>
  act(async () => {
    await dnd.props?.onDragEnd?.({ active: { id: active }, over: { id: over } } as never)
  })

describe('ProjectsPage', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    mockNotify.mockReset()
    localStorage.setItem('token', 'test-jwt')
  })

  test('lists the projects in priority order, with their amount, wellbeing, date and dependencies', async () => {
    stubApi([BIKE, MOVE, SOFA])
    render(<ProjectsPage />)
    await loaded(3)

    expect(shown()).toEqual(['Déménagement', 'Canapé', 'Vélo'])
    const move = within(row('Déménagement'))
    expect(move.getByText('1. Déménagement')).toBeInTheDocument()
    expect(move.getByText('Indispensable · 4')).toBeInTheDocument()
    expect(move.getByText(/2\s500,00\s€ · le 1er mars 2027/)).toBeInTheDocument()

    const sofa = within(row('Canapé'))
    expect(sofa.getByText('Confort · 1')).toBeInTheDocument()
    expect(sofa.getByText(/Sans date · après Déménagement/)).toBeInTheDocument()
    expect(sofa.getByText('Un vrai, cette fois')).toBeInTheDocument()

    expect(within(row('Vélo')).getByText(/au plus tard le 31 août 2027/)).toBeInTheDocument()
    expect(within(row('Vélo')).getByText('Élevé · 3')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  test('holds two Mercure streams for the projects, the planning and the scenarios, not three: a browser keeps six connections per origin', async () => {
    subscribed.clear()
    stubApi([MOVE, SOFA])
    render(<ProjectsPage />)
    await loaded(2)
    await screen.findByTestId('scenarios-panel')

    expect(subscribed.size).toBeLessThanOrEqual(2)
  })

  test('says when there is no project yet', async () => {
    stubApi([])
    render(<ProjectsPage />)

    expect(await screen.findByText("Aucun projet pour l'instant.")).toBeInTheDocument()
  })

  test('says when the projects could not be read, and reads them again on demand', async () => {
    const user = userEvent.setup()
    stubApi([MOVE], { failLoad: true })
    render(<ProjectsPage />)

    expect(await screen.findByText("Les projets n'ont pas pu être chargés.")).toBeInTheDocument()
    stubApi([MOVE])
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))
    await loaded(1)
  })

  test('warns when a project is dated before a project it waits for', async () => {
    stubApi([MOVE, { ...SOFA, dateMode: 'fixed', targetOn: '2027-01-15', unmetDependencies: ['MOVE'] }])
    render(<ProjectsPage />)
    await loaded(2)

    const warning = within(row('Canapé')).getByRole('alert')
    expect(warning).toHaveTextContent('Dépendance non respectée : Déménagement est prévu après.')
    expect(within(row('Déménagement')).queryByRole('alert')).toBeNull()
  })

  test('creates a project with a deadline and a dependency', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([MOVE, SOFA])
    render(<ProjectsPage />)
    await loaded(2)

    await user.click(screen.getByRole('button', { name: 'Nouveau projet' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/^Nom/), 'Vélo cargo')
    await user.type(within(dialog).getByLabelText(/^Montant/), '1 500,50')
    await user.click(within(dialog).getByRole('combobox', { name: 'Bien-être' }))
    await user.click(await screen.findByRole('option', { name: 'Élevé (3)' }))
    await user.click(within(dialog).getByRole('radio', { name: 'Date limite (au plus tard)' }))
    fireEvent.change(within(dialog).getByLabelText(/^Date limite\W*$/), { target: { value: '2027-08-31' } })
    await user.click(within(dialog).getByRole('checkbox', { name: 'Déménagement' }))
    expect(within(dialog).getByRole('toolbar', { name: /Note/ })).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Note'), 'Pour les courses')
    await user.click(within(dialog).getByRole('button', { name: 'Aperçu' }))
    await user.click(await within(dialog).findByRole('button', { name: 'Ajouter' }))

    await waitFor(() => expect(shown()).toEqual(['Déménagement', 'Canapé', 'Vélo cargo']))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(writes).toEqual([
      {
        method: 'POST',
        url: '/api/finance/projects',
        body: {
          name: 'Vélo cargo',
          amountCents: 150050,
          currency: 'EUR',
          wellbeing: 'high',
          dateMode: 'deadline',
          targetOn: '2027-08-31',
          dependsOn: ['/api/finance/projects/MOVE'],
          note: 'Pour les courses',
        },
      },
    ])
    expect(within(row('Vélo cargo')).getByText(/au plus tard le 31 août 2027 · après Déménagement/)).toBeInTheDocument()
  })

  test('refuses a fixed date left empty, a missing name and a zero amount before sending anything', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([MOVE])
    render(<ProjectsPage />)
    await loaded(1)

    await user.click(screen.getByRole('button', { name: 'Nouveau projet' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/^Montant/), '0')
    await user.click(within(dialog).getByRole('radio', { name: 'Date fixe (verrouillée)' }))
    await user.click(within(dialog).getByRole('button', { name: 'Aperçu' }))

    expect(within(dialog).getByText('Le nom est obligatoire.')).toBeInTheDocument()
    expect(within(dialog).getByText('Indiquez un montant supérieur à 0.')).toBeInTheDocument()
    expect(within(dialog).getByText('Choisissez la date du projet.')).toBeInTheDocument()
    expect(writes).toEqual([])
  })

  test("shows the API's refusal in the form, in French", async () => {
    const user = userEvent.setup()
    stubApi([MOVE, SOFA], {
      refuse: { status: 400, body: { detail: 'These dependencies would make a cycle: a project would end up waiting for itself.' } },
    })
    render(<ProjectsPage />)
    await loaded(2)

    await user.click(screen.getByRole('button', { name: 'Modifier Déménagement' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Canapé' }))
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    expect(
      await within(dialog).findByText('Ces dépendances formeraient un cycle : un projet finirait par attendre lui-même.'),
    ).toBeInTheDocument()
  })

  test('puts a field the API blamed under that field', async () => {
    const user = userEvent.setup()
    stubApi([MOVE], {
      refuse: { status: 422, body: { violations: [{ propertyPath: 'targetOn', message: 'targetOn is required for a fixed date.' }] } },
    })
    render(<ProjectsPage />)
    await loaded(1)

    await user.click(screen.getByRole('button', { name: 'Modifier Déménagement' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    expect(await within(dialog).findByText('targetOn is required for a fixed date.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText(/^Date du projet/)).toHaveAttribute('aria-invalid', 'true')
  })

  test('edits a project: giving « Canapé » a deadline shows it in the list', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([MOVE, SOFA])
    render(<ProjectsPage />)
    await loaded(2)

    await user.click(screen.getByRole('button', { name: 'Modifier Canapé' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText(/^Nom/)).toHaveValue('Canapé')
    expect(within(dialog).getByLabelText(/^Montant/)).toHaveValue('1200')
    expect(within(dialog).getByRole('checkbox', { name: 'Déménagement' })).toBeChecked()
    await user.click(within(dialog).getByRole('radio', { name: 'Date limite (au plus tard)' }))
    fireEvent.change(within(dialog).getByLabelText(/^Date limite\W*$/), { target: { value: '2027-08-31' } })
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(within(row('Canapé')).getByText(/au plus tard le 31 août 2027/)).toBeInTheDocument())
    expect(writes).toEqual([
      {
        method: 'PATCH',
        url: '/api/finance/projects/SOFA',
        body: expect.objectContaining({ dateMode: 'deadline', targetOn: '2027-08-31', amountCents: 120000, dependsOn: [iri('MOVE')] }),
      },
    ])
  })

  test('deletes a project once confirmed, and the others stop waiting for it', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([MOVE, SOFA])
    render(<ProjectsPage />)
    await loaded(2)

    await user.click(screen.getByRole('button', { name: 'Supprimer Déménagement' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Supprimer le projet « Déménagement » ?')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(shown()).toEqual(['Canapé']))
    expect(within(row('Canapé')).queryByText(/après Déménagement/)).toBeNull()
    expect(writes).toEqual([{ method: 'DELETE', url: '/api/finance/projects/MOVE', body: undefined }])
  })

  test('a mouse drag moves the project at once and sends the whole new order', async () => {
    const { writes } = stubApi([MOVE, SOFA, BIKE])
    render(<ProjectsPage />)
    await loaded(3)

    await drag('BIKE', 'MOVE')

    expect(shown()).toEqual(['Vélo', 'Déménagement', 'Canapé'])
    expect(within(row('Vélo')).getByText('1. Vélo')).toBeInTheDocument()
    expect(writes).toEqual([{ method: 'POST', url: '/api/finance/projects/reorder', body: { ids: ['BIKE', 'MOVE', 'SOFA'] } }])
  })

  test('the keyboard moves a project: Espace to pick it up, a flèche, Espace to drop it', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([MOVE, SOFA, BIKE])
    render(<ProjectsPage />)
    await loaded(3)

    handle('Canapé').focus()
    await user.keyboard(' ')
    expect(handle('Canapé')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText(/Canapé saisi, priorité 2 sur 3/)).toBeInTheDocument()

    await user.keyboard('{ArrowUp}')
    expect(shown()).toEqual(['Canapé', 'Déménagement', 'Vélo'])
    expect(writes).toEqual([])
    expect(handle('Canapé')).toHaveFocus()

    await user.keyboard(' ')
    expect(screen.getByText('Canapé déposé en priorité 1 sur 3.')).toBeInTheDocument()
    await waitFor(() =>
      expect(writes).toEqual([{ method: 'POST', url: '/api/finance/projects/reorder', body: { ids: ['SOFA', 'MOVE', 'BIKE'] } }]),
    )
    expect(handle('Canapé')).toHaveAttribute('aria-pressed', 'false')
  })

  test('Échap puts the project back where it was and sends nothing', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([MOVE, SOFA, BIKE])
    render(<ProjectsPage />)
    await loaded(3)

    handle('Déménagement').focus()
    await user.keyboard(' {ArrowDown}{ArrowDown}')
    expect(shown()).toEqual(['Canapé', 'Vélo', 'Déménagement'])
    await user.keyboard('{Escape}')

    expect(shown()).toEqual(['Déménagement', 'Canapé', 'Vélo'])
    expect(screen.getByText('Déplacement annulé : Déménagement reste à sa place.')).toBeInTheDocument()
    expect(writes).toEqual([])
  })

  test('a Mercure update while a project is carried does not undo the move', async () => {
    const user = userEvent.setup()
    stubApi([MOVE, SOFA, BIKE])
    render(<ProjectsPage />)
    await loaded(3)

    handle('Vélo').focus()
    await user.keyboard(' {ArrowUp}')
    expect(shown()).toEqual(['Déménagement', 'Vélo', 'Canapé'])

    await emitMercure('/api/finance/projects/{id}', { '@id': iri('MOVE'), note: 'Le 1er mars' })

    expect(shown()).toEqual(['Déménagement', 'Vélo', 'Canapé'])
    expect(handle('Vélo')).toHaveFocus()
  })

  test('leaving the handle while carrying a project puts it back and sends nothing', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([MOVE, SOFA, BIKE])
    render(<ProjectsPage />)
    await loaded(3)

    handle('Déménagement').focus()
    await user.keyboard(' {ArrowDown}')
    expect(shown()).toEqual(['Canapé', 'Déménagement', 'Vélo'])
    expect(handle('Déménagement')).toHaveFocus()

    await user.click(screen.getByRole('button', { name: 'Nouveau projet' }))
    await user.keyboard('{Escape}')

    await waitFor(() => expect(shown()).toEqual(['Déménagement', 'Canapé', 'Vélo']))
    expect(handle('Déménagement')).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByText('Déplacement annulé : Déménagement reste à sa place.')).toBeInTheDocument()
    expect(writes).toEqual([])
  })

  test('a refused order says so and puts the projects back', async () => {
    stubApi([MOVE, SOFA, BIKE], { failReorder: true })
    render(<ProjectsPage />)
    await loaded(3)

    await drag('SOFA', 'MOVE')

    await waitFor(() =>
      expect(mockNotify).toHaveBeenCalledWith('Le nouvel ordre ne nomme pas tous les projets.', { type: 'error' }),
    )
    expect(shown()).toEqual(['Déménagement', 'Canapé', 'Vélo'])
  })

  test('a project changed elsewhere (the phone, Maggie) is redrawn from its Mercure update, without a read', async () => {
    const { fetchMock } = stubApi([MOVE, SOFA, BIKE])
    render(<ProjectsPage />)
    await loaded(3)
    const reads = fetchMock.mock.calls.length

    // A reorder from the phone: one update per project, each with its new position.
    await emitMercure('/api/finance/projects/{id}', { '@id': iri('BIKE'), position: 0 })
    await emitMercure('/api/finance/projects/{id}', { '@id': iri('MOVE'), position: 1 })
    await emitMercure('/api/finance/projects/{id}', { '@id': iri('SOFA'), position: 2 })
    await waitFor(() => expect(shown()).toEqual(['Vélo', 'Déménagement', 'Canapé']))

    await emitMercure('/api/finance/projects/{id}', {
      '@id': iri('SOFA'),
      dateMode: 'fixed',
      targetOn: '2027-01-15',
      unmetDependencies: ['MOVE'],
    })
    expect(within(row('Canapé')).getByRole('alert')).toHaveTextContent('Déménagement est prévu après')

    await emitMercure('/api/finance/projects/{id}', {
      '@id': iri('TRIP'),
      name: 'Voyage',
      amountCents: 200000,
      currency: 'EUR',
      wellbeing: 'medium',
      wellbeingScore: 2,
      position: 3,
      dateMode: 'none',
      targetOn: null,
      dependsOn: [],
      note: null,
      unmetDependencies: [],
    })
    expect(shown()).toEqual(['Vélo', 'Déménagement', 'Canapé', 'Voyage'])

    await emitMercure('/api/finance/projects/{id}', { '@id': iri('MOVE'), deleted: true })
    expect(shown()).toEqual(['Vélo', 'Canapé', 'Voyage'])
    expect(fetchMock.mock.calls.length).toBe(reads)
  })

  test('an update for a project it does not know reads the list again', async () => {
    const { fetchMock } = stubApi([MOVE])
    render(<ProjectsPage />)
    await loaded(1)
    const reads = fetchMock.mock.calls.length

    await emitMercure('/api/finance/projects/{id}', { '@id': iri('ELSEWHERE'), position: 4 })

    await waitFor(() => expect(fetchMock.mock.calls.length).toBe(reads + 1))
  })
  describe('one page, no sub-tabs (MAG-633)', () => {
    test('the projects, their planning and the scenarios are on the same page, with no Planning · Scénarios tabs nor help text', async () => {
      stubApi([BIKE, MOVE], { planning: true, scenarios: [] })
      render(<ProjectsPage />)
      await loaded(2)

      expect(screen.queryByRole('tab')).toBeNull()
      expect(screen.queryByText(/Le premier passe avant les autres/)).toBeNull()
      expect(await screen.findByTestId('scenarios-panel')).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: 'Scénarios' })).toBeInTheDocument()
    })
  })

  describe('planning Gantt (MAG-527)', () => {
    const ganttRows = () => screen.queryAllByTestId('gantt-row').map((node) => node.getAttribute('data-project'))
    const planningReads = (fetchMock: ReturnType<typeof stubApi>['fetchMock']) =>
      fetchMock.mock.calls.filter(([input]) => String(input).includes('/project-planning')).length

    test('draws one line per project under the list, in priority order', async () => {
      stubApi([BIKE, MOVE, SOFA], { planning: true })
      render(<ProjectsPage />)
      await loaded(3)

      await waitFor(() => expect(ganttRows()).toEqual(['Déménagement', 'Canapé', 'Vélo']))
      expect(screen.getByRole('heading', { name: 'Planning' })).toBeInTheDocument()
    })

    test('draws the funding chart under the Gantt from the months of the planning (MAG-528)', async () => {
      const funded = Array.from({ length: 36 }, (_, index) => ({
        month: `${2026 + Math.floor((9 + index) / 12)}-${String(((9 + index) % 12) + 1).padStart(2, '0')}`,
        capacityCents: 50000,
        cushionCents: 0,
        salaryCents: index < 3 ? 200000 : 0,
        savingsCents: 0,
        capitalCents: 0,
        loanPaymentsCents: 0,
        isOverCapacity: index < 3,
      }))
      stubApi([BIKE, MOVE], { planning: { months: funded } })
      render(<ProjectsPage />)
      await loaded(2)

      const chart = await screen.findByTestId('funding-chart')
      expect(within(screen.getByTestId('project-gantt')).getByTestId('funding-chart')).toBe(chart)
      expect(within(chart).getAllByTestId('funding-over')).toHaveLength(3)
    })

    test('keeps the Gantt without the chart when the planning carries no month', async () => {
      stubApi([BIKE, MOVE], { planning: true })
      render(<ProjectsPage />)
      await loaded(2)

      await waitFor(() => expect(ganttRows()).toHaveLength(2))
      expect(screen.queryByTestId('funding-chart')).toBeNull()
    })

    test('reads the planning again after a reorder: the dropped project comes first', async () => {
      const { fetchMock } = stubApi([MOVE, SOFA, BIKE], { planning: true })
      render(<ProjectsPage />)
      await loaded(3)
      await waitFor(() => expect(ganttRows()).toEqual(['Déménagement', 'Canapé', 'Vélo']))
      const before = planningReads(fetchMock)

      await drag('BIKE', 'MOVE')

      await waitFor(() => expect(ganttRows()).toEqual(['Vélo', 'Déménagement', 'Canapé']))
      expect(planningReads(fetchMock)).toBeGreaterThan(before)
      expect(screen.getByTestId('project-gantt').querySelector('[data-project="Vélo"][data-testid="gantt-saving"]')).toHaveAttribute('data-from', '0')
    })

    test('reads the planning again after a project is created and after one is deleted', async () => {
      const user = userEvent.setup()
      const { fetchMock } = stubApi([MOVE, SOFA], { planning: true })
      render(<ProjectsPage />)
      await loaded(2)
      await waitFor(() => expect(ganttRows()).toEqual(['Déménagement', 'Canapé']))

      await user.click(screen.getByRole('button', { name: 'Nouveau projet' }))
      const dialog = await screen.findByRole('dialog')
      await user.type(within(dialog).getByLabelText(/^Nom/), 'Vélo cargo')
      await user.type(within(dialog).getByLabelText(/^Montant/), '1500')
      await user.click(within(dialog).getByRole('button', { name: 'Aperçu' }))
      await user.click(await within(dialog).findByRole('button', { name: 'Ajouter' }))
      await waitFor(() => expect(ganttRows()).toEqual(['Déménagement', 'Canapé', 'Vélo cargo']))

      const afterCreate = planningReads(fetchMock)
      await user.click(screen.getByRole('button', { name: 'Supprimer Canapé' }))
      await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Supprimer' }))
      await waitFor(() => expect(ganttRows()).toEqual(['Déménagement', 'Vélo cargo']))
      expect(planningReads(fetchMock)).toBeGreaterThan(afterCreate)
    })

    test('reads the planning again when a loan, the cushion or an account is published', async () => {
      const { fetchMock } = stubApi([MOVE, SOFA], { planning: true })
      render(<ProjectsPage />)
      await loaded(2)
      await waitFor(() => expect(ganttRows()).toHaveLength(2))

      for (const topic of ['/api/loans/{id}', '/api/safety_cushions/{id}', '/api/accounts/{id}']) {
        const before = planningReads(fetchMock)
        await emitMercure(topic, { '@id': topic.replace('{id}', 'X') })
        await waitFor(() => expect(planningReads(fetchMock)).toBeGreaterThan(before))
      }
    })

    test('shows no Gantt, and no alert, while the income is unknown', async () => {
      stubApi([MOVE, SOFA], { planning: { isIncomeKnown: false } })
      render(<ProjectsPage />)
      await loaded(2)

      await waitFor(() => expect(screen.queryByTestId('gantt-loading')).toBeNull())
      expect(screen.queryByTestId('project-gantt')).toBeNull()
      expect(screen.queryByTestId('project-planning')).toBeNull()
      expect(screen.queryByText("Le planning n'a pas pu être chargé.")).toBeNull()
    })

    test('says when the planning could not be read, and reads it again on demand', async () => {
      const user = userEvent.setup()
      stubApi([MOVE, SOFA], { failPlanning: true })
      render(<ProjectsPage />)
      await loaded(2)

      expect(await screen.findByText("Le planning n'a pas pu être chargé.")).toBeInTheDocument()
      expect(shown()).toEqual(['Déménagement', 'Canapé'])

      stubApi([MOVE, SOFA], { planning: true })
      await user.click(within(screen.getByTestId('project-planning')).getByRole('button', { name: 'Réessayer' }))

      await waitFor(() => expect(ganttRows()).toEqual(['Déménagement', 'Canapé']))
      expect(screen.queryByText("Le planning n'a pas pu être chargé.")).toBeNull()
    })
  })

  describe('investments (MAG-557)', () => {
    // The detail panel is made of react-admin forms: it needs a router and an admin context.
    const renderOpenable = () =>
      render(
        <MemoryRouter>
          <AdminContext dataProvider={testDataProvider()} i18nProvider={polyglotI18nProvider(() => messages, 'fr')}>
            <ResourceContextProvider value="projects">
              <ProjectsPage />
            </ResourceContextProvider>
          </AdminContext>
        </MemoryRouter>,
      )

    const FLAT = project('FLAT', {
      name: 'Box à louer',
      amountCents: 1800000,
      wellbeing: 'medium',
      wellbeingScore: 2,
      position: 3,
      dateMode: 'none',
      targetOn: null,
      investment: {
        priceCents: 1800000,
        downPaymentCents: 300000,
        loanId: 'L1',
        loanPaymentCents: 14000,
        monthlyRentCents: 12000,
        monthlyBalanceCents: -2000,
        resale: null,
      },
    })

    test('flags an investment in the list and opens its card with the funding', async () => {
      const user = userEvent.setup()
      stubApi([MOVE, FLAT])
      renderOpenable()
      await loaded(2)

      expect(within(row('Box à louer')).getByTestId('project-investment-chip')).toHaveTextContent('Investissement')
      expect(within(row('Déménagement')).queryByTestId('project-investment-chip')).toBeNull()

      await user.click(screen.getByRole('button', { name: 'Financement de Box à louer' }))

      const card = await screen.findByTestId('investment-card')
      expect(within(card).getByRole('heading', { name: 'Investissement · Box à louer' })).toBeInTheDocument()
      expect(within(card).getAllByTestId('investment-figure')).toHaveLength(6)
    })

    const rente = (coveragePoints: number | null) => ({
      renteSources: {
        withdrawalRateBasisPoints: 400,
        placements: { count: 0, monthlyRentCents: 0, items: [] },
        investments: {
          count: 1,
          monthlyRentCents: 16000,
          items: [{ projectId: 'FLAT', name: 'Box à louer', monthlyRentCents: 16000, fromMonth: '2027-03', untilMonth: null, coveragePoints }],
        },
      },
    })

    test('says the rent the independence projection counts for the investment, with its points', async () => {
      const user = userEvent.setup()
      stubApi([MOVE, FLAT], { independence: rente(9) })
      renderOpenable()
      await loaded(2)

      await user.click(screen.getByRole('button', { name: 'Financement de Box à louer' }))

      const line = await screen.findByTestId('investment-rente-counted')
      expect(line.textContent).toMatch(/^Rente comptée : 160\s€ \/ mois de loyer · indépendance \+ 9 points$/)
    })

    test('omits the line when the projection gives no points, or when the read fails', async () => {
      const user = userEvent.setup()
      stubApi([MOVE, FLAT], { independence: rente(null) })
      renderOpenable()
      await loaded(2)

      await user.click(screen.getByRole('button', { name: 'Financement de Box à louer' }))
      await screen.findByTestId('investment-card')
      expect(screen.queryByTestId('investment-rente-counted')).toBeNull()
    })

    test('turns a project into an investment from its card, then reads the list and the planning again', async () => {
      const user = userEvent.setup()
      const { writes, fetchMock } = stubApi([MOVE, SOFA], { planning: true })
      renderOpenable()
      await loaded(2)

      await user.click(screen.getByRole('button', { name: 'Financement de Canapé' }))
      await user.click(await screen.findByRole('button', { name: 'Définir un investissement' }))
      const form = await screen.findByRole('form', { name: 'Investissement · Canapé' })
      const reads = fetchMock.mock.calls.length
      fireEvent.change(within(form).getByLabelText(/^Apport/), { target: { value: '1200' } })
      fireEvent.change(within(form).getByLabelText(/^Loyer mensuel/), { target: { value: '50' } })
      await user.click(within(form).getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(within(row('Canapé')).getByTestId('project-investment-chip')).toBeInTheDocument())
      expect(writes).toEqual([
        { method: 'PUT', url: '/api/finance/projects/SOFA/investment', body: { priceCents: 120000, downPaymentCents: 120000, monthlyRentCents: 5000 } },
      ])
      await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(reads + 2))
    })

    test('says nothing was saved when the API refuses', async () => {
      const user = userEvent.setup()
      stubApi([MOVE, SOFA], { refuse: { status: 400, body: { error: 'The investment is missing: monthlyRentCents.' } } })
      renderOpenable()
      await loaded(2)

      await user.click(screen.getByRole('button', { name: 'Financement de Canapé' }))
      await user.click(await screen.findByRole('button', { name: 'Définir un investissement' }))
      const form = await screen.findByRole('form', { name: 'Investissement · Canapé' })
      fireEvent.change(within(form).getByLabelText(/^Apport/), { target: { value: '1200' } })
      fireEvent.change(within(form).getByLabelText(/^Loyer mensuel/), { target: { value: '0' } })
      await user.click(within(form).getByRole('button', { name: 'Enregistrer' }))

      expect(await within(form).findByRole('alert')).toBeInTheDocument()
      expect(within(row('Canapé')).queryByTestId('project-investment-chip')).toBeNull()
    })
  })

  describe('preview before adding (MAG-553)', () => {
    const CURRENT = { scenario: { id: 'MINE', name: 'Mon plan', editable: true } }
    const previewOf = (extra: Record<string, unknown> = {}) => (body: Record<string, unknown>) => ({
      scenario: null,
      project: { name: body.name, amountCents: body.amountCents, currency: 'EUR' },
      dates: [
        { projectId: 'MOVE', name: 'Déménagement', before: '2027-03', after: '2027-03' },
        { projectId: 'SOFA', name: 'Canapé', before: '2027-05', after: '2027-09' },
        { projectId: 'BIKE', name: 'Vélo', before: null, after: null },
      ],
      nDate: { before: '2041-03', after: '2041-06' },
      metrics: { realCost: body.amountCents, availableAt: '2028-01', nImpactMonths: 3 },
      ...extra,
    })

    const fillBox = async (user: ReturnType<typeof userEvent.setup>) => {
      await user.click(screen.getByRole('button', { name: 'Nouveau projet' }))
      const dialog = await screen.findByRole('dialog')
      await user.type(within(dialog).getByLabelText(/^Nom/), 'Box à louer')
      await user.type(within(dialog).getByLabelText(/^Montant/), '18000')
      await user.click(within(dialog).getByRole('button', { name: 'Aperçu' }))

      return dialog
    }

    test('shows the dates before and after, the real cost, when it is available and the impact on N, and writes nothing', async () => {
      const user = userEvent.setup()
      const { writes, previews } = stubApi([MOVE, SOFA, BIKE], { preview: previewOf() })
      render(<ProjectsPage />)
      await loaded(3)

      const dialog = await fillBox(user)

      const card = await within(dialog).findByRole('region', { name: 'Aperçu · Box à louer' })
      expect(previews).toEqual([
        { name: 'Box à louer', amountCents: 1800000, wellbeing: 'medium', dateMode: 'none', targetOn: null, dependsOn: [] },
      ])
      const sofa = within(card).getAllByTestId('preview-date').find((line) => line.getAttribute('data-project') === 'Canapé') as HTMLElement
      expect(sofa).toHaveAttribute('data-moved', 'true')
      expect(sofa).toHaveTextContent(/mai 2027.*septembre 2027/)
      const move = within(card).getAllByTestId('preview-date').find((line) => line.getAttribute('data-project') === 'Déménagement') as HTMLElement
      expect(move).toHaveAttribute('data-moved', 'false')
      expect(within(card).getAllByTestId('preview-date').find((line) => line.getAttribute('data-project') === 'Vélo')).toHaveTextContent(/au-delà de 36 mois/)
      expect(within(card).getByTestId('preview-n')).toHaveTextContent(/mars 2041.*juin 2041/)
      expect(within(card).getByTestId('preview-real-cost')).toHaveTextContent(/18\s000,00\s€/)
      expect(within(card).getByTestId('preview-available')).toHaveTextContent('janvier 2028')
      expect(within(card).getByTestId('preview-impact')).toHaveTextContent('3 mois plus tard')
      expect(card).not.toHaveTextContent(/recommand|conseill|préférable/i)
      expect(writes).toEqual([])
    })

    test('cancelling creates nothing and leaves the list as it was', async () => {
      const user = userEvent.setup()
      const { writes } = stubApi([MOVE, SOFA], { preview: previewOf() })
      render(<ProjectsPage />)
      await loaded(2)

      const dialog = await fillBox(user)
      await within(dialog).findByRole('region', { name: 'Aperçu · Box à louer' })
      await user.click(within(dialog).getByRole('button', { name: 'Annuler' }))

      expect(screen.queryByRole('dialog')).toBeNull()
      expect(shown()).toEqual(['Déménagement', 'Canapé'])
      expect(writes).toEqual([])
    })

    test('adding creates the project, then puts it last in the current scenario', async () => {
      const user = userEvent.setup()
      const { writes } = stubApi([MOVE, SOFA], {
        preview: previewOf(CURRENT),
        scenarios: [{ id: 'MINE', name: 'Mon plan', kind: 'user', status: 'saved', current: true, projects: [{ projectId: 'MOVE', position: 0 }, { projectId: 'SOFA', position: 1 }] }],
      })
      render(<ProjectsPage />)
      await loaded(2)

      const dialog = await fillBox(user)
      await user.click(await within(dialog).findByRole('button', { name: 'Ajouter' }))

      await waitFor(() => expect(shown()).toEqual(['Déménagement', 'Canapé', 'Box à louer']))
      expect(screen.queryByRole('dialog')).toBeNull()
      expect(writes.map((write) => [write.method, write.url])).toEqual([
        ['POST', '/api/finance/projects'],
        ['PATCH', '/api/finance/scenarios/MINE'],
      ])
      expect(writes[1]?.body).toEqual({ projectIds: ['MOVE', 'SOFA', 'NEW'] })
    })

    test("previews in another scenario than the current one when it is chosen", async () => {
      const user = userEvent.setup()
      const { previews } = stubApi([MOVE, SOFA], {
        preview: (body) => previewOf({ scenario: { id: body.scenarioId ?? 'MINE', name: body.scenarioId ? 'Plan B' : 'Mon plan', editable: true } })(body),
        scenarios: [
          { id: 'MINE', name: 'Mon plan', kind: 'user', status: 'saved', current: true, projects: [] },
          { id: 'OTHER', name: 'Plan B', kind: 'user', status: 'saved', current: false, projects: [] },
          { id: 'auto-rapid', name: 'Rapide', kind: 'auto', status: 'draft', current: false, projects: [] },
        ],
      })
      render(<ProjectsPage />)
      await loaded(2)

      const dialog = await fillBox(user)
      const select = await within(dialog).findByRole('combobox', { name: 'Scénario' })
      await waitFor(() => expect(select).toHaveTextContent('Mon plan (courant)'))
      await user.click(select)
      expect(screen.queryByRole('option', { name: 'Rapide' })).toBeNull()
      await user.click(await screen.findByRole('option', { name: 'Plan B' }))

      await waitFor(() => expect(previews).toHaveLength(2))
      expect(previews[1]).toMatchObject({ scenarioId: 'OTHER' })
      await waitFor(() => expect(within(dialog).getByRole('combobox', { name: 'Scénario' })).toHaveTextContent('Plan B'))
    })

    test('adding in an automatic scenario creates the project outside it and says so', async () => {
      const user = userEvent.setup()
      const { writes } = stubApi([MOVE], { preview: previewOf({ scenario: { id: 'auto-rapid', name: 'Rapide', editable: false } }) })
      render(<ProjectsPage />)
      await loaded(1)

      const dialog = await fillBox(user)

      expect(await within(dialog).findByText(/proposition de Maggie : le projet sera créé sans y entrer/)).toBeInTheDocument()
      expect(writes).toEqual([])
      await user.click(within(dialog).getByRole('button', { name: 'Ajouter' }))

      await waitFor(() => expect(writes.some((w) => w.method === 'POST')).toBe(true))
      expect(writes.filter((w) => w.method === 'PUT')).toEqual([])
    })

    test('says why the preview failed and keeps the form', async () => {
      const user = userEvent.setup()
      const { writes } = stubApi([MOVE], { failPreview: { status: 400, body: { error: 'Project not found: GONE' } } })
      render(<ProjectsPage />)
      await loaded(1)

      const dialog = await fillBox(user)

      expect(await within(dialog).findByText('Project not found: GONE')).toBeInTheDocument()
      expect(within(dialog).getByLabelText(/^Nom/)).toHaveValue('Box à louer')
      expect(within(dialog).queryByRole('region', { name: /^Aperçu/ })).toBeNull()
      expect(writes).toEqual([])
    })

    test('"Modifier" goes back to the form with what was typed', async () => {
      const user = userEvent.setup()
      stubApi([MOVE], { preview: previewOf() })
      render(<ProjectsPage />)
      await loaded(1)

      const dialog = await fillBox(user)
      await user.click(await within(dialog).findByRole('button', { name: 'Modifier' }))

      expect(within(dialog).getByLabelText(/^Nom/)).toHaveValue('Box à louer')
      expect(within(dialog).getByLabelText(/^Montant/)).toHaveValue('18000')
    })

    test('editing a project has no preview: it is saved at once', async () => {
      const user = userEvent.setup()
      const { previews, writes } = stubApi([MOVE])
      render(<ProjectsPage />)
      await loaded(1)

      await user.click(screen.getByRole('button', { name: 'Modifier Déménagement' }))
      await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Enregistrer' }))

      await waitFor(() => expect(writes).toHaveLength(1))
      expect(previews).toEqual([])
    })
  })
})
