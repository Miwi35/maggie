import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardActions from '@mui/material/CardActions'
import CardContent from '@mui/material/CardContent'
import Checkbox from '@mui/material/Checkbox'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { isAdvisor, isAuto, monthLabel, nLabel } from './scenariosApi'
import { ScenarioRepaymentFigures } from './ScenarioRepaymentFigures'
import type { Scenario } from './scenariosApi'

interface Props {
  scenario: Scenario
  /** The API's sentence for N against the current scenario; absent when none is current or N cannot be compared. */
  gap?: string
  compared: boolean
  comparable: boolean
  busy: boolean
  onToggleCompare: (scenario: Scenario) => void
  onMakeCurrent: (scenario: Scenario) => void
  onDuplicate: (scenario: Scenario) => void
  onEdit: (scenario: Scenario) => void
  onDelete: (scenario: Scenario) => void
}

/** One scenario: its date N, the API's sentence on the gap to the current one, its wellbeing and when its projects are done. Facts only. */
export const ScenarioCard = ({ scenario, gap, compared, comparable, busy, onToggleCompare, onMakeCurrent, onDuplicate, onEdit, onDelete }: Props) => {
  const advisor = isAdvisor(scenario)
  const auto = isAuto(scenario)

  return (
    <Card variant="outlined" data-testid="scenario-card" data-scenario={scenario.name} sx={{ borderColor: scenario.current ? 'primary.main' : undefined }}>
      <CardContent>
        {advisor && (
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
            Suggestion du conseiller · brouillon
          </Typography>
        )}
        <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap', mb: 1 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            {scenario.name}
          </Typography>
          {scenario.current && <Chip size="small" color="primary" label="Courant" />}
          {!advisor && scenario.status === 'draft' && <Chip size="small" variant="outlined" label="Brouillon" />}
        </Stack>
        <Typography data-testid="scenario-n">N : {nLabel(scenario)}</Typography>
        {gap && !scenario.current && (
          <Typography variant="body2" color="text.secondary" data-testid="scenario-gap">
            {gap}
          </Typography>
        )}
        <Typography variant="body2" color="text.secondary">
          Bien-être {scenario.metrics.wellbeing} · Projets bouclés{' '}
          {scenario.metrics.projectsDoneBy ? `en ${monthLabel(scenario.metrics.projectsDoneBy)}` : "au-delà de l'horizon"}
        </Typography>
        {scenario.repayments.length > 0 && <ScenarioRepaymentFigures scenario={scenario} />}
      </CardContent>
      <CardActions sx={{ flexWrap: 'wrap', px: 2, pb: 2, gap: 0.5 }}>
        <FormControlLabel
          sx={{ mr: 1 }}
          control={
            <Checkbox
              size="small"
              checked={compared}
              disabled={!compared && !comparable}
              onChange={() => onToggleCompare(scenario)}
              slotProps={{ input: { 'aria-label': `Comparer ${scenario.name}` } }}
            />
          }
          label="Comparer"
        />
        {!scenario.current && (
          <Button size="small" variant="contained" disabled={busy} onClick={() => onMakeCurrent(scenario)}>
            Rendre courant
          </Button>
        )}
        {advisor ? (
          <>
            <Button size="small" onClick={() => onEdit(scenario)}>
              Voir
            </Button>
            <Button size="small" color="inherit" disabled={busy} onClick={() => onDelete(scenario)}>
              Ignorer
            </Button>
          </>
        ) : (
          <>
            <Button size="small" disabled={busy} onClick={() => onDuplicate(scenario)}>
              Dupliquer
            </Button>
            {!auto && (
              <>
                <Button size="small" onClick={() => onEdit(scenario)}>
                  Modifier
                </Button>
                <Button size="small" color="error" disabled={busy} onClick={() => onDelete(scenario)}>
                  Supprimer
                </Button>
              </>
            )}
          </>
        )}
      </CardActions>
    </Card>
  )
}
