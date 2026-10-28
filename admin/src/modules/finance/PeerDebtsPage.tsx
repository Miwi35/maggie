import { useCallback, useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Collapse from '@mui/material/Collapse'
import LinearProgress from '@mui/material/LinearProgress'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemText from '@mui/material/ListItemText'
import Stack from '@mui/material/Stack'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import { Link, Title } from 'react-admin'
import { formatCents } from './accountTypes'
import { contactLines, compensationSentence, netSentence, openDebts, owedSentence, peopleSentence } from './peerDebtsView'
import { fetchPeerDebtsOverview, fetchPeerDebtSummary } from './peerDebtsApi'
import type { PeerDebtSummary, PeerDebtWithHistory } from './peerDebtsApi'

type View = 'person' | 'object'

const Tile = ({ title, cents, testId }: { title: string; cents: number; testId: string }) => (
  <Card variant="outlined" sx={{ flex: 1, minWidth: 220 }} data-testid={testId}>
    <CardContent>
      <Typography variant="overline" color="text.secondary">
        {title}
      </Typography>
      <Typography variant="h5">{formatCents(cents)}</Typography>
    </CardContent>
  </Card>
)

const PersonRow = ({
  balance,
  debts,
}: {
  balance: PeerDebtSummary['byContact'][number]
  debts: PeerDebtWithHistory[]
}) => {
  const [open, setOpen] = useState(false)
  const lines = contactLines(debts, balance.contactId)

  return (
    <Box component="li" sx={{ listStyle: 'none' }} data-testid="peer-debt-person">
      <ListItemButton onClick={() => setOpen((v) => !v)} aria-expanded={open} aria-label={balance.name}>
        <ListItemText primary={balance.name} secondary={balance.compensates && balance.netCents !== 0 ? compensationSentence(balance.name, balance.netCents) : undefined} />
        <Typography variant="body2" sx={{ fontWeight: 600, whiteSpace: 'nowrap' }}>
          {netSentence(balance.netCents)}
        </Typography>
      </ListItemButton>
      <Collapse in={open} unmountOnExit>
        <List dense disablePadding sx={{ pl: 4 }} aria-label={`Détail de ${balance.name}`}>
          {lines.map((line) => (
            <Box component="li" key={line.key} sx={{ listStyle: 'none', display: 'flex', justifyContent: 'space-between', py: 0.5, pr: 2 }}>
              <Typography variant="body2">{line.object}</Typography>
              <Typography variant="body2" color="text.secondary">
                {line.direction === 'owed_to_me' ? 'te doit' : 'tu lui dois'} {formatCents(line.remainingCents)}
              </Typography>
            </Box>
          ))}
          <Box sx={{ py: 0.5 }}>
            <Button size="small" component={Link} to={`/contacts/${encodeURIComponent(`/api/contacts/${balance.contactId}`)}`}>
              Fiche et comptes de {balance.name}
            </Button>
          </Box>
        </List>
      </Collapse>
    </Box>
  )
}

const ObjectRow = ({ debt }: { debt: PeerDebtWithHistory }) => (
  <Box component="li" sx={{ listStyle: 'none', px: 2, py: 1 }} data-testid="peer-debt-object">
    <Stack direction="row" justifyContent="space-between" spacing={2}>
      <Typography variant="body1">{debt.object}</Typography>
      <Typography variant="body2" sx={{ fontWeight: 600, whiteSpace: 'nowrap' }}>
        {owedSentence(debt.direction, debt.remainingCents)}
      </Typography>
    </Stack>
    <Typography variant="body2" color="text.secondary">
      {peopleSentence(debt)}
    </Typography>
    {debt.notes && (
      <Typography variant="caption" color="text.secondary">
        {debt.notes}
      </Typography>
    )}
  </Box>
)

/**
 * « Créances et dettes » (MAG-455): who owes what, by person then by object, each
 * person one tap from their page. A section, with its own reads.
 */
export const PeerDebtsSection = () => {
  const [summary, setSummary] = useState<PeerDebtSummary | null>(null)
  const [debts, setDebts] = useState<PeerDebtWithHistory[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [view, setView] = useState<View>('person')

  const load = useCallback(() => {
    setError(null)
    Promise.all([fetchPeerDebtSummary(), fetchPeerDebtsOverview({ status: 'open' })])
      .then(([s, d]) => {
        setSummary(s)
        setDebts(d)
      })
      .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Impossible de lire les créances et les dettes.'))
  }, [])

  useEffect(load, [load])

  const open = debts ? openDebts(debts) : []
  const empty = summary !== null && summary.byContact.length === 0 && open.length === 0

  return (
    <Card data-testid="treasury-section-debts">
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={2} sx={{ mb: 2 }}>
          <Typography variant="h6" component="h2">
            Créances et dettes
          </Typography>
          <Button component={Link} to="/contacts/create" startIcon={<AddIcon />} variant="outlined" size="small">
            Personne
          </Button>
        </Stack>

        {error && (
          <Alert severity="error" action={<Button color="inherit" size="small" onClick={load}>Réessayer</Button>}>
            {error}
          </Alert>
        )}
        {!summary && !error && <LinearProgress aria-label="Chargement des créances et des dettes" />}

        {summary && debts && (
          <>
            <Stack direction="row" spacing={2} sx={{ mb: 2, flexWrap: 'wrap', gap: 2 }}>
              <Tile title="On te doit" cents={summary.owedToMeCents} testId="tile-owed-to-me" />
              <Tile title="Tu dois" cents={summary.iOweCents} testId="tile-i-owe" />
            </Stack>

            {empty ? (
              <Typography variant="body2" color="text.secondary">
                Rien en cours
              </Typography>
            ) : (
              <>
                <ToggleButtonGroup
                  exclusive
                  size="small"
                  value={view}
                  onChange={(_, next: View | null) => {
                    if (next !== null) setView(next)
                  }}
                  aria-label="Affichage des créances et des dettes"
                  sx={{ mb: 1 }}
                >
                  <ToggleButton value="person">Par personne</ToggleButton>
                  <ToggleButton value="object">Par objet</ToggleButton>
                </ToggleButtonGroup>

                {view === 'person' ? (
                  <List disablePadding aria-label="Par personne">
                    {summary.byContact.map((balance) => (
                      <PersonRow key={balance.contactId} balance={balance} debts={debts} />
                    ))}
                  </List>
                ) : (
                  <List disablePadding aria-label="Par objet">
                    {open.map((debt) => (
                      <ObjectRow key={debt.id} debt={debt} />
                    ))}
                  </List>
                )}
              </>
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}

export const PeerDebtsPage = () => (
  <Box sx={{ p: 2, maxWidth: 820 }}>
    <Title title="Créances et dettes" />
    <PeerDebtsSection />
  </Box>
)
