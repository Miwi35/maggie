import { describe, test, expect, vi, afterEach, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminContext, Notification, testDataProvider } from 'react-admin'
import { AccountLinkCard } from './AccountLinkCard'

const candidate = {
  id: '01BANK',
  name: 'Compte bancaire',
  bank: 'Mock Bank',
  type: 'checking',
  currency: 'EUR',
  balanceCents: 115520,
}

const json = (body: unknown, status = 200) =>
  Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))

const stubApi = (link: (body: { confirm: boolean }) => Promise<Response>, candidates = [candidate]) => {
  const fetchMock = vi.fn((url: string, init?: RequestInit) =>
    url.endsWith('/link-candidates')
      ? json({ candidates })
      : link(JSON.parse(String(init?.body)) as { confirm: boolean }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const renderCard = (manual = true, onLinked = vi.fn()) => {
  render(
    <AdminContext dataProvider={testDataProvider()}>
      <AccountLinkCard accountId="01MANUAL" accountName="Compte main" manual={manual} onLinked={onLinked} />
      <Notification />
    </AdminContext>,
  )
  return onLinked
}

const posts = (fetchMock: ReturnType<typeof stubApi>) =>
  fetchMock.mock.calls.filter(([url]) => String(url).endsWith('/link'))

describe('AccountLinkCard', () => {
  beforeEach(() => localStorage.setItem('token', 't'))
  afterEach(() => vi.unstubAllGlobals())

  test('rendersNothingWithoutCandidate', async () => {
    const fetchMock = stubApi(() => json({}), [])
    renderCard()
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(screen.queryByText('Rattacher au compte de la banque')).toBeNull()
  })

  test('rendersNothingForALinkedAccount', async () => {
    const fetchMock = stubApi(() => json({}))
    renderCard(false)
    await Promise.resolve()
    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.queryByText('Rattacher au compte de la banque')).toBeNull()
  })

  test('opensTheDialogAndShowsThePreview', async () => {
    const fetchMock = stubApi(() => json({ kept: '01MANUAL', moved: 2, dropped: 0 }))
    renderCard()
    await userEvent.click(await screen.findByRole('button', { name: 'Rattacher…' }))
    expect(await screen.findByText(/2 mouvements rejoignent ce compte/)).toBeTruthy()
    expect(posts(fetchMock)).toHaveLength(1)
    expect(JSON.parse(String(posts(fetchMock)[0][1]?.body))).toEqual({ bankAccountId: '01BANK', confirm: false })
  })

  test('confirmLinksAndNotifies', async () => {
    const fetchMock = stubApi((body) => json({ kept: '01MANUAL', moved: 2, dropped: 0, ...(body.confirm ? { linked: true } : {}) }))
    const onLinked = renderCard()
    await userEvent.click(await screen.findByRole('button', { name: 'Rattacher…' }))
    await screen.findByText(/2 mouvements/)
    await userEvent.click(screen.getByRole('button', { name: 'Rattacher' }))
    expect(await screen.findByText('Compte rattaché.')).toBeTruthy()
    expect(onLinked).toHaveBeenCalledTimes(1)
    expect(posts(fetchMock).map(([, init]) => JSON.parse(String(init?.body)).confirm)).toEqual([false, true])
    await waitFor(() => expect(screen.queryByText('Rattacher au compte de la banque')).toBeNull())
  })

  test('refusalShowsTheError', async () => {
    stubApi(() => json({ error: 'Les deux comptes sont dans des devises différentes.' }, 400))
    const onLinked = renderCard()
    await userEvent.click(await screen.findByRole('button', { name: 'Rattacher…' }))
    expect(await screen.findByText('Les deux comptes sont dans des devises différentes.')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Rattacher' })).toHaveProperty('disabled', true)
    expect(onLinked).not.toHaveBeenCalled()
  })

  test('cancelDoesNothing', async () => {
    const fetchMock = stubApi(() => json({ kept: '01MANUAL', moved: 2, dropped: 0 }))
    const onLinked = renderCard()
    await userEvent.click(await screen.findByRole('button', { name: 'Rattacher…' }))
    await screen.findByText(/2 mouvements/)
    await userEvent.click(screen.getByRole('button', { name: 'Annuler' }))
    expect(posts(fetchMock).every(([, init]) => JSON.parse(String(init?.body)).confirm === false)).toBe(true)
    expect(onLinked).not.toHaveBeenCalled()
    expect(screen.getByText('Rattacher au compte de la banque')).toBeTruthy()
  })
})
