import Button from '@mui/material/Button'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import IconButton from '@mui/material/IconButton'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import { newRepaymentRow } from './scenarioForm'
import type { RepaymentRow } from './scenarioForm'
import type { LoanChoice } from './scenariosApi'

interface Props {
  rows: RepaymentRow[]
  loans: LoanChoice[]
  onChange: (rows: RepaymentRow[]) => void
}

/** The early repayments simulated in a scenario: a loan, a month, an amount or the whole capital. */
export const ScenarioRepaymentRows = ({ rows, loans, onChange }: Props) => {
  const patch = (key: string, fields: Partial<RepaymentRow>) =>
    onChange(rows.map((row) => (row.key === key ? { ...row, ...fields } : row)))

  return (
    <Stack spacing={1}>
      <Typography variant="subtitle2">Remboursements anticipés</Typography>
      {rows.map((row, index) => (
        <Stack key={row.key} direction="row" data-testid="scenario-repayment" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          <TextField
            select
            size="small"
            label={`Prêt ${index + 1}`}
            value={row.loanId}
            onChange={(event) => patch(row.key, { loanId: event.target.value })}
            sx={{ minWidth: 160 }}
          >
            {loans.map((loan) => (
              <MenuItem key={loan.id} value={loan.id}>
                {loan.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            size="small"
            type="month"
            label={`Mois ${index + 1}`}
            value={row.month}
            onChange={(event) => patch(row.key, { month: event.target.value })}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField
            size="small"
            label={`Montant ${index + 1} (€)`}
            value={row.total ? '' : row.euros}
            disabled={row.total}
            onChange={(event) => patch(row.key, { euros: event.target.value })}
            slotProps={{ htmlInput: { inputMode: 'decimal' } }}
            sx={{ width: 140 }}
          />
          <FormControlLabel
            control={<Checkbox size="small" checked={row.total} onChange={(event) => patch(row.key, { total: event.target.checked })} />}
            label="Tout le capital"
          />
          <IconButton size="small" aria-label={`Retirer le remboursement ${index + 1}`} onClick={() => onChange(rows.filter((candidate) => candidate.key !== row.key))}>
            <DeleteOutlineIcon fontSize="small" />
          </IconButton>
        </Stack>
      ))}
      <Button size="small" startIcon={<AddIcon />} sx={{ alignSelf: 'flex-start' }} onClick={() => onChange([...rows, newRepaymentRow()])}>
        Ajouter un remboursement
      </Button>
    </Stack>
  )
}
