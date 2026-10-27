import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useNotify } from 'react-admin'
import { CushionSettingsForm } from './CushionPage'
import { FinanceSettingsDialog } from './FinanceSettingsDialog'
import type { CushionConfig, CushionStatus } from './useCushionStatus'
import { isValidSalaryDay } from './useTreasuryOutlook'
import type { TreasurySaveResult, TreasurySettingsChange, TreasurySettingsState } from './useTreasuryOutlook'

type Save = (change: TreasurySettingsChange) => Promise<TreasurySaveResult>

const SalaryDayForm = ({ initial, onSave }: { initial: number | null; onSave: Save }) => {
  const [value, setValue] = useState(initial === null ? '' : String(initial))
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    const day = Number(value)
    if (value.trim() === '' || !isValidSalaryDay(day)) {
      setError('Indiquez un jour du mois, de 1 à 31.')

      return
    }
    const result = await onSave({ salaryExpectedDay: day })
    setError(result.ok ? null : result.message)
  }

  return (
    <Box>
      <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
        Jour de salaire attendu
      </Typography>
      <Stack direction="row" spacing={2} alignItems="flex-start">
        <TextField
          type="number"
          size="small"
          label="Jour du mois"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          error={error !== null}
          helperText={error ?? undefined}
          slotProps={{ htmlInput: { min: 1, max: 31 } }}
          sx={{ width: 180 }}
        />
        <Button variant="contained" onClick={submit} sx={{ mt: 0.25 }}>
          Enregistrer le jour
        </Button>
      </Stack>
    </Box>
  )
}

const OverdraftLimitForm = ({ limitCents, onSave }: { limitCents: number; onSave: Save }) => {
  const [value, setValue] = useState(String(limitCents / 100))
  const [error, setError] = useState<string | null>(null)

  const submit = async () => {
    const euros = Number(value)
    if (value.trim() === '' || Number.isNaN(euros)) {
      setError('Indiquez un montant en euros.')

      return
    }
    if (euros < 0) {
      setError("Le découvert autorisé ne peut pas être négatif : indiquez 0 s'il n'y en a pas.")

      return
    }
    const result = await onSave({ overdraftLimitCents: Math.round(euros * 100) })
    setError(result.ok ? null : result.message)
  }

  return (
    <Box>
      <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
        Découvert autorisé
      </Typography>
      <Stack direction="row" spacing={2} alignItems="flex-start">
        <TextField
          type="number"
          size="small"
          label="Découvert autorisé (€)"
          value={value}
          onChange={(e) => setValue(e.target.value)}
          error={error !== null}
          helperText={error ?? undefined}
          sx={{ width: 260 }}
        />
        <Button variant="contained" onClick={submit} sx={{ mt: 0.25 }}>
          Enregistrer le découvert
        </Button>
      </Stack>
    </Box>
  )
}

/**
 * « Régler »: the treasury's settings in one dialog — the cushion, the day the
 * salary is expected, the overdraft the bank allows. Each form saves on its own
 * and closes the dialog once the API has accepted it.
 */
export const TreasurySettingsDialog = ({
  open,
  onClose,
  cushion,
  configureCushion,
  settings,
  save,
}: {
  open: boolean
  onClose: () => void
  cushion: CushionStatus | null
  configureCushion: (config: CushionConfig) => Promise<boolean>
  settings: TreasurySettingsState | null
  save: Save
}) => {
  const notify = useNotify()

  const saveSetting: Save = async (change) => {
    const result = await save(change)
    if (result.ok) {
      notify('Réglage enregistré', { type: 'info' })
      onClose()
    }

    return result
  }

  return (
    <FinanceSettingsDialog open={open} onClose={onClose}>
      <Stack spacing={3} sx={{ pt: 1 }}>
        {cushion && <CushionSettingsForm status={cushion} configure={configureCushion} onSaved={onClose} />}
        {settings && <SalaryDayForm initial={settings.salaryExpectedDay} onSave={saveSetting} />}
        {settings && <OverdraftLimitForm limitCents={settings.overdraftLimitCents} onSave={saveSetting} />}
      </Stack>
    </FinanceSettingsDialog>
  )
}
