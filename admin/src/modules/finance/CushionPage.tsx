import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Title, useNotify } from 'react-admin'
import { formatCents } from './accountTypes'
import { CUSHION_STATE_LABELS, useCushionStatus } from './useCushionStatus'
import type { CushionConfig, CushionStatus } from './useCushionStatus'
import { accountLabel } from './accountLabel'
import { FinanceSettingsDialog } from './FinanceSettingsDialog'

const stateColor = (state: string): 'success' | 'warning' | 'info' =>
  state === 'complete' ? 'success' : state === 'recharging' ? 'warning' : 'info'

/** Euro-facing text field bound to an integer-cents value. */
const CentsField = ({ label, cents, onChange }: { label: string; cents: number; onChange: (cents: number) => void }) => (
  <TextField
    type="number"
    size="small"
    label={label}
    value={cents / 100}
    onChange={(e) => onChange(Math.round(Number(e.target.value) * 100))}
    sx={{ flex: '1 1 220px' }}
  />
)

/** The cushion as the owner reads it: target, balance, progress, state, the accounts counted, what is lent to people. */
export const CushionSection = ({ status }: { status: CushionStatus }) => (
  <Card data-testid="treasury-section-cushion">
    <CardContent>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 1, flexWrap: 'wrap' }}>
        <Typography variant="h6" component="h2" sx={{ flexGrow: 1 }}>
          Matelas de sécurité
        </Typography>
        <Chip
          label={CUSHION_STATE_LABELS[status.state] ?? status.state}
          color={stateColor(status.state)}
          size="small"
        />
      </Stack>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        {formatCents(status.currentCents)} sur {formatCents(status.targetCents)} —{' '}
        {status.monthsCovered} mois de revenu couverts sur {status.targetMonths}
      </Typography>

      <LinearProgress
        variant="determinate"
        value={status.coveragePercent}
        color={stateColor(status.state)}
        sx={{ height: 10, borderRadius: 5, mb: 1 }}
      />

      {status.deficitCents > 0 && (
        <Typography variant="body2">
          Il manque <strong>{formatCents(status.deficitCents)}</strong>
          {status.peerClaimsCents > 0 &&
            `, dont ${formatCents(Math.min(status.deficitCents, status.peerClaimsCents))} avancés à des proches`}{' '}
          — à {formatCents(status.monthlyRechargeCents)} par mois, le matelas est reconstitué en{' '}
          {status.rechargeMonths} mois.
        </Typography>
      )}

      <Typography variant="body2" sx={{ mt: 1 }} data-testid="cushion-share-rule">
        {status.criticalDeficitCents > 0
          ? `Sous le seuil critique (${status.criticalMonths} mois de dépenses, ${formatCents(status.criticalCents)}) : toute l'épargne va au matelas.`
          : status.deficitCents > 0 && status.cushionSharePercent >= 100
            ? "Matelas d'abord : toute l'épargne va au matelas, les projets attendent qu'il soit complet."
            : status.deficitCents > 0
            ? `Seuil critique atteint : ${status.cushionSharePercent} % de l'épargne va au matelas, le reste aux projets.`
            : "Matelas complet : toute l'épargne va aux projets."}
      </Typography>

      <Typography variant="subtitle2" sx={{ mt: 2 }}>
        Comptes comptés dans le matelas
      </Typography>
      {status.accounts.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Aucun compte matelas
        </Typography>
      ) : (
        status.accounts.map((account) => (
          <Typography key={account.id} variant="body2" color="text.secondary">
            {accountLabel(account)} — {formatCents(account.balanceCents, account.currency)}
          </Typography>
        ))
      )}
      {status.peerClaimsCents > 0 && (
        <Stack direction="row" justifyContent="space-between" spacing={2} data-testid="cushion-peer-claims">
          <Typography variant="body2" color="text.secondary">
            Avancé à des proches ({status.peerClaimObjects.join(', ')}) — revient au remboursement
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
            −{formatCents(status.peerClaimsCents)}
          </Typography>
        </Stack>
      )}
    </CardContent>
  </Card>
)

/**
 * The cushion's settings, seeded from the stored values — the dialog that holds
 * it is mounted on open, so the fields always start from the server's answer.
 */
export const CushionSettingsForm = ({
  status,
  configure,
  onSaved,
}: {
  status: CushionStatus
  configure: (config: CushionConfig) => Promise<boolean>
  onSaved: () => void
}) => {
  const notify = useNotify()
  const [targetMonths, setTargetMonths] = useState(status.targetMonths)
  const [incomeCents, setIncomeCents] = useState(status.monthlyNetIncomeCents)
  const [capCents, setCapCents] = useState(status.rechargeCapCents)
  const [horizonMonths, setHorizonMonths] = useState(status.rechargeTargetMonths)
  const [criticalMonths, setCriticalMonths] = useState(status.criticalMonths)
  const [sharePercent, setSharePercent] = useState(status.cushionSharePercent)

  const save = async () => {
    const ok = await configure({
      targetMonths,
      monthlyNetIncomeCents: incomeCents,
      rechargeCapCents: capCents,
      rechargeTargetMonths: horizonMonths,
      criticalMonths,
      cushionSharePercent: sharePercent,
    })
    notify(ok ? 'Matelas mis à jour' : 'Impossible de mettre à jour le matelas', { type: ok ? 'info' : 'error' })
    if (ok) {
      onSaved()
    }
  }

  return (
    <Box>
      <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
        Matelas
      </Typography>
      <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
        <TextField
          type="number"
          size="small"
          label="Cible (mois de revenu)"
          value={targetMonths}
          onChange={(e) => setTargetMonths(Number(e.target.value))}
          sx={{ flex: '1 1 220px' }}
        />
        <CentsField label="Revenu net mensuel (€)" cents={incomeCents} onChange={setIncomeCents} />
        <TextField
          type="number"
          size="small"
          label="Seuil critique (mois de dépenses)"
          value={criticalMonths}
          onChange={(e) => setCriticalMonths(Number(e.target.value))}
          slotProps={{ htmlInput: { min: 0, max: 24 } }}
          sx={{ flex: '1 1 220px' }}
        />
        <TextField
          type="number"
          size="small"
          label="Part du matelas (%)"
          value={sharePercent}
          onChange={(e) => setSharePercent(Number(e.target.value))}
          slotProps={{ htmlInput: { min: 0, max: 100 } }}
          sx={{ flex: '1 1 220px' }}
        />
        <CentsField label="Plafond de recharge (€/mois)" cents={capCents} onChange={setCapCents} />
        <TextField
          type="number"
          size="small"
          label="Horizon de recharge (mois)"
          value={horizonMonths}
          onChange={(e) => setHorizonMonths(Number(e.target.value))}
          sx={{ flex: '1 1 220px' }}
        />
      </Box>
      <Button variant="contained" onClick={save} sx={{ mt: 2 }}>
        Enregistrer le matelas
      </Button>
    </Box>
  )
}

/** « Matelas » on its own address: the cushion, and « Régler » for its settings. */
export const CushionPage = () => {
  const { status, loading, error, configure } = useCushionStatus()
  const [settingsOpen, setSettingsOpen] = useState(false)

  return (
    <>
      <Title title="Matelas de sécurité" />
      {loading && !status && <LinearProgress />}
      {error && !status && <Alert severity="error">Impossible de charger le matelas de sécurité.</Alert>}
      {status && (
        <>
          <Stack direction="row" justifyContent="flex-end" sx={{ mb: 2 }}>
            <Button variant="outlined" size="small" onClick={() => setSettingsOpen(true)}>
              Régler
            </Button>
          </Stack>
          <CushionSection status={status} />
          <FinanceSettingsDialog open={settingsOpen} onClose={() => setSettingsOpen(false)}>
            <CushionSettingsForm status={status} configure={configure} onSaved={() => setSettingsOpen(false)} />
          </FinanceSettingsDialog>
        </>
      )}
    </>
  )
}
