import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'
import { SOURCE_LABELS, weekdayLabel } from './upcomingLabels'
import type { UpcomingDay, UpcomingItem, UpcomingWindow as Window } from './useUpcomingProjection'

const WIDTH = 640
const HEIGHT = 150
const PAD = { left: 8, right: 8, top: 8, bottom: 22 }

/** Income and spending of each day, side by side: income on the left of the day, spending on the right. */
export const DayBars = ({ days }: { days: UpcomingDay[] }) => {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const incomeColour = chartColor(1, mode)
  const expenseColour = chartColor(2, mode)

  const peak = Math.max(1, ...days.map((day) => Math.max(day.incomeCents, day.expenseCents)))
  const slot = (WIDTH - PAD.left - PAD.right) / Math.max(days.length, 1)
  const barWidth = Math.max(Math.min(slot * 0.4, 18), 2)
  const base = HEIGHT - PAD.bottom
  const heightOf = (cents: number) => (cents > 0 ? Math.max((cents / peak) * (base - PAD.top), 2) : 0)
  // 7 days: every day is named; a month: the first, then every fifth.
  const labelled = (index: number) => days.length <= 7 || index % 5 === 0

  const totalIncome = days.reduce((sum, day) => sum + day.incomeCents, 0)
  const totalExpense = days.reduce((sum, day) => sum + day.expenseCents, 0)

  return (
    <Box data-testid="upcoming-days">
      <Stack direction="row" spacing={2} sx={{ mb: 0.5 }}>
        <Stack direction="row" spacing={0.5} alignItems="center">
          <Box sx={{ width: 10, height: 10, bgcolor: incomeColour }} />
          <Typography variant="caption">Revenus</Typography>
        </Stack>
        <Stack direction="row" spacing={0.5} alignItems="center">
          <Box sx={{ width: 10, height: 10, bgcolor: expenseColour }} />
          <Typography variant="caption">Dépenses</Typography>
        </Stack>
      </Stack>
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        role="img"
        aria-label={`Jour par jour : ${formatCents(totalIncome)} de revenus et ${formatCents(totalExpense)} de dépenses attendus`}
      >
        <line x1={PAD.left} x2={WIDTH - PAD.right} y1={base} y2={base} stroke={theme.palette.divider} />
        {days.map((day, index) => {
          const centre = PAD.left + slot * index + slot / 2
          const income = heightOf(day.incomeCents)
          const expense = heightOf(day.expenseCents)

          return (
            <g
              key={day.date}
              data-testid="upcoming-day"
              data-date={day.date}
              data-income-cents={day.incomeCents}
              data-expense-cents={day.expenseCents}
            >
              <title>{`${weekdayLabel(day.date)} : +${formatCents(day.incomeCents)} / −${formatCents(day.expenseCents)}`}</title>
              <rect x={centre - barWidth - 0.5} y={base - income} width={barWidth} height={income} fill={incomeColour} />
              <rect x={centre + 0.5} y={base - expense} width={barWidth} height={expense} fill={expenseColour} />
              {labelled(index) && (
                <text x={centre} y={HEIGHT - 6} textAnchor="middle" fontSize={10} fill={theme.palette.text.secondary}>
                  {days.length <= 7 ? weekdayLabel(day.date) : String(Number(day.date.slice(8, 10)))}
                </text>
              )}
            </g>
          )
        })}
      </svg>
    </Box>
  )
}

/** One bar per total, on a shared scale, so income and spending can be compared at a glance. */
const FlowRow = ({
  label,
  cents,
  scale,
  colour,
  testId,
}: {
  label: string
  cents: number
  scale: number
  colour: string
  testId: string
}) => (
  <Box data-testid={testId}>
    <Stack direction="row" justifyContent="space-between" alignItems="baseline">
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2" sx={{ fontWeight: 600 }}>
        {formatCents(cents)}
      </Typography>
    </Stack>
    <Box aria-hidden sx={{ height: 6, borderRadius: 3, bgcolor: 'action.hover', overflow: 'hidden' }}>
      <Box sx={{ width: `${Math.min((cents / scale) * 100, 100)}%`, height: '100%', bgcolor: colour }} />
    </Box>
  </Box>
)

const ItemList = ({ title, items, empty, testId }: { title: string; items: UpcomingItem[]; empty: string; testId: string }) => (
  <Box data-testid={testId}>
    <Typography variant="subtitle2" component="h4" sx={{ fontWeight: 600, mb: 0.5 }}>
      {title}
    </Typography>
    {items.length === 0 ? (
      <Typography variant="body2" color="text.secondary">
        {empty}
      </Typography>
    ) : (
      <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
        {items.map((item) => (
          <Stack
            component="li"
            key={`${item.date}-${item.label}-${item.source}-${item.amountCents}`}
            data-testid="upcoming-item"
            data-date={item.date}
            data-label={item.label}
            data-source={item.source}
            direction="row"
            spacing={1}
            alignItems="baseline"
            sx={{ py: 0.25 }}
          >
            <Typography variant="caption" color="text.secondary" sx={{ width: 56, flexShrink: 0 }}>
              {weekdayLabel(item.date)}
            </Typography>
            <Typography variant="body2" noWrap sx={{ flexGrow: 1, minWidth: 0 }}>
              {item.label}
            </Typography>
            <Typography variant="caption" color="text.secondary" sx={{ flexShrink: 0 }}>
              {SOURCE_LABELS[item.source]}
            </Typography>
            <Typography variant="body2" sx={{ flexShrink: 0, minWidth: 84, textAlign: 'right' }}>
              {formatCents(item.amountCents)}
            </Typography>
          </Stack>
        ))}
      </Box>
    )}
  </Box>
)

interface UpcomingWindowProps {
  testId: string
  title: string
  subtitle: string
  window: Window
}

/** The week or the month to come: what comes in, what goes out, day by day, and the net. */
export const UpcomingWindow = ({ testId, title, subtitle, window }: UpcomingWindowProps) => {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const scale = Math.max(1, window.incomeCents, window.expenseCents)

  return (
    <Card data-testid={testId} sx={{ flex: '1 1 420px', minWidth: 0 }}>
      <CardContent>
        <Typography variant="subtitle1" component="h3" sx={{ fontWeight: 600 }}>
          {title}
        </Typography>
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 1.5 }}>
          {subtitle}
        </Typography>

        <Stack spacing={1} sx={{ mb: 1.5 }}>
          <FlowRow label="Revenus attendus" cents={window.incomeCents} scale={scale} colour={chartColor(1, mode)} testId={`${testId}-income`} />
          <FlowRow label="Dépenses prévues" cents={window.expenseCents} scale={scale} colour={chartColor(2, mode)} testId={`${testId}-expense`} />
          <Stack direction="row" justifyContent="space-between" alignItems="baseline" data-testid={`${testId}-net`}>
            <Typography variant="caption" color="text.secondary">
              Net
            </Typography>
            <Typography variant="body1" sx={{ fontWeight: 600 }} color={window.netCents < 0 ? 'error.main' : 'text.primary'}>
              {formatCents(window.netCents)}
            </Typography>
          </Stack>
        </Stack>

        <DayBars days={window.days} />

        <Stack spacing={1.5} sx={{ mt: 1.5 }}>
          <ItemList
            title="Revenus attendus"
            items={window.incomes}
            empty="Aucun revenu attendu sur cette période."
            testId={`${testId}-incomes`}
          />
          <ItemList
            title="Opérations récurrentes et dépenses prévues"
            items={window.expenses}
            empty="Aucune opération récurrente ni dépense prévue sur cette période."
            testId={`${testId}-expenses`}
          />
        </Stack>
      </CardContent>
    </Card>
  )
}
