import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import LinearProgress from '@mui/material/LinearProgress'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Title, useGetList, useNotify } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'
import { Amount } from './AmountField'
import { formatCents } from './accountTypes'
import { useStatementImport } from './useStatementImport'
import type { StatementImportReport } from './useStatementImport'

interface AccountChoice {
  id: string
  name: string
  currency: string
}

/** `2026-09-07` the way the bank wrote it on the statement. */
const asDay = (iso: string) => iso.split('-').reverse().join('/')

/**
 * React-admin hands back records keyed by their IRI; the API wants the bare
 * ULID. Posting the IRI gets "Cet identifiant de compte n'est pas valide."
 */
const idOf = (iri: string) => iri.split('/').pop() ?? iri

const period = (report: StatementImportReport) =>
  report.first === null ? '—' : `${asDay(report.first)} → ${asDay(report.last ?? report.first)}`

const Figure = ({ label, value }: { label: string; value: string }) => (
  <Box sx={{ minWidth: 150 }}>
    <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
      {label}
    </Typography>
    <Typography variant="h6">{value}</Typography>
  </Box>
)

/**
 * Import de relevé — the manual counterpart of the bank synchronisation.
 *
 * Always two steps: the rehearsal writes nothing and reports what it would do,
 * line by line, and only then is there something to confirm. That order is the
 * screen's whole reason to exist — a bank export is the one file nobody can
 * check by eye, and an import that went in blind cannot be undone line by
 * line.
 */
export const StatementImportPage = () => {
  const { data: accounts, isPending: accountsPending } = useGetList<AccountChoice>('accounts', {
    pagination: { page: 1, perPage: 100 },
    sort: { field: 'name', order: 'ASC' },
  })
  const { simulate, confirm, reset, report, error, running } = useStatementImport()
  const [accountId, setAccountId] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const notify = useNotify()

  // Only a rehearsal gives something to confirm: once the import has run, the
  // report on screen describes what was written, not what is still to write.
  const pending = report !== null && report.dryRun && report.imported > 0

  const clear = () => {
    setFile(null)
    reset()
    if (fileInput.current) {
      fileInput.current.value = ''
    }
  }

  const onSimulate = async () => {
    if (accountId === '' || file === null) {
      return
    }

    await simulate(idOf(accountId), file)
  }

  const onConfirm = async () => {
    if (accountId === '' || file === null) {
      return
    }

    const written = await confirm(idOf(accountId), file)
    if (written === null) {
      notify("L'import n'a pas abouti", { type: 'error' })

      return
    }

    notify(`${written.imported} opération(s) importée(s).`, { type: 'info' })
  }

  return (
    <>
      <Title title="Import de relevé" />

      <Card sx={{ mb: 2 }}>
        <CardContent>
          <FormSection
            first
            title="Déposer un relevé"
            description="Le fichier CSV exporté depuis votre banque, quel qu'en soit le format. Rien n'est écrit avant que vous ayez lu le rapport et confirmé."
          />

          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems="flex-start">
            <TextField
              select
              size="small"
              label="Compte"
              value={accountId}
              onChange={(event) => {
                setAccountId(event.target.value)
                reset()
              }}
              disabled={accountsPending}
              sx={{ minWidth: 220 }}
            >
              {(accounts ?? []).map((account) => (
                <MenuItem key={account.id} value={account.id}>
                  {account.name}
                </MenuItem>
              ))}
            </TextField>

            <TextField
              type="file"
              size="small"
              label="Fichier"
              inputRef={fileInput}
              slotProps={{
                inputLabel: { shrink: true },
                htmlInput: { accept: '.csv,text/csv,text/plain' },
              }}
              onChange={(event) => {
                const picked = (event.target as HTMLInputElement).files?.[0] ?? null
                setFile(picked)
                // A new file next to the previous file's report would invite a
                // confirmation of the wrong thing.
                reset()
              }}
              sx={{ minWidth: 260 }}
            />

            <Button
              variant="contained"
              disabled={accountId === '' || file === null || running}
              onClick={onSimulate}
            >
              Simuler l'import
            </Button>
          </Stack>

          {running && <LinearProgress sx={{ mt: 2 }} />}

          {error && (
            <Alert severity="error" sx={{ mt: 2 }}>
              {error}
            </Alert>
          )}
        </CardContent>
      </Card>

      {report && (
        <Card>
          <CardContent>
            <FormSection
              first
              title={report.dryRun ? 'Rapport de simulation' : 'Import effectué'}
              description={
                report.dryRun
                  ? `Rien n'a été écrit. ${report.account.name} recevrait les opérations ci-dessous.`
                  : `${report.imported} opération(s) écrite(s) sur ${report.account.name}.`
              }
            />

            <Stack direction="row" spacing={3} flexWrap="wrap" useFlexGap sx={{ mb: 2 }}>
              <Figure label="Lignes lues" value={String(report.rowsRead)} />
              <Figure
                label={report.dryRun ? 'À importer' : 'Importées'}
                value={String(report.imported)}
              />
              <Figure label="Déjà présentes" value={String(report.skipped)} />
              <Figure label="Catégorisées par règle" value={String(report.categorized)} />
              <Figure label="Période" value={period(report)} />
              <Figure
                label="Solde des mouvements"
                value={formatCents(report.totalCents, report.account.currency)}
              />
            </Stack>

            {report.errors.length > 0 && (
              <Alert severity="warning" sx={{ mb: 2 }}>
                {report.errors.length} ligne(s) illisible(s), ignorée(s) :
                <Box component="ul" sx={{ m: 0, pl: 3 }}>
                  {report.errors.map((line) => (
                    <li key={line}>{line}</li>
                  ))}
                </Box>
              </Alert>
            )}

            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Date</TableCell>
                  <TableCell>Libellé</TableCell>
                  <TableCell align="right">Montant</TableCell>
                  <TableCell>Catégorie</TableCell>
                  <TableCell>Sort</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {report.rows.map((row) => (
                  <TableRow key={row.line} sx={{ opacity: row.duplicate ? 0.55 : 1 }}>
                    <TableCell>{asDay(row.bookedAt)}</TableCell>
                    <TableCell>{row.label}</TableCell>
                    <TableCell align="right">
                      <Amount cents={row.amountCents} currency={row.currency} signed />
                    </TableCell>
                    <TableCell>
                      {row.categoryName ?? (
                        <Typography variant="body2" color="text.secondary">
                          Non catégorisée
                        </Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      {/* "Nouvelle", not "À importer": the figures above
                          already say "À importer", and one wording for two
                          different things makes both unreadable — on screen and
                          in the journeys that address them. */}
                      <Chip
                        size="small"
                        color={row.duplicate ? 'default' : 'success'}
                        label={row.duplicate ? 'Déjà présente' : 'Nouvelle'}
                      />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>

            <Stack direction="row" spacing={2} sx={{ mt: 2 }}>
              {pending && (
                <Button variant="contained" disabled={running} onClick={onConfirm}>
                  Importer {report.imported} opération(s)
                </Button>
              )}
              {report.dryRun && report.imported === 0 && (
                <Alert severity="info" sx={{ flexGrow: 1 }}>
                  Toutes les opérations de ce fichier sont déjà sur le compte : il n'y a rien à
                  importer.
                </Alert>
              )}
              <Button color="inherit" disabled={running} onClick={clear}>
                Recommencer
              </Button>
            </Stack>
          </CardContent>
        </Card>
      )}
    </>
  )
}
