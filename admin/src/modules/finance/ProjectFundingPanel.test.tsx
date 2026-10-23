import { beforeEach, describe, expect, test, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, ResourceContextProvider, testDataProvider } from 'react-admin'
import polyglotI18nProvider from 'ra-i18n-polyglot'
import { MemoryRouter } from 'react-router-dom'
import { messages } from '../../i18n/messages'
import { ProjectFundingPanel } from './ProjectFundingPanel'
import type { Project } from './projectsApi'

const i18nProvider = polyglotI18nProvider(() => messages, 'fr')

const CAR: Project = {
  id: 'CAR',
  name: 'Voiture',
  amountCents: 1_000_000,
  currency: 'EUR',
  wellbeing: 'medium',
  wellbeingScore: 2,
  position: 0,
  dateMode: 'none',
  targetOn: null,
  dependsOn: [],
  note: null,
  unmetDependencies: [],
  fundingSources: [],
  salaryCents: 1_000_000,
  investment: null,
}

const ACCOUNTS = [
  { '@id': '/api/accounts/LIVRET', id: '/api/accounts/LIVRET', name: 'Livret jeune', type: 'savings', cushion: false },
  { '@id': '/api/accounts/MATELAS', id: '/api/accounts/MATELAS', name: 'Matelas', type: 'savings', cushion: true },
  { '@id': '/api/accounts/PEA', id: '/api/accounts/PEA', name: 'PEA', type: 'investment', cushion: false },
]

interface Write {
  url: string
  body: { sources: Record<string, unknown>[] }
}

/** The accounts, the stored loan of a loan source, and `PUT …/funding` answered by `answer`. */
function stubApi(answer: (write: Write) => Response, loan: Record<string, unknown> | null = null) {
  const writes: Write[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (init?.method === 'PUT') {
        const write = { url, body: JSON.parse(String(init.body)) }
        writes.push(write)

        return answer(write)
      }
      if (url.startsWith('/api/accounts')) return new Response(JSON.stringify({ member: ACCOUNTS }), { status: 200 })
      if (url.startsWith('/api/loans/')) return loan ? new Response(JSON.stringify(loan), { status: 200 }) : new Response('{}', { status: 404 })

      return new Response('{}', { status: 404 })
    }),
  )

  return writes
}

const accepted = (write: Write) => {
  const sources = write.body.sources.map((source) => ({
    kind: source.kind,
    amountCents: source.amountCents,
    accountId: source.account ?? null,
    loanId: source.kind === 'loan' ? 'L1' : null,
  }))
  const total = sources.reduce((sum, source) => sum + (source.amountCents as number), 0)

  return new Response(JSON.stringify({ success: true, project: { ...CAR, fundingSources: sources, salaryCents: CAR.amountCents - total } }), { status: 200 })
}

const renderPanel = (project: Project = CAR, onSaved = vi.fn()) => {
  render(
    <MemoryRouter>
      <AdminContext dataProvider={testDataProvider()} i18nProvider={i18nProvider}>
        <ResourceContextProvider value="projects">
          <ProjectFundingPanel project={project} onSaved={onSaved} />
        </ResourceContextProvider>
      </AdminContext>
    </MemoryRouter>,
  )

  return onSaved
}

const remainder = () => screen.getByTestId('funding-remainder')
const typeAmount = (label: RegExp, euros: number) => fireEvent.change(screen.getByLabelText(label), { target: { value: String(euros) } })

describe('ProjectFundingPanel', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
    localStorage.setItem('token', 'test-jwt')
  })

  test('shows the whole amount on the salary when nothing funds the project', async () => {
    stubApi(accepted)
    renderPanel()

    await waitFor(() => expect(remainder()).toHaveTextContent(/10\s000,00\s€ sur salaire/))
    expect(screen.queryAllByTestId('funding-source')).toHaveLength(0)
    expect(screen.getAllByTestId('funding-segment').map((segment) => segment.getAttribute('data-kind'))).toEqual(['salary'])
  })

  test('adds savings and a loan: the remainder follows, the bar stacks the sources, saving sends the whole list', async () => {
    const user = userEvent.setup()
    const writes = stubApi(accepted)
    const onSaved = renderPanel()
    await screen.findByRole('button', { name: 'Économies' })

    await user.click(screen.getByRole('button', { name: 'Économies' }))
    expect(screen.getByLabelText(/Compte \(économies\)/)).toHaveTextContent('Livret jeune')
    typeAmount(/^Montant économies/, 1000)

    await user.click(screen.getByRole('button', { name: 'Emprunt' }))
    typeAmount(/^Montant emprunt/, 6000)
    await user.type(screen.getByLabelText(/Taux annuel/), '4')
    await user.type(screen.getByLabelText(/Durée \(mois\)/), '24')

    expect(remainder()).toHaveTextContent(/3\s000,00\s€ sur salaire/)
    expect(screen.getAllByTestId('funding-segment').map((segment) => segment.getAttribute('data-kind'))).toEqual(['savings', 'loan', 'salary'])
    expect(screen.getByRole('button', { name: 'Emprunt' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Enregistrer les sources' }))

    await waitFor(() => expect(onSaved).toHaveBeenCalledTimes(1))
    expect(writes).toHaveLength(1)
    expect(writes[0].url).toBe('/api/finance/projects/CAR/funding')
    expect(writes[0].body.sources).toEqual([
      { kind: 'savings', amountCents: 100_000, account: 'LIVRET' },
      { kind: 'loan', amountCents: 600_000, loan: { type: 'amortizing_fixed', annualRateBasisPoints: 400, durationMonths: 24, earlyRepaymentAllowed: false } },
    ])
    expect(onSaved.mock.calls[0][0]).toMatchObject({ salaryCents: 300_000 })
  })

  test('the cushion is never offered as a source', async () => {
    const user = userEvent.setup()
    stubApi(accepted)
    renderPanel()
    await user.click(await screen.findByRole('button', { name: 'Économies' }))

    await user.click(screen.getByLabelText(/Compte \(économies\)/))
    const options = await screen.findAllByRole('option')

    expect(options.map((option) => option.textContent)).toEqual(['Livret jeune'])
  })

  test('« + » and « − » move the amount by a hundred euros, never below 0', async () => {
    const user = userEvent.setup()
    stubApi(accepted)
    renderPanel()
    await user.click(await screen.findByRole('button', { name: 'Économies' }))

    await user.click(screen.getByRole('button', { name: /Plus montant économies/ }))
    await user.click(screen.getByRole('button', { name: /Plus montant économies/ }))
    expect(screen.getByLabelText(/^Montant économies/)).toHaveValue(200)
    expect(remainder()).toHaveTextContent(/9\s800,00\s€ sur salaire/)

    for (let press = 0; press < 3; press++) await user.click(screen.getByRole('button', { name: /Moins montant économies/ }))
    expect(screen.getByLabelText(/^Montant économies/)).toHaveValue(0)
  })

  test('removes a source', async () => {
    const user = userEvent.setup()
    stubApi(accepted)
    renderPanel({ ...CAR, fundingSources: [{ kind: 'savings', amountCents: 100_000, accountId: 'LIVRET', loanId: null, sourceProjectId: null }], salaryCents: 900_000 })
    await screen.findByTestId('funding-source')

    await user.click(screen.getByRole('button', { name: 'Retirer économies' }))

    expect(screen.queryAllByTestId('funding-source')).toHaveLength(0)
    expect(remainder()).toHaveTextContent(/10\s000,00\s€ sur salaire/)
  })

  test('reads the stored sources back, the loan with its conditions', async () => {
    stubApi(accepted, { id: '/api/loans/L1', annualRateBasisPoints: 400, durationMonths: 24, type: 'amortizing_fixed', initialAmountCents: 600_000 })
    renderPanel({
      ...CAR,
      fundingSources: [
        { kind: 'savings', amountCents: 100_000, accountId: 'LIVRET', loanId: null, sourceProjectId: null },
        { kind: 'loan', amountCents: 600_000, accountId: null, loanId: 'L1', sourceProjectId: null },
      ],
      salaryCents: 300_000,
    })

    const rows = await screen.findAllByTestId('funding-source')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByLabelText(/^Montant économies/)).toHaveValue(1000)
    expect(within(rows[1]).getByLabelText(/^Montant emprunt/)).toHaveValue(6000)
    expect(screen.getByLabelText(/Taux annuel/)).toHaveValue(4)
    expect(screen.getByLabelText(/Durée \(mois\)/)).toHaveValue(24)
    expect(remainder()).toHaveTextContent(/3\s000,00\s€ sur salaire/)
  })

  test('shows the API refusal as it is worded and keeps nothing', async () => {
    const user = userEvent.setup()
    const message = 'The sources add up to 1200000 cents, more than the project\'s 1000000: lower them.'
    stubApi(() => new Response(JSON.stringify({ error: message, current: CAR }), { status: 400 }))
    const onSaved = renderPanel()
    await user.click(await screen.findByRole('button', { name: 'Économies' }))
    typeAmount(/^Montant économies/, 12000)

    await user.click(screen.getByRole('button', { name: 'Enregistrer les sources' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(onSaved).not.toHaveBeenCalled()
    expect(screen.getByLabelText(/^Montant économies/)).toHaveValue(12000)
  })

  test('a loan whose stored conditions cannot be read is saved without them', async () => {
    const user = userEvent.setup()
    const writes = stubApi(accepted, null)
    renderPanel({
      ...CAR,
      fundingSources: [{ kind: 'loan', amountCents: 600_000, accountId: null, loanId: 'L1', sourceProjectId: null }],
      salaryCents: 400_000,
    })

    expect(await screen.findByText(/conditions de l'emprunt n'ont pas pu être lues/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Enregistrer les sources' }))

    await waitFor(() => expect(writes).toHaveLength(1))
    expect(writes[0].body.sources).toEqual([{ kind: 'loan', amountCents: 600_000 }])
  })

  test('shows the resale of an investment as read-only, named after it, and never sends it back', async () => {
    const user = userEvent.setup()
    const writes = stubApi(accepted)
    const onSaved = vi.fn()
    render(
      <MemoryRouter>
        <AdminContext dataProvider={testDataProvider()} i18nProvider={i18nProvider}>
          <ResourceContextProvider value="projects">
            <ProjectFundingPanel
              project={{
                ...CAR,
                fundingSources: [
                  { kind: 'savings', amountCents: 100_000, accountId: 'LIVRET', loanId: null, sourceProjectId: null },
                  { kind: 'resale', amountCents: 500_000, accountId: null, loanId: null, sourceProjectId: 'BOX' },
                ],
                salaryCents: 400_000,
              }}
              names={new Map([['BOX', 'Box à louer']])}
              onSaved={onSaved}
            />
          </ResourceContextProvider>
        </AdminContext>
      </MemoryRouter>,
    )

    const rows = await screen.findAllByTestId('funding-source')
    const resale = rows.find((node) => node.getAttribute('data-kind') === 'resale') as HTMLElement
    expect(resale).toHaveTextContent('Revente de Box à louer')
    expect(resale).toHaveTextContent(/5\s000,00\s€/)
    expect(resale).toHaveTextContent("Elle suit l'investissement")
    expect(screen.queryByRole('button', { name: 'Retirer revente' })).toBeNull()
    expect(screen.getAllByTestId('funding-segment').map((segment) => segment.getAttribute('data-kind'))).toContain('resale')
    expect(remainder()).toHaveTextContent(/4\s000,00\s€ sur salaire/)

    await user.click(screen.getByRole('button', { name: 'Enregistrer les sources' }))

    await waitFor(() => expect(writes).toHaveLength(1))
    expect(writes[0].body.sources.map((source) => source.kind)).toEqual(['savings'])
  })

  test('names a resale whose investment is not known', async () => {
    stubApi(accepted)
    renderPanel({
      ...CAR,
      fundingSources: [{ kind: 'resale', amountCents: 500_000, accountId: null, loanId: null, sourceProjectId: 'GONE' }],
      salaryCents: 500_000,
    })

    expect(await screen.findByText("Revente d'un investissement")).toBeInTheDocument()
  })
})
