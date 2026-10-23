import { afterEach, describe, expect, test, vi } from 'vitest'
import { emptyValues, percentToBasisPoints, toInput, validatePlacement, valuesOf } from './placementForm'
import {
  createPlacement,
  depositsLabel,
  fetchPlacements,
  fetchSimulation,
  formatEuros,
  formatRate,
  isWholePlacement,
  NOTHING_PAID,
  patchPlacement,
  PlacementError,
  toPlacement,
  updatePlacement,
  yearsLabel,
} from './placementsApi'
import type { PlacementInput } from './placementsApi'

const INPUT: PlacementInput = { name: 'PEA', initial: 200000, deposit: 15000, frequency: 'monthly', annualRate: 600, years: 20 }

/** The spaces Intl puts in French numbers, read as plain ones. */
const plain = (text: string) => text.replace(/[\u00a0\u202f]/g, ' ')

const stubFetch = (body: unknown, status = 200) => {
  const fetchMock = vi.fn<(input: RequestInfo | URL, init?: RequestInit) => Promise<Response>>(
    async () => new Response(JSON.stringify(body), { status }),
  )
  vi.stubGlobal('fetch', fetchMock)

  return fetchMock
}

const sentBody = (fetchMock: ReturnType<typeof stubFetch>) => JSON.parse(String(fetchMock.mock.calls[0][1]?.body))

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('placements, as the owner reads them', () => {
  test('euros are rounded to the euro, the French way', () => {
    expect(plain(formatEuros(7443007))).toBe('74 430 €')
    expect(plain(formatEuros(24810))).toBe('248 €')
    expect(plain(formatEuros(0))).toBe('0 €')
  })

  test.each([
    [600, '6 %'],
    [350, '3,5 %'],
    [325, '3,25 %'],
    [0, '0 %'],
  ])('%i basis points read « %s »', (basisPoints, label) => {
    expect(plain(formatRate(basisPoints))).toBe(label)
  })

  test('one year, twenty years', () => {
    expect(yearsLabel(1)).toBe('1 an')
    expect(yearsLabel(20)).toBe('20 ans')
  })

  test.each([
    [{ initial: 200000, deposit: 15000, frequency: 'monthly' }, '150 € / mois · 2 000 € initial'],
    [{ initial: 0, deposit: 120000, frequency: 'yearly' }, '1 200 € / an'],
    [{ initial: 0, deposit: 30000, frequency: 'quarterly' }, '300 € / trimestre'],
    [{ initial: 0, deposit: 50000, frequency: 'once' }, '500 € une fois'],
    [{ initial: 50000, deposit: 0, frequency: 'once' }, '500 € une fois'],
    [{ initial: 50000, deposit: 0, frequency: 'monthly' }, '500 € une fois'],
  ] as const)('%o is paid « %s »', (placement, label) => {
    expect(plain(depositsLabel(placement))).toBe(label)
  })
})

describe('placements, as the API sends them', () => {
  test('reads a member, its IRI as a bare id', () => {
    const placement = toPlacement({
      '@id': '/api/finance/placements/01PEA',
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

    expect(placement).toEqual({
      id: '01PEA',
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
    expect(toPlacement({ id: 'X', frequency: 'weekly' }).frequency).toBe('monthly')
  })

  test('a Mercure update changes only the fields it carries', () => {
    const before = toPlacement({ id: 'A', name: 'PEA', annualRate: 600, years: 20, final: 100 })
    const after = patchPlacement(before, { '@id': '/api/finance/placements/A', annualRate: 500, final: 90 })

    expect(after).toEqual({ ...before, annualRate: 500, final: 90 })
    expect(isWholePlacement({ annualRate: 500 })).toBe(false)
    expect(
      isWholePlacement({ name: 'Livret', initial: 0, deposit: 1, frequency: 'once', annualRate: 300, years: 10, final: 2 }),
    ).toBe(true)
  })

  test('lists every placement in one answer, by name', async () => {
    const fetchMock = stubFetch({ member: [{ id: 'B', name: 'PEA' }, { id: 'A', name: 'Assurance-vie' }] })

    const placements = await fetchPlacements()

    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/finance/placements?pagination=false')
    expect(placements.map((placement) => placement.name)).toEqual(['Assurance-vie', 'PEA'])
  })

  test('creates with a POST in cents and basis points, and changes with a merge patch', async () => {
    const created = stubFetch({ id: 'NEW', ...INPUT })
    await createPlacement(INPUT)
    expect(created.mock.calls[0][1]?.method).toBe('POST')
    expect(sentBody(created)).toEqual(INPUT)

    const updated = stubFetch({ id: 'NEW', ...INPUT })
    await updatePlacement('/api/finance/placements/NEW', { ...INPUT, annualRate: 500 })
    expect(String(updated.mock.calls[0][0])).toBe('/api/finance/placements/NEW')
    expect(updated.mock.calls[0][1]?.method).toBe('PATCH')
    expect((updated.mock.calls[0][1]?.headers as Record<string, string>)['Content-Type']).toBe('application/merge-patch+json')
    expect(sentBody(updated).annualRate).toBe(500)
  })

  test('says « nothing paid » in French, under the deposit', async () => {
    stubFetch({ violations: [{ propertyPath: 'deposit', message: NOTHING_PAID }] }, 422)

    const error = await createPlacement({ ...INPUT, initial: 0, deposit: 0 }).catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(PlacementError)
    expect((error as PlacementError).message).toBe('Indiquez un versement initial ou un versement régulier.')
    expect((error as PlacementError).field).toBe('deposit')
  })

  test('any other refusal is a French sentence, never the English one', async () => {
    stubFetch({ violations: [{ propertyPath: 'years', message: 'This value should be between 1 and 60.' }] }, 422)

    const error = (await updatePlacement('A', INPUT).catch((caught: unknown) => caught)) as PlacementError

    expect(error.message).toBe("Le placement « PEA » n'a pas été modifié.")
    expect(error.field).toBeUndefined()
  })

  test('reads the simulation, month by month', async () => {
    const fetchMock = stubFetch({
      final: 1000,
      paid: 900,
      interest: 100,
      rent: 3,
      curve: [
        { month: 0, capital: 0, paid: 0 },
        { month: 12, capital: 1000, paid: 900 },
      ],
    })

    const simulation = await fetchSimulation('/api/finance/placements/A')

    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/finance/placements/A/simulation')
    expect(simulation.curve).toEqual([
      { month: 0, capital: 0, paid: 0 },
      { month: 12, capital: 1000, paid: 900 },
    ])
    expect(simulation.final).toBe(1000)
  })
})

describe('the placement form', () => {
  test.each([
    ['6', 600],
    ['3,5', 350],
    ['3.25', 325],
    ['5 %', 500],
    ['0', 0],
    ['100', 10000],
    ['100,01', null],
    ['3,555', null],
    ['-1', null],
    ['', null],
  ])('« %s » % is %s basis points', (typed, basisPoints) => {
    expect(percentToBasisPoints(typed)).toBe(basisPoints)
  })

  test('turns what was typed into cents and basis points, an empty amount being 0', () => {
    expect(
      toInput({ name: ' Livret ', initial: '', deposit: '1 200,50', frequency: 'yearly', rate: '3,5', years: '15' }),
    ).toEqual({ name: 'Livret', initial: 0, deposit: 120050, frequency: 'yearly', annualRate: 350, years: 15 })
  })

  test('gives an edited placement back as the owner typed it', () => {
    const values = valuesOf(toPlacement({ id: 'A', name: 'Assurance-vie', initial: 0, deposit: 120050, frequency: 'yearly', annualRate: 350, years: 15 }))

    expect(values).toEqual({ name: 'Assurance-vie', initial: '0', deposit: '1200,5', frequency: 'yearly', rate: '3,5', years: '15' })
    expect(toInput(values).deposit).toBe(120050)
  })

  test('refuses what the API would refuse, before anything is sent', () => {
    expect(validatePlacement(emptyValues())).toEqual({
      name: 'Le nom est obligatoire.',
      deposit: 'Indiquez un versement initial ou un versement régulier.',
      rate: 'Indiquez un rendement de 0 à 100 %.',
      years: 'Indiquez une durée de 1 à 60 ans.',
    })
    expect(validatePlacement({ name: 'PEA', initial: 'abc', deposit: '150', frequency: 'monthly', rate: '6', years: '61' })).toEqual({
      initial: 'Indiquez un montant en euros.',
      years: 'Indiquez une durée de 1 à 60 ans.',
    })
    expect(validatePlacement({ name: 'PEA', initial: '2 000', deposit: '150', frequency: 'monthly', rate: '6', years: '20' })).toEqual({})
  })
})
