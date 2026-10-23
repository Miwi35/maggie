import { afterEach, describe, expect, test, vi } from 'vitest'
import { eurosToCents, validateProject } from './projectForm'
import {
  createProject,
  fetchProjectPlanning,
  fetchProjects,
  joinNames,
  patchProject,
  projectDateLabel,
  removeInvestment,
  saveInvestment,
  toProject,
  updateProject,
} from './projectsApi'
import type { ProjectInput } from './projectsApi'

const INPUT: ProjectInput = {
  name: 'Voyage au Japon',
  amountCents: 300000,
  wellbeing: 'high',
  dateMode: 'none',
  targetOn: null,
  dependsOn: [],
  note: null,
}

/** Stubs fetch with one answer and returns the calls it received. */
const stubFetch = (body: unknown) => {
  const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
    async () => new Response(JSON.stringify(body), { status: 200 }),
  )
  vi.stubGlobal('fetch', fetchMock)

  return fetchMock
}

const sentBody = (fetchMock: ReturnType<typeof stubFetch>) => JSON.parse(String(fetchMock.mock.calls[0][1]?.body))

describe('projects', () => {
  test.each([
    [{ dateMode: 'none', targetOn: null }, 'Sans date'],
    [{ dateMode: 'fixed', targetOn: '2027-03-01' }, 'le 1er mars 2027'],
    [{ dateMode: 'fixed', targetOn: '2027-01-15' }, 'le 15 janvier 2027'],
    [{ dateMode: 'deadline', targetOn: '2027-08-31' }, 'au plus tard le 31 août 2027'],
  ] as const)('%o reads « %s »', (project, label) => {
    expect(projectDateLabel(project)).toBe(label)
  })

  test('names one, two or three projects the French way', () => {
    expect(joinNames(['Déménagement'])).toBe('Déménagement')
    expect(joinNames(['Déménagement', 'Voiture'])).toBe('Déménagement et Voiture')
    expect(joinNames(['A', 'B', 'C'])).toBe('A, B et C')
  })

  test('reads dependencies as bare ids, IRIs (REST) or ids (reorder) alike', () => {
    expect(toProject({ '@id': '/api/finance/projects/A', dependsOn: ['/api/finance/projects/B'] }).dependsOn).toEqual(['B'])
    expect(toProject({ id: 'A', dependsOn: ['B'] }).id).toBe('A')
  })

  test('a Mercure update changes only the fields it carries', () => {
    const before = toProject({ id: 'A', name: 'Déménagement', position: 0, amountCents: 100 })
    const after = patchProject(before, { '@id': '/api/finance/projects/A', position: 2 })

    expect(after).toEqual({ ...before, position: 2 })
  })

  test.each([
    ['1 500,50', 150050],
    ['1 500,5 €', 150050],
    ['12.3', 1230],
    ['0', 0],
    ['douze', null],
    ['', null],
  ])('« %s » is %s cents', (typed, cents) => {
    expect(eurosToCents(typed)).toBe(cents)
  })

  test('the form refuses what the API would', () => {
    const valid = { name: 'Canapé', amount: '1200', wellbeing: 'comfort', dateMode: 'none', targetOn: '', dependsOn: [], note: '' } as const

    expect(validateProject({ ...valid, dependsOn: [] })).toEqual({})
    expect(validateProject({ ...valid, dependsOn: [], dateMode: 'deadline' })).toEqual({ targetOn: 'Choisissez la date limite.' })
    expect(validateProject({ ...valid, dependsOn: [], name: ' ', amount: '-3' })).toEqual({
      name: 'Le nom est obligatoire.',
      amount: 'Indiquez un montant supérieur à 0.',
    })
  })

  describe('over the wire', () => {
    afterEach(() => vi.unstubAllGlobals())

    test('reads every project in one answer, not only the first page', async () => {
      const fetchMock = stubFetch({ member: [{ id: 'B', position: 1 }, { id: 'A', position: 0 }] })

      expect((await fetchProjects()).map((project) => project.id)).toEqual(['A', 'B'])
      expect(String(fetchMock.mock.calls[0][0])).toBe('/api/finance/projects?pagination=false')
    })

    test('a new project is in euros', async () => {
      const fetchMock = stubFetch({ id: 'NEW', ...INPUT, currency: 'EUR' })

      await createProject(INPUT)

      expect(sentBody(fetchMock).currency).toBe('EUR')
    })

    test('a changed project keeps its own currency', async () => {
      const fetchMock = stubFetch({ id: 'JPY', ...INPUT, currency: 'JPY' })

      await updateProject({ id: '/api/finance/projects/JPY', currency: 'JPY' }, INPUT)

      expect(String(fetchMock.mock.calls[0][0])).toBe('/api/finance/projects/JPY')
      expect(sentBody(fetchMock).currency).toBe('JPY')
    })
  })

  describe('planning months (MAG-528)', () => {
    test('reads the funding of each month, by source', async () => {
      stubFetch({
        startMonth: '2026-10',
        months: [
          { month: '2026-10', capacityCents: 50000, cushionCents: 0, salaryCents: 200000, savingsCents: 0, capitalCents: 0, loanPaymentsCents: 0, isOverCapacity: true },
          { month: '2026-11', capacityCents: 50000, salaryCents: 10000, savingsCents: 80000, capitalCents: 5000 },
        ],
        projects: [],
      })

      const { months } = await fetchProjectPlanning()

      expect(months).toEqual([
        { month: '2026-10', capacityCents: 50000, salaryCents: 200000, savingsCents: 0, capitalCents: 0, rentCents: 0, resaleCents: 0, isOverCapacity: true },
        { month: '2026-11', capacityCents: 50000, salaryCents: 10000, savingsCents: 80000, capitalCents: 5000, rentCents: 0, resaleCents: 0, isOverCapacity: false },
      ])
    })

    test('has no month when the answer carries none', async () => {
      stubFetch({ startMonth: '2026-10', projects: [] })

      expect((await fetchProjectPlanning()).months).toEqual([])
    })
  })

  describe('investments (MAG-557)', () => {
    const RAW_INVESTMENT = {
      projectId: '/api/finance/projects/BOX',
      priceCents: 1800000,
      downPaymentCents: 300000,
      loanId: '/api/loans/L1',
      loanPaymentCents: 14000,
      monthlyRentCents: 12000,
      monthlyBalanceCents: -2000,
      resale: { month: '2028-06', netAmountCents: 1000000, targetProjectId: '/api/finance/projects/CAR' },
    }

    test('reads the investment of a project, the loan and the resale target as bare ids', () => {
      expect(toProject({ id: 'BOX', investment: RAW_INVESTMENT }).investment).toEqual({
        priceCents: 1800000,
        downPaymentCents: 300000,
        loanId: 'L1',
        loanPaymentCents: 14000,
        monthlyRentCents: 12000,
        monthlyBalanceCents: -2000,
        resale: { month: '2028-06', netAmountCents: 1000000, targetProjectId: 'CAR' },
      })
      expect(toProject({ id: 'BOX', investment: { ...RAW_INVESTMENT, loanId: null, resale: null } }).investment).toMatchObject({ loanId: null, resale: null })
      expect(toProject({ id: 'BOX' }).investment).toBeNull()
    })

    test('reads the source project of a resale funding source', () => {
      const project = toProject({ id: 'CAR', fundingSources: [{ kind: 'resale', amountCents: 1000000, sourceProjectId: '/api/finance/projects/BOX' }] })

      expect(project.fundingSources[0]).toMatchObject({ kind: 'resale', sourceProjectId: 'BOX' })
    })

    test('a Mercure update lays the investment over the project, or takes it away', () => {
      const before = toProject({ id: 'BOX', name: 'Box' })

      expect(patchProject(before, { investment: RAW_INVESTMENT }).investment?.monthlyRentCents).toBe(12000)
      expect(patchProject(patchProject(before, { investment: RAW_INVESTMENT }), { investment: null }).investment).toBeNull()
      expect(patchProject(patchProject(before, { investment: RAW_INVESTMENT }), { name: 'Box 2' }).investment).not.toBeNull()
    })

    test('reads the rent, the resale and the investment of the planning', async () => {
      stubFetch({
        startMonth: '2026-10',
        months: [{ month: '2026-10', rentCents: 12000, resaleCents: 1000000 }],
        projects: [
          {
            projectId: 'BOX',
            name: 'Box',
            resaleFromMonth: null,
            investment: {
              monthlyRentCents: 12000,
              monthlyBalanceCents: -2000,
              rentFromMonth: '2027-01',
              rentUntilMonth: '2028-06',
              resaleMonth: '2028-06',
              resaleNetCents: 1000000,
              resaleTargetProjectId: '/api/finance/projects/CAR',
            },
          },
          { projectId: 'CAR', name: 'Voiture', resaleFromMonth: '2028-07' },
        ],
      })

      const planning = await fetchProjectPlanning()

      expect(planning.months[0]).toMatchObject({ rentCents: 12000, resaleCents: 1000000 })
      expect(planning.projects[0].investment).toEqual({
        monthlyRentCents: 12000,
        monthlyBalanceCents: -2000,
        rentFromMonth: '2027-01',
        rentUntilMonth: '2028-06',
        resaleMonth: '2028-06',
        resaleNetCents: 1000000,
        resaleTargetProjectId: 'CAR',
      })
      expect(planning.projects[1]).toMatchObject({ resaleFromMonth: '2028-07', investment: null })
    })

    test('PUTs the investment and returns the project the API answered', async () => {
      const fetchMock = stubFetch({ success: true, project: { id: 'BOX', name: 'Box', investment: RAW_INVESTMENT } })
      const input = { downPaymentCents: 300000, monthlyRentCents: 12000 }

      const project = await saveInvestment('/api/finance/projects/BOX', input)

      expect(String(fetchMock.mock.calls[0][0])).toBe('/api/finance/projects/BOX/investment')
      expect(fetchMock.mock.calls[0][1]?.method).toBe('PUT')
      expect(sentBody(fetchMock)).toEqual(input)
      expect(project.investment?.loanId).toBe('L1')
    })

    test('DELETEs the investment', async () => {
      const fetchMock = stubFetch({ success: true, project: { id: 'BOX', name: 'Box', investment: null } })

      const project = await removeInvestment('BOX')

      expect(String(fetchMock.mock.calls[0][0])).toBe('/api/finance/projects/BOX/investment')
      expect(fetchMock.mock.calls[0][1]?.method).toBe('DELETE')
      expect(project.investment).toBeNull()
    })

    test('says what the API refused', async () => {
      vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ error: 'The loan is missing.', current: {} }), { status: 400 })))

      await expect(saveInvestment('BOX', { downPaymentCents: 0, monthlyRentCents: 0 })).rejects.toThrow(/loan|prêt/i)
      await expect(removeInvestment('BOX')).rejects.toThrow()
    })

    test('falls back to a French sentence when the refusal says nothing', async () => {
      vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 500 })))

      await expect(saveInvestment('BOX', { downPaymentCents: 0, monthlyRentCents: 0 })).rejects.toThrow("L'investissement n'a pas été enregistré.")
      await expect(removeInvestment('BOX')).rejects.toThrow("L'investissement n'a pas été supprimé.")
    })
  })
})
