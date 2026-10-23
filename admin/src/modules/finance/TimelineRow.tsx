import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { formatMonth } from './independenceDates'
import { eventFacts, MAX_LANES, nDateSentence, placeEvents, yearTicks } from './timelineLayout'
import type { TimelineCategory, TimelineLine } from './timelineApi'

export type TimelineScope = 'current' | 'compared'

const LANE_HEIGHT = 18
const MARKER = 12

/** `timeline-event-<id>` for the current scenario, `timeline-compared-event-<id>` for the one beside it. */
const testId = (scope: TimelineScope, part: 'event' | 'marker', id: string): string =>
  `timeline-${scope === 'compared' ? 'compared-' : ''}${part}-${id}`

interface Props {
  scope: TimelineScope
  line: TimelineLine
  categories: TimelineCategory[]
  /** Keys of the categories the owner switched off. */
  hidden: ReadonlySet<string>
  startMonth: string
  horizonMonths: number
}

/**
 * One scenario of the frise: its name and date N, the 20 years as an axis with
 * a marker per event on its month, and the same events as a list below — the
 * markers are only a picture, the list is what a screen reader and a keyboard read.
 */
export function TimelineRow({ scope, line, categories, hidden, startMonth, horizonMonths }: Props) {
  const theme = useTheme()
  const colors = new Map(categories.map((category) => [category.key, category.color]))
  const colorOf = (key: string): string => colors.get(key) ?? theme.palette.text.disabled

  const events = line.events.filter((event) => !hidden.has(event.category))
  const placed = placeEvents(events, startMonth, horizonMonths)
  const lanes = Math.min(MAX_LANES, Math.max(1, ...placed.map((p) => p.lane + 1)))
  const ticks = yearTicks(startMonth, horizonMonths)

  return (
    <Box
      component="section"
      aria-label={`Frise de « ${line.name} »`}
      data-testid={`timeline-row-${scope}`}
      data-scenario={line.scenarioId ?? ''}
      sx={{ mt: 2 }}
    >
      <Stack direction="row" spacing={2} sx={{ alignItems: 'baseline', flexWrap: 'wrap' }}>
        <Typography variant="h6" component="h3" data-testid={`timeline-name-${scope}`}>
          {line.name}
        </Typography>
        <Typography variant="body2" color="text.secondary" data-testid={`timeline-n-date-${scope}`}>
          {nDateSentence(line.nDate)}
        </Typography>
      </Stack>

      <Box
        aria-hidden="true"
        data-testid={`timeline-axis-${scope}`}
        sx={{
          position: 'relative',
          mt: 1,
          height: lanes * LANE_HEIGHT + 6,
          borderBottom: `1px solid ${theme.palette.divider}`,
        }}
      >
        {ticks.map((tick) => (
          <Box
            key={tick.year}
            sx={{ position: 'absolute', left: `${tick.percent}%`, top: 0, bottom: 0, borderLeft: `1px solid ${theme.palette.divider}` }}
          />
        ))}
        {placed.map(({ event, percent, lane }) => (
          <Box
            key={event.id}
            data-testid={testId(scope, 'marker', event.id)}
            data-month={event.month}
            title={`${formatMonth(event.month)} · ${event.label}`}
            sx={{
              position: 'absolute',
              left: `${percent}%`,
              top: 3 + lane * LANE_HEIGHT,
              width: MARKER,
              height: MARKER,
              ml: `${-MARKER / 2}px`,
              borderRadius: '50%',
              backgroundColor: colorOf(event.category),
              boxShadow: event.type === 'independence_reached' ? `0 0 0 2px ${theme.palette.text.primary}` : 'none',
            }}
          />
        ))}
      </Box>
      <Box aria-hidden="true" sx={{ position: 'relative', height: 18 }}>
        {ticks.map((tick, i) =>
          i % 2 === 0 ? (
            <Typography
              key={tick.year}
              variant="caption"
              color="text.secondary"
              sx={{ position: 'absolute', left: `${tick.percent}%`, fontSize: 11, transform: i === 0 ? 'none' : 'translateX(-50%)' }}
            >
              {tick.year}
            </Typography>
          ) : null,
        )}
      </Box>

      {events.length === 0 ? (
        <Typography variant="body2" color="text.secondary" data-testid={`timeline-empty-${scope}`}>
          Aucun événement à afficher pour ces catégories.
        </Typography>
      ) : (
        <Stack
          component="ul"
          spacing={0.5}
          aria-label={`Événements de « ${line.name} »`}
          sx={{ listStyle: 'none', p: 0, m: 0 }}
        >
          {placed.map(({ event }) => (
            <Stack
              component="li"
              key={event.id}
              direction="row"
              spacing={1}
              data-testid={testId(scope, 'event', event.id)}
              data-month={event.month}
              data-category={event.category}
              sx={{ alignItems: 'baseline' }}
            >
              <Box
                aria-hidden="true"
                component="span"
                sx={{ width: MARKER, height: MARKER, flexShrink: 0, borderRadius: '50%', backgroundColor: colorOf(event.category), alignSelf: 'center' }}
              />
              <Typography variant="body2" component="span" sx={{ minWidth: 112, color: 'text.secondary' }}>
                <time dateTime={event.month}>{formatMonth(event.month)}</time>
              </Typography>
              <Typography variant="body2" component="span">
                <strong>{event.label}</strong>
                {eventFacts(event).map((fact) => ` · ${fact}`).join('')}
              </Typography>
            </Stack>
          ))}
        </Stack>
      )}
    </Box>
  )
}
