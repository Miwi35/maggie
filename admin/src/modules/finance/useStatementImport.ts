import { useCallback, useState } from 'react'

/** One line of the file and what the import would do with it. */
export interface StatementImportRow {
  line: number
  bookedAt: string
  label: string
  amountCents: number
  currency: string
  /** Already on the account: the import leaves it alone. */
  duplicate: boolean
  /** The heading a rule gives it, or null when no rule claims it. */
  categoryName: string | null
}

export interface StatementImportReport {
  /** True for a rehearsal: nothing was written. */
  dryRun: boolean
  account: { id: string; name: string; currency: string }
  rowsRead: number
  imported: number
  skipped: number
  categorized: number
  first: string | null
  last: string | null
  totalCents: number
  /** Lines the parser could not read — the rest was still imported. */
  errors: string[]
  rows: StatementImportRow[]
}

/**
 * The CSV import, in its two steps.
 *
 * The file is posted twice on purpose: once to get the report, once to write.
 * Nothing is kept server-side between the two, and a repeated import is safe
 * by construction — the API recognises the movements it already stored.
 */
export function useStatementImport() {
  const [report, setReport] = useState<StatementImportReport | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [running, setRunning] = useState(false)

  const send = useCallback(
    async (
      accountId: string,
      file: File,
      confirm: boolean,
    ): Promise<StatementImportReport | null> => {
      setRunning(true)
      setError(null)

      const body = new FormData()
      body.append('account', accountId)
      body.append('file', file)
      if (confirm) {
        body.append('confirm', '1')
      }

      try {
        const token = localStorage.getItem('token')
        const res = await fetch('/api/finance/import-statement', {
          method: 'POST',
          headers: {
            Accept: 'application/json',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
          },
          body,
        })

        if (!res.ok) {
          // The API says what is wrong with this file in words meant to be
          // read; a status code on its own would send the reader guessing.
          // Parsed defensively: a 502 from the edge is an HTML page, and
          // letting that throw would blame the server for not answering when
          // it did answer.
          const refusal = await res
            .json()
            .then((body: { error?: string }) => body.error)
            .catch(() => undefined)

          setError(refusal ?? "Le fichier n'a pas pu être lu.")
          setReport(null)

          return null
        }

        const payload = (await res.json()) as StatementImportReport

        setReport(payload)

        return payload
      } catch {
        setError("Le serveur n'a pas répondu.")
        setReport(null)

        return null
      } finally {
        setRunning(false)
      }
    },
    [],
  )

  /** Back to a blank slate — a new file must not be read next to the old report. */
  const reset = useCallback(() => {
    setReport(null)
    setError(null)
  }, [])

  const simulate = useCallback(
    (accountId: string, file: File) => send(accountId, file, false),
    [send],
  )

  const confirm = useCallback(
    (accountId: string, file: File) => send(accountId, file, true),
    [send],
  )

  return { simulate, confirm, reset, report, error, running }
}
