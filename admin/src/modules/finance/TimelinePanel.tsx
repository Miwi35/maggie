import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Title } from 'react-admin'
import { TimelineRow } from './TimelineRow'
import { useFinanceTimeline } from './useFinanceTimeline'
import type { ScenarioChoice, TimelineCategory } from './timelineApi'

const NO_COMPARISON = ''

const CategoryChips = ({
  categories,
  hidden,
  onToggle,
}: {
  categories: TimelineCategory[]
  hidden: ReadonlySet<string>
  onToggle: (key: string) => void
}) => (
  <Stack
    direction="row"
    role="group"
    aria-label="Catégories d'événements"
    data-testid="timeline-filters"
    sx={{ gap: 1, flexWrap: 'wrap', mt: 2 }}
  >
    {categories.map((category) => {
      const shown = !hidden.has(category.key)

      return (
        <Chip
          key={category.key}
          label={category.label}
          clickable
          aria-pressed={shown}
          data-testid={`timeline-filter-${category.key}`}
          variant={shown ? 'filled' : 'outlined'}
          onClick={() => { onToggle(category.key) }}
          icon={
            <Box
              component="span"
              aria-hidden="true"
              sx={{ width: 10, height: 10, borderRadius: '50%', backgroundColor: category.color, opacity: shown ? 1 : 0.4 }}
            />
          }
          sx={{ '& .MuiChip-icon': { ml: 1.25, mr: -0.25 } }}
        />
      )
    })}
  </Stack>
)

const ScenarioSelect = ({
  choices,
  excludedId,
  value,
  disabled,
  onChange,
}: {
  choices: ScenarioChoice[]
  excludedId: string | null
  value: string | null
  disabled: boolean
  onChange: (id: string | null) => void
}) => {
  const options = choices.filter((choice) => !choice.current && choice.id !== excludedId)
  const automatic = options.filter((choice) => choice.automatic)
  const own = options.filter((choice) => !choice.automatic)

  return (
    <TextField
      select
      size="small"
      label="Scénario comparé"
      value={value ?? NO_COMPARISON}
      disabled={disabled}
      onChange={(event) => { onChange(event.target.value === NO_COMPARISON ? null : event.target.value) }}
      slotProps={{ select: { native: true }, inputLabel: { shrink: true } }}
      sx={{ minWidth: 260 }}
    >
      <option value={NO_COMPARISON}>Aucune comparaison</option>
      {own.length > 0 && (
        <optgroup label="Vos scénarios">
          {own.map((choice) => (
            <option key={choice.id} value={choice.id}>{choice.name}</option>
          ))}
        </optgroup>
      )}
      {automatic.length > 0 && (
        <optgroup label="Scénarios automatiques">
          {automatic.map((choice) => (
            <option key={choice.id} value={choice.id}>{choice.name}</option>
          ))}
        </optgroup>
      )}
    </TextField>
  )
}

/**
 * The 20-year frise (MAG-554): the events of the current scenario — or of the
 * planning without a scenario — up to independence, a second scenario beside it
 * on request, and the categories as filters. Facts only: the gap between the
 * two dates N is the API's sentence, said as it comes, and the hypotheses of the
 * projection are stated under the frise.
 */
export const TimelinePanel = () => {
  const { timeline, fetching, failed, compareId, compareWith, notice, scenarios, scenariosFailed, reload } = useFinanceTimeline()
  const [hidden, setHidden] = useState<ReadonlySet<string>>(new Set())

  const toggle = (key: string) => {
    setHidden((previous) => {
      const next = new Set(previous)
      if (next.has(key)) next.delete(key)
      else next.add(key)

      return next
    })
  }

  return (
    <Box data-testid="timeline-panel" aria-busy={fetching}>
      <Title title="Frise 20 ans" />
      <Typography variant="h5" component="h2">Frise 20 ans</Typography>

      {failed && (
        <Alert
          severity="error"
          sx={{ mt: 2 }}
          action={
            <Button color="inherit" size="small" onClick={reload}>
              Réessayer
            </Button>
          }
        >
          La frise n'a pas pu être chargée.
        </Alert>
      )}

      {notice && (
        <Alert severity="info" role="status" sx={{ mt: 2 }} data-testid="timeline-notice">
          {notice}
        </Alert>
      )}

      {fetching && !timeline && <LinearProgress aria-label="Chargement de la frise" sx={{ mt: 2 }} />}

      {timeline && (
        <>
          <Stack direction="row" sx={{ mt: 2, gap: 2, alignItems: 'center', flexWrap: 'wrap' }}>
            <ScenarioSelect
              choices={scenarios}
              excludedId={timeline.current.scenarioId}
              value={compareId}
              disabled={scenariosFailed}
              onChange={compareWith}
            />
            {scenariosFailed && (
              <Typography variant="caption" color="text.secondary">
                Les scénarios n'ont pas pu être chargés.
              </Typography>
            )}
          </Stack>

          <CategoryChips categories={timeline.categories} hidden={hidden} onToggle={toggle} />

          <TimelineRow
            scope="current"
            line={timeline.current}
            categories={timeline.categories}
            hidden={hidden}
            startMonth={timeline.startMonth}
            horizonMonths={timeline.horizonMonths}
          />

          {timeline.compared && (
            <TimelineRow
              scope="compared"
              line={timeline.compared}
              categories={timeline.categories}
              hidden={hidden}
              startMonth={timeline.startMonth}
              horizonMonths={timeline.horizonMonths}
            />
          )}

          {timeline.difference && (
            <Typography variant="body1" sx={{ mt: 2 }} data-testid="timeline-difference">
              {timeline.difference.sentence}
            </Typography>
          )}

          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }} data-testid="timeline-assumptions">
            {timeline.assumptions}
          </Typography>
        </>
      )}
    </Box>
  )
}
