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
import type { CushionStatus } from './useCushionStatus'
import { accountLabel } from './accountLabel'

const stateColor = (state: string): 'success' | 'warning' | 'info' =>
  state === 'complete' ? 'success' : state === 'recharging' ? 'warning' : 'info'

/** Euro-facing text field bound to an integer-cents value. */
const CentsField = ({
  label,
  cents,
  onChange,
  helperText,
}: {
  label: string
  cents: number
  onChange: (cents: number) => void
  helperText: string
}) => (
  <TextField
    type="number"
    size="small"
    label={label}
    value={cents / 100}
    onChange={(e) => onChange(Math.round(Number(e.target.value) * 100))}
    helperText={helperText}
    sx={{ flex: '1 1 220px' }}
  />
)

const CushionSummary = ({ status }: { status: CushionStatus }) => (
  <Card sx={{ mb: 2 }}>
    <CardContent>
      <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 1, flexWrap: 'wrap' }}>
        <Typography variant="h6" sx={{ flexGrow: 1 }}>
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
          — à{' '}
          {formatCents(status.monthlyRechargeCents)} par mois, le matelas est reconstitué en{' '}
          {status.rechargeMonths} mois.
          {status.isCappedByRechargeCap && (
            <Typography component="span" variant="body2" color="text.secondary">
              {' '}
              (le plafond mensuel allonge la durée plutôt que de forcer l'effort)
            </Typography>
          )}
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

      {status.blocksGreenScore && (
        <Alert severity="info" variant="outlined" sx={{ mt: 2 }}>
          Tant que le matelas n'est pas complet, le score global ne peut pas passer au vert.
        </Alert>
      )}

      <Typography variant="subtitle2" sx={{ mt: 2 }}>
        Comptes comptés dans le matelas
      </Typography>
      {status.accounts.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Aucun compte n'est marqué « matelas ». Cochez-le sur les comptes concernés pour qu'ils
          soient comptés ici.
        </Typography>
      ) : (
        status.accounts.map((account) => (
          <Typography key={account.id} variant="body2" color="text.secondary">
            {accountLabel(account)} — {formatCents(account.balanceCents, account.currency)}
          </Typography>
        ))
      )}
      {status.peerClaimsCents > 0 && (
        <Stack
          direction="row"
          justifyContent="space-between"
          spacing={2}
          data-testid="cushion-peer-claims"
        >
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
 * Settings seeded from the stored values. Remounted when they change server
 * side (see the key below), so no effect has to copy props into state.
 */
const CushionSettings = ({
  status,
  onSave,
}: {
  status: CushionStatus
  onSave: (config: {
    targetMonths: number
    monthlyNetIncomeCents: number
    rechargeCapCents: number
    rechargeTargetMonths: number
    criticalMonths: number
    cushionSharePercent: number
  }) => void
}) => {
  const [targetMonths, setTargetMonths] = useState(status.targetMonths)
  const [incomeCents, setIncomeCents] = useState(status.monthlyNetIncomeCents)
  const [capCents, setCapCents] = useState(status.rechargeCapCents)
  const [horizonMonths, setHorizonMonths] = useState(status.rechargeTargetMonths)
  const [criticalMonths, setCriticalMonths] = useState(status.criticalMonths)
  const [sharePercent, setSharePercent] = useState(status.cushionSharePercent)

  return (
      <Card>
        <CardContent>
          <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
            Réglages
          </Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            La cible est un nombre de mois de revenu net : elle se recalcule toute seule si le
            revenu change.
          </Typography>

          <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', maxWidth: 680 }}>
            <TextField
              type="number"
              size="small"
              label="Cible (mois de revenu)"
              value={targetMonths}
              onChange={(e) => setTargetMonths(Number(e.target.value))}
              helperText="3 mois est le repère courant."
              sx={{ flex: '1 1 220px' }}
            />
            <CentsField
              label="Revenu net mensuel (€)"
              cents={incomeCents}
              onChange={setIncomeCents}
              helperText="Sert uniquement à calculer la cible."
            />
            <TextField
              type="number"
              size="small"
              label="Seuil critique (mois de dépenses)"
              value={criticalMonths}
              onChange={(e) => setCriticalMonths(Number(e.target.value))}
              helperText="En dessous, toute l'épargne va au matelas (0 à 24)."
              slotProps={{ htmlInput: { min: 0, max: 24 } }}
              sx={{ flex: '1 1 220px' }}
            />
            <TextField
              type="number"
              size="small"
              label="Part du matelas (%)"
              value={sharePercent}
              onChange={(e) => setSharePercent(Number(e.target.value))}
              helperText="Au-delà du seuil, jusqu'à la cible ; le reste va aux projets. 100 % = matelas d'abord."
              slotProps={{ htmlInput: { min: 0, max: 100 } }}
              sx={{ flex: '1 1 220px' }}
            />
            <CentsField
              label="Plafond de recharge (€/mois)"
              cents={capCents}
              onChange={setCapCents}
              helperText="Ce que vous acceptez d'y remettre au maximum chaque mois."
            />
            <TextField
              type="number"
              size="small"
              label="Horizon de recharge (mois)"
              value={horizonMonths}
              onChange={(e) => setHorizonMonths(Number(e.target.value))}
              helperText="Durée souhaitée ; si le plafond est plus bas, c'est la durée qui s'allonge."
              sx={{ flex: '1 1 220px' }}
            />
          </Box>

          <Button
            variant="contained"
            onClick={() =>
              onSave({
                targetMonths,
                monthlyNetIncomeCents: incomeCents,
                rechargeCapCents: capCents,
                rechargeTargetMonths: horizonMonths,
                criticalMonths,
                cushionSharePercent: sharePercent,
              })
            }
            sx={{ mt: 2 }}
          >
            Enregistrer
          </Button>
        </CardContent>
      </Card>
  )
}

export const CushionPage = () => {
  const { status, loading, error, configure } = useCushionStatus()
  const notify = useNotify()

  const onSave = async (config: Parameters<typeof configure>[0]) => {
    const ok = await configure(config)
    notify(ok ? 'Matelas mis à jour' : 'Impossible de mettre à jour le matelas', {
      type: ok ? 'info' : 'error',
    })
  }

  return (
    <>
      <Title title="Matelas de sécurité" />
      {loading && !status && <LinearProgress />}
      {error && !status && (
        <Alert severity="error">Impossible de charger le matelas de sécurité.</Alert>
      )}
      {status && (
        <>
          <CushionSummary status={status} />
          <CushionSettings
            key={`${status.targetMonths}-${status.monthlyNetIncomeCents}-${status.rechargeCapCents}-${status.rechargeTargetMonths}-${status.criticalMonths}-${status.cushionSharePercent}`}
            status={status}
            onSave={onSave}
          />
        </>
      )}
    </>
  )
}
