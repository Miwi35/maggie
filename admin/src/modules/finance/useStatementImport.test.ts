import { describe, test, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useStatementImport } from './useStatementImport'
import type { StatementImportReport } from './useStatementImport'

const REPORT: StatementImportReport = {
  dryRun: true,
  account: { id: 'acc-1', name: 'Compte courant', currency: 'EUR' },
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
      bookedAt: '2026-09-07',
      label: 'CARREFOUR CITY',
      amountCents: -810,
      currency: 'EUR',
      duplicate: false,
      categoryName: 'Courses',
    },
  ],
}

const csv = () => new File(['Date;Libellé;Montant'], 'releve.csv', { type: 'text/csv' })

/** The multipart body the hook built, as plain fields. */
const sentFields = () => {
  const body = (vi.mocked(fetch).mock.calls[0][1] as RequestInit).body as FormData

  return {
    account: body.get('account'),
    confirm: body.get('confirm'),
    file: (body.get('file') as File).name,
  }
}

describe('useStatementImport', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    localStorage.setItem('token', 'test-jwt-token')
  })

  test('a rehearsal posts the file without asking for a write', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(REPORT) }),
    )

    const { result } = renderHook(() => useStatementImport())

    await act(async () => {
      await result.current.simulate('acc-1', csv())
    })

    expect(fetch).toHaveBeenCalledWith('/api/finance/import-statement', expect.anything())
    expect(sentFields()).toEqual({
      account: 'acc-1',
      // No `confirm` at all: the write has to be asked for.
      confirm: null,
      file: 'releve.csv',
    })
    expect(result.current.report).toEqual(REPORT)
    expect(result.current.error).toBeNull()
    expect(result.current.running).toBe(false)
  })

  test('confirming asks for the write', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: () => Promise.resolve({ ...REPORT, dryRun: false }),
      }),
    )

    const { result } = renderHook(() => useStatementImport())

    await act(async () => {
      await result.current.confirm('acc-1', csv())
    })

    expect(sentFields().confirm).toBe('1')
    expect(result.current.report?.dryRun).toBe(false)
  })

  test("the API's own wording is what the reader gets when the file is refused", async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        json: () => Promise.resolve({ error: 'Le fichier dépasse 2 Mo.', errors: [] }),
      }),
    )

    const { result } = renderHook(() => useStatementImport())

    let returned
    await act(async () => {
      returned = await result.current.simulate('acc-1', csv())
    })

    expect(returned).toBeNull()
    expect(result.current.error).toBe('Le fichier dépasse 2 Mo.')
    expect(result.current.report).toBeNull()
    expect(result.current.running).toBe(false)
  })

  test('a refusal that is not JSON still reads as a refusal, not as a silent server', async () => {
    // What the edge returns on a 502: an HTML page. Parsing it throws, and
    // blaming the server for not answering would be a lie about a server that
    // answered.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        json: () => Promise.reject(new SyntaxError('Unexpected token <')),
      }),
    )

    const { result } = renderHook(() => useStatementImport())

    let returned
    await act(async () => {
      returned = await result.current.simulate('acc-1', csv())
    })

    expect(returned).toBeNull()
    expect(result.current.error).toBe("Le fichier n'a pas pu être lu.")
  })

  test('a server that does not answer is said so, not swallowed', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network down')))

    const { result } = renderHook(() => useStatementImport())

    await act(async () => {
      await result.current.simulate('acc-1', csv())
    })

    expect(result.current.error).toBe("Le serveur n'a pas répondu.")
    expect(result.current.running).toBe(false)
  })

  test('resetting drops the previous report', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve(REPORT) }),
    )

    const { result } = renderHook(() => useStatementImport())

    await act(async () => {
      await result.current.simulate('acc-1', csv())
    })
    expect(result.current.report).not.toBeNull()

    act(() => {
      result.current.reset()
    })

    expect(result.current.report).toBeNull()
    expect(result.current.error).toBeNull()
  })
})
