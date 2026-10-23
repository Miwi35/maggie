import { beforeEach, describe, expect, test, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { emitMercure } from '../../test/mercure'
import { PlacementsSection } from './PlacementsSection'

const mockNotify = vi.fn()
vi.mock('react-admin', async (importOriginal) => ({
  ...(await importOriginal<typeof import('react-admin')>()),
  useNotify: () => mockNotify,
}))

vi.mock('../../hooks/useMercure', async () => (await import('../../test/mercure')).mercureModule)

type Stored = Record<string, unknown> & { id: string; name: string }

const iri = (id: string) => `/api/finance/placements/${id}`
const TOPIC = '/api/finance/placements/{id}'

const placement = (id: string, fields: Record<string, unknown>): Stored => ({ '@id': iri(id), id, ...fields }) as unknown as Stored

const PEA = placement('PEA', {
  name: 'PEA',
  initial: 200000,
  deposit: 15000,
  frequency: 'monthly',
  annualRate: 600,
  years: 20,
  final: 7443007,
  paid: 3800000,
  interest: 3643007,
  rent: 24810,
  monthlyDeposit: 15000,
})
const LIFE = placement('LIFE', {
  name: 'Assurance-vie',
  initial: 0,
  deposit: 120000,
  frequency: 'yearly',
  annualRate: 350,
  years: 15,
  final: 2315700,
  paid: 1800000,
  interest: 515700,
  rent: 7719,
  monthlyDeposit: 10000,
})
const BOOK = placement('BOOK', {
  name: 'Livret jeune de Léo',
  initial: 50000,
  deposit: 0,
  frequency: 'once',
  annualRate: 300,
  years: 10,
  final: 67196,
  paid: 50000,
  interest: 17196,
  rent: 224,
  monthlyDeposit: 0,
})

/** What the API would compute, near enough for a screen: no interest. */
const figures = (fields: Record<string, unknown>) => {
  const paid = Number(fields.initial) + Number(fields.deposit) * 12 * Number(fields.years)
  return { final: paid + 100, paid, interest: 100, rent: Math.round(((paid + 100) * 0.04) / 12), monthlyDeposit: Number(fields.deposit) }
}

interface Write {
  method: string
  url: string
  body: unknown
}

function stubApi(
  initial: Stored[],
  options: {
    failLoad?: boolean
    failSimulation?: boolean
    /** What the simulation adds when the projects of the scenario take capital out. */
    withdrawal?: Record<string, unknown>
    refuse?: { status: number; body: unknown }
  } = {},
) {
  let stored = initial.map((row) => ({ ...row }))
  const writes: Write[] = []
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    if (method !== 'GET') writes.push({ method, url, body })

    if (method === 'GET' && url.endsWith('/simulation')) {
      if (options.failSimulation) return json({ error: 'down' }, 500)
      const id = url.split('/').slice(-2)[0]
      const row = stored.find((candidate) => candidate.id === id) as Stored
      return json({
        final: row.final,
        paid: row.paid,
        interest: row.interest,
        rent: row.rent,
        curve: [
          { month: 0, capital: row.initial, paid: row.initial },
          { month: Number(row.years) * 12, capital: row.final, paid: row.paid },
        ],
        ...options.withdrawal,
      })
    }
    if (method === 'GET') {
      if (options.failLoad) return json({}, 500)
      return json({ member: stored })
    }
    if (options.refuse) return json(options.refuse.body, options.refuse.status)
    if (method === 'POST') {
      const created = placement('NEW', { ...(body as Record<string, unknown>), ...figures(body as Record<string, unknown>) })
      stored.push(created)
      return json(created, 201)
    }
    const id = url.split('/').pop() as string
    if (method === 'PATCH') {
      const merged = { ...(stored.find((row) => row.id === id) as Stored), ...(body as Record<string, unknown>) }
      const updated = { ...merged, ...figures(merged) }
      stored = stored.map((row) => (row.id === id ? updated : row))
      return json(updated)
    }
    stored = stored.filter((row) => row.id !== id)
    return new Response(null, { status: 204 })
  })
  vi.stubGlobal('fetch', fetchMock)

  return { writes, fetchMock }
}

/** Intl's narrow spaces, matched as any space. */
const text = (spelled: string) => new RegExp(`^${spelled.replace(/ /g, '\\s')}$`)

const shown = () => screen.queryAllByTestId('placement-row').map((row) => row.getAttribute('data-placement'))
const row = (name: string) =>
  screen.getAllByTestId('placement-row').find((candidate) => candidate.getAttribute('data-placement') === name) as HTMLElement
const loaded = async (count: number) => waitFor(() => expect(shown()).toHaveLength(count))
const simulation = () => screen.getByRole('region', { name: /^Simulation de / })

describe('PlacementsSection', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    mockNotify.mockReset()
    localStorage.setItem('token', 'test-jwt')
  })

  test('lists the placements by name with their deposits, rate, duration, final capital and rente at 4 %', async () => {
    stubApi([PEA, LIFE, BOOK])
    render(<PlacementsSection />)
    await loaded(3)

    expect(shown()).toEqual(['Assurance-vie', 'Livret jeune de Léo', 'PEA'])
    expect(screen.getByTestId('placements-monthly')).toHaveTextContent(text('250 € / mois de versements'))

    const pea = within(row('PEA'))
    expect(pea.getByText(text('150 € / mois · 2 000 € initial'))).toBeInTheDocument()
    expect(pea.getByText(text('6 %'))).toBeInTheDocument()
    expect(pea.getByText('20 ans')).toBeInTheDocument()
    expect(pea.getByTestId('placement-final')).toHaveTextContent(text('74 430 €'))
    expect(pea.getByTestId('placement-rent')).toHaveTextContent(text('248 €'))

    expect(within(row('Assurance-vie')).getByText(text('1 200 € / an'))).toBeInTheDocument()
    expect(within(row('Assurance-vie')).getByText(text('3,5 %'))).toBeInTheDocument()
    expect(within(row('Livret jeune de Léo')).getByText(text('500 € une fois'))).toBeInTheDocument()
  })

  test('draws the first placement by default, then the one clicked, from its simulation', async () => {
    const user = userEvent.setup()
    const { fetchMock } = stubApi([PEA, LIFE])
    render(<PlacementsSection />)
    await loaded(2)

    expect(row('Assurance-vie')).toHaveAttribute('aria-selected', 'true')
    expect(await within(simulation()).findByTestId('placement-curve')).toBeInTheDocument()
    expect(within(simulation()).getByText('Assurance-vie · 15 ans')).toBeInTheDocument()
    expect(within(simulation()).getByRole('img', { name: /^Courbe de Assurance-vie sur 15 ans/ })).toBeInTheDocument()
    expect(within(simulation()).getByText('versements cumulés')).toBeInTheDocument()
    expect(within(simulation()).getByText('intérêts composés')).toBeInTheDocument()

    await user.click(within(row('PEA')).getByText('PEA'))

    expect(row('PEA')).toHaveAttribute('aria-selected', 'true')
    expect(row('Assurance-vie')).toHaveAttribute('aria-selected', 'false')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/finance/placements/PEA/simulation', expect.anything()))
    expect(await within(simulation()).findByRole('img', { name: /^Courbe de PEA sur 20 ans/ })).toBeInTheDocument()
    expect(within(simulation()).getByText('Capital dans 20 ans').nextSibling).toHaveTextContent(text('74 430 €'))
    expect(within(simulation()).getByText('Dont versements').nextSibling).toHaveTextContent(text('38 000 €'))
    expect(within(simulation()).getByText('Dont intérêts composés').nextSibling).toHaveTextContent(text('36 430 €'))
    expect(
      within(simulation()).getByText(/^Retiré à 4 % par an, ce capital donnerait environ 248\s€ par mois de rente \(simulation\)\.$/),
    ).toBeInTheDocument()
  })

  describe('when a project takes capital out of the placement', () => {
    const WITHDRAWAL = {
      withdrawals: [
        { projectId: 'JAPAN', name: 'Voyage au Japon', month: '2027-04', amountCents: 150000 },
        { projectId: 'BIKE', name: 'Vélo', month: '2028-01', amountCents: 50000 },
      ],
      withdrawn: 200000,
      curveWithout: [
        { month: 0, capital: 200000, paid: 200000 },
        { month: 240, capital: 7443007, paid: 3800000 },
      ],
      curveWith: [
        { month: 0, capital: 200000, paid: 200000 },
        { month: 240, capital: 6200000, paid: 3800000 },
      ],
      gapAtEnd: 1243007,
      finalWith: 6200000,
      rentWith: 20667,
    }

    test('draws the capital with and without the withdrawals, the gap at the end and the rente after them', async () => {
      stubApi([PEA], { withdrawal: WITHDRAWAL })
      render(<PlacementsSection />)
      await loaded(1)

      expect(await within(simulation()).findByTestId('placement-curve-without')).toHaveAttribute('stroke-dasharray')
      expect(within(simulation()).getByTestId('placement-curve-with')).not.toHaveAttribute('stroke-dasharray')
      expect(within(simulation()).queryByTestId('placement-curve-paid')).toBeNull()
      expect(within(simulation()).getByText('capital sans retrait')).toBeInTheDocument()
      expect(within(simulation()).getByText('capital avec retrait')).toBeInTheDocument()
      expect(within(simulation()).getByText('PEA · avec et sans retrait pour Voyage au Japon et Vélo')).toBeInTheDocument()
      expect(
        within(simulation()).getByRole('img', { name: /^Courbe de PEA sur 20 ans : 74\s430\s€ de capital à la fin sans retrait, 62\s000\s€ avec retrait$/ }),
      ).toBeInTheDocument()

      expect(within(simulation()).getByTestId('placement-gap')).toHaveTextContent(text('Écart à 20 ans : −12 430 €'))
      expect(within(simulation()).getByText('Capital dans 20 ans, sans retrait').nextSibling).toHaveTextContent(text('74 430 €'))
      expect(within(simulation()).getByText('Capital après retrait').nextSibling).toHaveTextContent(text('62 000 €'))
      expect(within(simulation()).getByTestId('placement-rent-with')).toHaveTextContent(
        /environ 207\s€ par mois de rente, contre 248\s€ sans retrait \(simulation\)\.$/,
      )
    })

    test('lists each withdrawal: the project, the month and the amount', async () => {
      stubApi([PEA], { withdrawal: WITHDRAWAL })
      render(<PlacementsSection />)
      await loaded(1)

      const list = await within(simulation()).findByRole('list', { name: 'Retraits de PEA' })
      const items = within(list).getAllByTestId('placement-withdrawal')
      expect(items).toHaveLength(2)
      expect(items[0]).toHaveTextContent(/Voyage au Japon\s*avril 2027\s*−1\s500\s€/)
      expect(items[1]).toHaveTextContent(/Vélo\s*janvier 2028\s*−500\s€/)
    })

    test('speaks of the project by the name the API gives, with no verdict', async () => {
      stubApi([PEA], { withdrawal: { ...WITHDRAWAL, withdrawals: [WITHDRAWAL.withdrawals[0]] } })
      render(<PlacementsSection />)
      await loaded(1)

      await within(simulation()).findByTestId('placement-gap')
      expect(within(simulation()).getByText('PEA · avec et sans retrait pour Voyage au Japon')).toBeInTheDocument()
      expect(simulation().textContent).not.toMatch(/recommand/i)
    })

    test('keeps the table of the placements as it was: the final capital and the rente are those without withdrawal', async () => {
      stubApi([PEA], { withdrawal: WITHDRAWAL })
      render(<PlacementsSection />)
      await loaded(1)
      await within(simulation()).findByTestId('placement-gap')

      expect(within(row('PEA')).getByTestId('placement-final')).toHaveTextContent(text('74 430 €'))
      expect(within(row('PEA')).getByTestId('placement-rent')).toHaveTextContent(text('248 €'))
    })

    test('an answer with no withdrawal leaves the screen as it was: areas, no gap, no list', async () => {
      stubApi([PEA], { withdrawal: { withdrawals: [], withdrawn: 0, gapAtEnd: 0 } })
      render(<PlacementsSection />)
      await loaded(1)

      expect(await within(simulation()).findByTestId('placement-curve-paid')).toBeInTheDocument()
      expect(within(simulation()).queryByTestId('placement-gap')).toBeNull()
      expect(within(simulation()).queryByRole('list')).toBeNull()
      expect(within(simulation()).getByText('PEA · 20 ans')).toBeInTheDocument()
    })

    test('says when the simulation failed, without a gap or a list', async () => {
      stubApi([PEA], { failSimulation: true, withdrawal: WITHDRAWAL })
      render(<PlacementsSection />)
      await loaded(1)

      expect(await screen.findByRole('alert')).toHaveTextContent("La courbe de ce placement n'a pas pu être calculée.")
      expect(within(simulation()).queryByTestId('placement-gap')).toBeNull()
      expect(within(simulation()).queryByTestId('placement-withdrawal')).toBeNull()
    })
  })

  test('the keyboard selects a row too', async () => {
    const user = userEvent.setup()
    stubApi([PEA, LIFE])
    render(<PlacementsSection />)
    await loaded(2)

    row('PEA').focus()
    await user.keyboard('{Enter}')

    expect(row('PEA')).toHaveAttribute('aria-selected', 'true')
  })

  test('says when there is no placement yet', async () => {
    stubApi([])
    render(<PlacementsSection />)

    expect(await screen.findByText(/^Aucun placement pour l'instant\./)).toBeInTheDocument()
    expect(screen.queryByTestId('placements-monthly')).toBeNull()
  })

  test('says when the placements could not be read, and reads them again on demand', async () => {
    const user = userEvent.setup()
    stubApi([PEA], { failLoad: true })
    render(<PlacementsSection />)

    expect(await screen.findByText("Les placements n'ont pas pu être chargés.")).toBeInTheDocument()
    stubApi([PEA])
    await user.click(screen.getByRole('button', { name: 'Réessayer' }))
    await loaded(1)
  })

  test('says when the curve could not be computed, and still shows the figures', async () => {
    stubApi([PEA], { failSimulation: true })
    render(<PlacementsSection />)
    await loaded(1)

    expect(await screen.findByText("La courbe de ce placement n'a pas pu être calculée.")).toBeInTheDocument()
    expect(within(simulation()).getByText('Capital dans 20 ans')).toBeInTheDocument()
  })

  test('creates a placement of 100 € a month at 5 % over 10 years, and selects it', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([PEA])
    render(<PlacementsSection />)
    await loaded(1)

    await user.click(screen.getByRole('button', { name: 'Placement' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/^Nom/), 'Livret A')
    await user.type(within(dialog).getByLabelText('Versement (€)'), '100')
    await user.type(within(dialog).getByLabelText(/^Rendement annuel/), '5')
    await user.type(within(dialog).getByLabelText(/^Durée/), '10')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(shown()).toEqual(['Livret A', 'PEA']))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(writes).toEqual([
      {
        method: 'POST',
        url: '/api/finance/placements',
        body: { name: 'Livret A', initial: 0, deposit: 10000, frequency: 'monthly', annualRate: 500, years: 10 },
      },
    ])
    expect(row('Livret A')).toHaveAttribute('aria-selected', 'true')
    expect(within(row('Livret A')).getByText(text('100 € / mois'))).toBeInTheDocument()
  })

  test('a quarterly deposit with a decimal rate is sent in cents and basis points', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([])
    render(<PlacementsSection />)
    await screen.findByText(/^Aucun placement/)

    await user.click(screen.getByRole('button', { name: 'Placement' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/^Nom/), 'Assurance-vie')
    await user.type(within(dialog).getByLabelText('Versement initial (€)'), '1 000,50')
    await user.type(within(dialog).getByLabelText('Versement (€)'), '300')
    await user.click(within(dialog).getByRole('combobox', { name: 'Fréquence' }))
    await user.click(await screen.findByRole('option', { name: 'Trimestriel' }))
    await user.type(within(dialog).getByLabelText(/^Rendement annuel/), '3,25')
    await user.type(within(dialog).getByLabelText(/^Durée/), '15')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await loaded(1)
    expect(writes[0].body).toEqual({ name: 'Assurance-vie', initial: 100050, deposit: 30000, frequency: 'quarterly', annualRate: 325, years: 15 })
  })

  test('refuses a placement with nothing paid, a missing name, rate or duration before sending anything', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([PEA])
    render(<PlacementsSection />)
    await loaded(1)

    await user.click(screen.getByRole('button', { name: 'Placement' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/^Durée/), '80')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    expect(within(dialog).getByText('Le nom est obligatoire.')).toBeInTheDocument()
    expect(within(dialog).getByText('Indiquez un versement initial ou un versement régulier.')).toBeInTheDocument()
    expect(within(dialog).getByText('Indiquez un rendement de 0 à 100 %.')).toBeInTheDocument()
    expect(within(dialog).getByText('Indiquez une durée de 1 à 60 ans.')).toBeInTheDocument()
    expect(writes).toEqual([])
  })

  test("puts the API's « nothing paid » refusal under the deposit, in French", async () => {
    const user = userEvent.setup()
    stubApi([PEA], {
      refuse: { status: 422, body: { violations: [{ propertyPath: 'deposit', message: 'A placement needs an initial payment or a deposit.' }] } },
    })
    render(<PlacementsSection />)
    await loaded(1)

    await user.click(screen.getByRole('button', { name: 'Modifier PEA' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    expect(await within(dialog).findByText('Indiquez un versement initial ou un versement régulier.')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Versement (€)')).toHaveAttribute('aria-invalid', 'true')
  })

  test('any other refusal is said in French at the top of the form', async () => {
    const user = userEvent.setup()
    stubApi([PEA], { refuse: { status: 500, body: { detail: 'Internal Server Error' } } })
    render(<PlacementsSection />)
    await loaded(1)

    await user.click(screen.getByRole('button', { name: 'Modifier PEA' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    expect(await within(dialog).findByText("Le placement « PEA » n'a pas été modifié.")).toBeInTheDocument()
    expect(within(dialog).queryByText('Internal Server Error')).toBeNull()
  })

  test('edits a placement: its values come back as typed, the new rate is saved and shown', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([PEA, LIFE])
    render(<PlacementsSection />)
    await loaded(2)

    await user.click(screen.getByRole('button', { name: 'Modifier PEA' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText(/^Nom/)).toHaveValue('PEA')
    expect(within(dialog).getByLabelText('Versement initial (€)')).toHaveValue('2000')
    expect(within(dialog).getByLabelText('Versement (€)')).toHaveValue('150')
    expect(within(dialog).getByLabelText(/^Rendement annuel/)).toHaveValue('6')
    expect(within(dialog).getByLabelText(/^Durée/)).toHaveValue('20')
    await user.clear(within(dialog).getByLabelText(/^Rendement annuel/))
    await user.type(within(dialog).getByLabelText(/^Rendement annuel/), '4,5')
    await user.click(within(dialog).getByRole('button', { name: 'Enregistrer' }))

    await waitFor(() => expect(within(row('PEA')).getByText(text('4,5 %'))).toBeInTheDocument())
    expect(writes).toEqual([
      {
        method: 'PATCH',
        url: '/api/finance/placements/PEA',
        body: { name: 'PEA', initial: 200000, deposit: 15000, frequency: 'monthly', annualRate: 450, years: 20 },
      },
    ])
    // The edit does not select the row: the curve stays on the one chosen.
    expect(row('Assurance-vie')).toHaveAttribute('aria-selected', 'true')
  })

  test('deletes a placement once confirmed, and the curve moves to the first one left', async () => {
    const user = userEvent.setup()
    const { writes } = stubApi([PEA, LIFE])
    render(<PlacementsSection />)
    await loaded(2)

    await user.click(screen.getByRole('button', { name: 'Supprimer Assurance-vie' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Supprimer le placement « Assurance-vie » ?')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(shown()).toEqual(['PEA']))
    expect(row('PEA')).toHaveAttribute('aria-selected', 'true')
    expect(writes).toEqual([{ method: 'DELETE', url: '/api/finance/placements/LIFE', body: undefined }])
  })

  test('a refused delete puts the placement back and says so', async () => {
    const user = userEvent.setup()
    stubApi([PEA], { refuse: { status: 500, body: {} } })
    render(<PlacementsSection />)
    await loaded(1)

    await user.click(screen.getByRole('button', { name: 'Supprimer PEA' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Supprimer' }))

    await waitFor(() => expect(mockNotify).toHaveBeenCalledWith("Le placement n'a pas été supprimé.", { type: 'error' }))
    expect(shown()).toEqual(['PEA'])
  })

  test('a placement changed elsewhere (the phone, Maggie) is redrawn from its Mercure update, without a read', async () => {
    const { fetchMock } = stubApi([PEA, LIFE])
    render(<PlacementsSection />)
    await loaded(2)
    await within(simulation()).findByTestId('placement-curve')
    const listReads = () => fetchMock.mock.calls.filter(([url]) => String(url).includes('?pagination=false')).length
    const reads = listReads()

    await emitMercure(TOPIC, { '@id': iri('PEA'), annualRate: 500, final: 6500000, rent: 21667 })
    expect(within(row('PEA')).getByText(text('5 %'))).toBeInTheDocument()
    expect(within(row('PEA')).getByTestId('placement-final')).toHaveTextContent(text('65 000 €'))

    await emitMercure(TOPIC, {
      '@id': iri('BOOK'),
      name: 'Livret jeune de Léo',
      initial: 50000,
      deposit: 0,
      frequency: 'once',
      annualRate: 300,
      years: 10,
      final: 67196,
      paid: 50000,
      interest: 17196,
      rent: 224,
      monthlyDeposit: 0,
    })
    expect(shown()).toEqual(['Assurance-vie', 'Livret jeune de Léo', 'PEA'])

    await emitMercure(TOPIC, { '@id': iri('LIFE'), deleted: true })
    expect(shown()).toEqual(['Livret jeune de Léo', 'PEA'])
    expect(screen.getByTestId('placements-monthly')).toHaveTextContent(text('150 € / mois de versements'))
    expect(listReads()).toBe(reads)
  })

  test('the curve is read again when the selected placement changes elsewhere', async () => {
    const { fetchMock } = stubApi([PEA])
    render(<PlacementsSection />)
    await loaded(1)
    await within(simulation()).findByTestId('placement-curve')
    const simulations = () => fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/simulation')).length
    expect(simulations()).toBe(1)

    await emitMercure(TOPIC, { '@id': iri('PEA'), years: 25, final: 9000000, paid: 4700000 })

    await waitFor(() => expect(simulations()).toBe(2))
  })

  test('an update for a placement it does not know reads the list again', async () => {
    const { fetchMock } = stubApi([PEA])
    render(<PlacementsSection />)
    await loaded(1)
    const reads = fetchMock.mock.calls.length

    await emitMercure(TOPIC, { '@id': iri('ELSEWHERE'), annualRate: 400 })

    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(reads))
    expect(fetchMock.mock.calls.some(([url], index) => index >= reads && String(url).includes('?pagination=false'))).toBe(true)
  })
})
