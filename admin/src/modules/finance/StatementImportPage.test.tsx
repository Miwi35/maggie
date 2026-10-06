import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import type { DataProvider } from 'react-admin'
import { StatementImportPage } from './StatementImportPage'
import type { StatementImportReport } from './useStatementImport'

/**
 * Accounts keyed by their IRI, the way the Hydra data provider really hands
 * them back. Bare ULIDs here would hide the one thing the page has to do with
 * them — the API refuses an IRI as an account identifier.
 */
const dataProvider = testDataProvider({
  getList: (() =>
    Promise.resolve({
      data: [
        { id: '/api/accounts/01JACC0000000000000000001', name: 'Compte courant', currency: 'EUR' },
        { id: '/api/accounts/01JACC0000000000000000002', name: 'Livret A', currency: 'EUR' },
      ],
      total: 2,
    })) as unknown as DataProvider['getList'],
})

/** The rehearsal the API answers: one line new, one already stored. */
const REHEARSAL: StatementImportReport = {
  dryRun: true,
  account: { id: 'acc-checking', name: 'Compte courant', currency: 'EUR' },
  rowsRead: 2,
  imported: 1,
  skipped: 1,
  categorized: 1,
  first: '2026-09-07',
  last: '2026-09-07',
  totalCents: -810,
  errors: [],
  rows: [
    {
      line: 2,
      bookedAt: '2026-09-01',
      label: 'CARREFOUR MARKET 4412',
      amountCents: -4599,
      currency: 'EUR',
      duplicate: true,
      categoryName: null,
    },
    {
      line: 3,
      bookedAt: '2026-09-07',
      label: 'CARREFOUR CITY',
      amountCents: -810,
      currency: 'EUR',
      duplicate: false,
      categoryName: 'Alimentation',
    },
  ],
}

const fetchMock = vi.fn()

const json = (body: unknown, ok = true) => ({ ok, json: async () => body }) as Response

/** The fields the last request carried, as the API would read them. */
const lastSent = () => {
  const calls = fetchMock.mock.calls
  const body = (calls[calls.length - 1][1] as RequestInit).body as FormData

  return { account: body.get('account'), confirm: body.get('confirm') }
}

/** One figure of the report: its label and the value under it share a wrapper. */
const figure = (label: string) => screen.getByText(label, { exact: true }).parentElement

const renderPage = () =>
  render(
    <AdminContext dataProvider={dataProvider}>
      <StatementImportPage />
      <Notification />
    </AdminContext>,
  )

const file = () =>
  new File(['Date;Libellé;Montant\n07/09/2026;CARREFOUR CITY;-8,10'], 'releve.csv', {
    type: 'text/csv',
  })

/** Picks the account and drops the file — everything before "Simuler". */
const fillIn = async () => {
  await userEvent.click(await screen.findByRole('combobox', { name: /Compte/ }))
  await userEvent.click(screen.getByRole('option', { name: 'Compte courant' }))
  await userEvent.upload(screen.getByLabelText(/Fichier/), file())
}

describe('StatementImportPage', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'test-jwt-token')
    fetchMock.mockResolvedValue(json(REHEARSAL))
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    fetchMock.mockReset()
  })

  test('nothing can be simulated before an account and a file are chosen', async () => {
    renderPage()

    expect(await screen.findByRole('button', { name: "Simuler l'import" })).toBeDisabled()
    // And no report, so nothing to confirm.
    expect(screen.queryByRole('button', { name: /^Importer/ })).not.toBeInTheDocument()
  })

  test('the rehearsal names the duplicate it would drop and the category a rule gives', async () => {
    renderPage()
    await fillIn()
    await userEvent.click(screen.getByRole('button', { name: "Simuler l'import" }))

    expect(await screen.findByText('Rapport de simulation')).toBeInTheDocument()

    const dropped = screen.getByRole('row', { name: /CARREFOUR MARKET 4412/ })
    expect(dropped).toHaveTextContent('Déjà présente')
    expect(dropped).toHaveTextContent('01/09/2026')

    const fresh = screen.getByRole('row', { name: /CARREFOUR CITY/ })
    expect(fresh).toHaveTextContent('Nouvelle')
    expect(fresh).toHaveTextContent('Alimentation')

    // The figures that decide whether to confirm, read whole: "1" is contained
    // in "11" too, so a count off by a digit has to fail here.
    expect(figure('Lignes lues')).toHaveTextContent('Lignes lues2')
    expect(figure('À importer')).toHaveTextContent('À importer1')
    expect(figure('Déjà présentes')).toHaveTextContent('Déjà présentes1')
    expect(figure('Catégorisées par règle')).toHaveTextContent('Catégorisées par règle1')
    expect(figure('Période')).toHaveTextContent('Période07/09/2026 → 07/09/2026')
    expect(figure('Solde des mouvements')).toHaveTextContent('-8,10')

    // The bare ULID, not the IRI react-admin keys the record by — and a
    // rehearsal asks for no write.
    expect(lastSent()).toEqual({ account: '01JACC0000000000000000001', confirm: null })
  })

  test('confirming asks the API to write, and says how much was written', async () => {
    renderPage()
    await fillIn()
    await userEvent.click(screen.getByRole('button', { name: "Simuler l'import" }))

    const importButton = await screen.findByRole('button', { name: 'Importer 1 opération(s)' })
    fetchMock.mockResolvedValue(json({ ...REHEARSAL, dryRun: false }))
    await userEvent.click(importButton)

    await waitFor(() =>
      expect(lastSent()).toEqual({ account: '01JACC0000000000000000001', confirm: '1' }),
    )
    expect(await screen.findByText('Import effectué')).toBeInTheDocument()
    expect(await screen.findByText('1 opération(s) importée(s).')).toBeInTheDocument()

    // What is written cannot be written again from the same report.
    expect(screen.queryByRole('button', { name: /^Importer/ })).not.toBeInTheDocument()
  })

  test('a file the API refuses is explained, and offers nothing to confirm', async () => {
    fetchMock.mockResolvedValue(json({ error: 'Aucun mouvement n’a pu être lu.' }, false))

    renderPage()
    await fillIn()
    await userEvent.click(screen.getByRole('button', { name: "Simuler l'import" }))

    expect(await screen.findByText('Aucun mouvement n’a pu être lu.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Importer/ })).not.toBeInTheDocument()
  })

  test('a file whose every line is already stored offers nothing to confirm', async () => {
    fetchMock.mockResolvedValue(
      json({
        ...REHEARSAL,
        imported: 0,
        skipped: 2,
        categorized: 0,
        first: null,
        last: null,
        totalCents: 0,
        rows: REHEARSAL.rows.map((row) => ({ ...row, duplicate: true, categoryName: null })),
      }),
    )

    renderPage()
    await fillIn()
    await userEvent.click(screen.getByRole('button', { name: "Simuler l'import" }))

    expect(await screen.findByText(/il n'y a rien à importer/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Importer/ })).not.toBeInTheDocument()
  })

  test('picking another file clears the report it does not describe', async () => {
    renderPage()
    await fillIn()
    await userEvent.click(screen.getByRole('button', { name: "Simuler l'import" }))
    expect(await screen.findByText('Rapport de simulation')).toBeInTheDocument()

    await userEvent.upload(
      screen.getByLabelText(/Fichier/),
      new File(['Date;Libellé;Montant'], 'autre.csv', { type: 'text/csv' }),
    )

    await waitFor(() =>
      expect(screen.queryByText('Rapport de simulation')).not.toBeInTheDocument(),
    )
  })
})
