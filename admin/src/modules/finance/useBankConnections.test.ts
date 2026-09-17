import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import {
  useBankConnections,
  expiryNotice,
  CONNECTION_STATUS_LABELS,
} from './useBankConnections'
import type { BankConnection } from './useBankConnections'

const connection = (overrides: Partial<BankConnection> = {}): BankConnection => ({
  id: 'conn-1',
  bankName: 'N26',
  country: 'FR',
  status: 'active',
  consentExpiresAt: '2026-12-15T10:00:00+00:00',
  daysBeforeExpiry: 60,
  lastSyncedAt: null,
  needsReconnecting: false,
  ...overrides,
})

describe('useBankConnections', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('loads the connections held for the user', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ connections: [connection()] }),
      }),
    )

    const { result } = renderHook(() => useBankConnections())

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.connections[0].bankName).toBe('N26')
  })

  test('connecting hands back where to send the user', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ connections: [] }) })
        .mockResolvedValueOnce({
          ok: true,
          json: () => Promise.resolve({ authorizationUrl: 'https://bank.example/consent' }),
        }),
    )

    const { result } = renderHook(() => useBankConnections())
    await waitFor(() => expect(result.current.loading).toBe(false))

    let url
    await act(async () => {
      url = await result.current.connect('N26', 'FR')
    })

    expect(url).toBe('https://bank.example/consent')
  })

  test('a provider that cannot list banks says so instead of showing none', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValueOnce({ ok: true, json: () => Promise.resolve({ connections: [] }) })
        .mockResolvedValueOnce({
          ok: false,
          json: () => Promise.resolve({ error: 'Application is not active' }),
        }),
    )

    const { result } = renderHook(() => useBankConnections())
    await waitFor(() => expect(result.current.loading).toBe(false))

    await act(async () => {
      await result.current.loadBanks('FR')
    })

    expect(result.current.banksError).toBe('Application is not active')
  })
})

describe('expiryNotice', () => {
  test('says nothing while the consent has time', () => {
    expect(expiryNotice(connection({ daysBeforeExpiry: 60 }))).toBeNull()
  })

  test('warns in the last week', () => {
    expect(expiryNotice(connection({ daysBeforeExpiry: 3 }))).toContain('3 jour(s)')
  })

  test('an expired access asks for a reconnection rather than stating a fact', () => {
    expect(expiryNotice(connection({ needsReconnecting: true }))).toContain('reconnectez')
  })

  test('a connection without an expiry says nothing', () => {
    expect(expiryNotice(connection({ daysBeforeExpiry: null }))).toBeNull()
  })
})

describe('CONNECTION_STATUS_LABELS', () => {
  test('covers the four states', () => {
    expect(Object.keys(CONNECTION_STATUS_LABELS)).toEqual([
      'pending',
      'active',
      'expired',
      'revoked',
    ])
  })
})
