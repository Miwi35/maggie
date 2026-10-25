import { useId, useState } from 'react'
import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { chartColor } from '../../design/tokens'
import { formatCents, formatCentsRounded } from './accountTypes'
import type { TreasuryDay, TreasuryMovement } from './useTreasuryOutlook'

const WIDTH = 640
const HEIGHT = 220
const PAD = { top: 12, right: 12, bottom: 24, left: 12 }

/** "14 oct." from "2026-10-14" — the day alone, so the axis stays readable at 60 days. */
export const shortDay = (date: string): string => {
  const [, month, day] = date.split('-')
  const names = [
    'janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin',
    'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.',
  ]

  return `${Number(day)} ${names[Number(month) - 1] ?? month}`
}

/** « Taxe foncière (récurrent) (-977,00 €) » — a movement of the day, as the table and the hover read it. */
export const movementText = (movement: TreasuryMovement): string =>
  `${movement.label}${movement.source === 'recurring' ? ' (récurrent)' : ''} (${formatCents(movement.amountCents)})`

const dayMovements = (day: TreasuryDay): string =>
  day.movements.length === 0 ? '' : ` — ${day.movements.map(movementText).join(', ')}`

interface TreasuryChartProps {
  projection: TreasuryDay[]
  limitCents: number
}

/**
 * The projected balance of the checking accounts, day by day. The line takes
 * categorical slot 1 of the validated palette (the 12-month chart's income);
 * the overdraft zone — below minus the allowed limit — is hatched and named,
 * so it reads without the colour or the figures.
 */
export const TreasuryChart = ({ projection, limitCents }: TreasuryChartProps) => {
  const theme = useTheme()
  const patternId = useId()
  const [hovered, setHovered] = useState<number | null>(null)
  const line = chartColor(1, theme.palette.mode === 'dark' ? 'dark' : 'light')
  const danger = theme.palette.error.main

  const balances = projection.map((day) => day.balanceCents)
  const high = Math.max(0, ...balances)
  const low = Math.min(0, -limitCents, ...balances)
  const span = Math.max(1, high - low)
  const top = high + span * 0.08
  const bottom = low - span * 0.08

  const plotWidth = WIDTH - PAD.left - PAD.right
  const plotHeight = HEIGHT - PAD.top - PAD.bottom
  const x = (index: number) =>
    PAD.left + (projection.length <= 1 ? plotWidth / 2 : (index / (projection.length - 1)) * plotWidth)
  const y = (cents: number) => PAD.top + ((top - cents) / (top - bottom)) * plotHeight

  const points = projection.map((day, index) => `${x(index)},${y(day.balanceCents)}`).join(' ')
  const active = hovered === null ? null : projection[hovered]
  const zoneTop = y(-limitCents)
  const zoneBottom = HEIGHT - PAD.bottom
  const hasZone = zoneTop < zoneBottom

  return (
    <Box>
      <Stack direction="row" spacing={2} sx={{ mb: 1 }}>
        <Stack direction="row" spacing={0.5} alignItems="center">
          <Box sx={{ width: 14, height: 3, backgroundColor: line }} />
          <Typography variant="caption" color="text.secondary">
            Solde projeté
          </Typography>
        </Stack>
        <Stack direction="row" spacing={0.5} alignItems="center">
          <Box
            sx={{
              width: 12,
              height: 12,
              border: `1px solid ${danger}`,
              background: `repeating-linear-gradient(135deg, ${danger} 0 2px, transparent 2px 5px)`,
            }}
          />
          <Typography variant="caption" color="text.secondary">
            Découvert
          </Typography>
        </Stack>
      </Stack>

      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        role="img"
        aria-label="Courbe du solde projeté, jour par jour, avec la zone de découvert"
        data-testid="treasury-chart"
        onMouseLeave={() => setHovered(null)}
      >
        <defs>
          <pattern id={patternId} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
            <line x1="0" y1="0" x2="0" y2="6" stroke={danger} strokeWidth="2" opacity="0.45" />
          </pattern>
        </defs>

        {hasZone && (
          <g data-testid="treasury-overdraft-zone">
            <rect
              x={PAD.left}
              y={zoneTop}
              width={plotWidth}
              height={zoneBottom - zoneTop}
              fill={`url(#${patternId})`}
              stroke={danger}
              strokeWidth="1"
            />
            <text x={PAD.left + 6} y={Math.min(zoneBottom - 6, zoneTop + 14)} fontSize="11" fill={danger}>
              Zone de découvert
            </text>
          </g>
        )}

        <line
          x1={PAD.left}
          x2={WIDTH - PAD.right}
          y1={y(0)}
          y2={y(0)}
          stroke={theme.palette.text.secondary}
          strokeDasharray="4 4"
          strokeWidth="1"
        />
        <text x={WIDTH - PAD.right} y={y(0) - 4} fontSize="11" textAnchor="end" fill={theme.palette.text.secondary}>
          {formatCentsRounded(0)}
        </text>

        <polyline points={points} fill="none" stroke={line} strokeWidth="2.5" strokeLinejoin="round" />

        {projection.map((day, index) => (
          <circle
            key={day.date}
            cx={x(index)}
            cy={y(day.balanceCents)}
            r={hovered === index ? 5 : projection.length > 31 ? 0 : 3}
            fill={day.balanceCents < -limitCents ? danger : line}
            onMouseEnter={() => setHovered(index)}
          />
        ))}

        {projection.map((day, index) => (
          <rect
            key={`hit-${day.date}`}
            x={x(index) - plotWidth / Math.max(1, projection.length) / 2}
            y={PAD.top}
            width={plotWidth / Math.max(1, projection.length)}
            height={plotHeight}
            fill="transparent"
            onMouseEnter={() => setHovered(index)}
          />
        ))}

        {projection.length > 0 && (
          <>
            <text x={PAD.left} y={HEIGHT - 6} fontSize="11" fill={theme.palette.text.secondary}>
              {shortDay(projection[0].date)}
            </text>
            <text x={WIDTH - PAD.right} y={HEIGHT - 6} fontSize="11" textAnchor="end" fill={theme.palette.text.secondary}>
              {shortDay(projection[projection.length - 1].date)}
            </text>
          </>
        )}
      </svg>

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }} aria-live="polite">
        {active
          ? `${shortDay(active.date)} : solde ${formatCents(active.balanceCents)}${active.balanceCents < -limitCents ? ' — en découvert' : ''}${dayMovements(active)}`
          : 'Survolez un jour pour le détail, ou lisez le tableau ci-dessous.'}
      </Typography>
    </Box>
  )
}

/**
 * What came in and what went out since today, both cumulated, on the same time
 * scale as the balance: the gap between the two lines is the balance's drift.
 * Two lines and no verdict — slots 1 and 2 of the palette, the outflows dashed
 * so they read without the colour.
 */
export const TreasuryFlowsChart = ({ projection }: { projection: TreasuryDay[] }) => {
  const theme = useTheme()
  const [hovered, setHovered] = useState<number | null>(null)
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const inflowColor = chartColor(1, mode)
  const outflowColor = chartColor(2, mode)

  const high = Math.max(1, ...projection.map((day) => Math.max(day.cumulativeInflowCents, day.cumulativeOutflowCents)))
  const plotWidth = WIDTH - PAD.left - PAD.right
  const plotHeight = HEIGHT - PAD.top - PAD.bottom
  const x = (index: number) =>
    PAD.left + (projection.length <= 1 ? plotWidth / 2 : (index / (projection.length - 1)) * plotWidth)
  const y = (cents: number) => PAD.top + (1 - cents / high) * plotHeight
  const line = (pick: (day: TreasuryDay) => number) =>
    projection.map((day, index) => `${x(index)},${y(pick(day))}`).join(' ')
  const active = hovered === null ? null : projection[hovered]

  return (
    <Box>
      <Stack direction="row" spacing={2} sx={{ mb: 1 }}>
        <Stack direction="row" spacing={0.5} alignItems="center">
          <Box sx={{ width: 14, height: 3, backgroundColor: inflowColor }} />
          <Typography variant="caption" color="text.secondary">
            Entrées cumulées
          </Typography>
        </Stack>
        <Stack direction="row" spacing={0.5} alignItems="center">
          <Box sx={{ width: 14, height: 0, borderTop: `3px dashed ${outflowColor}` }} />
          <Typography variant="caption" color="text.secondary">
            Sorties cumulées
          </Typography>
        </Stack>
      </Stack>

      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        width="100%"
        role="img"
        aria-label="Courbes cumulées des entrées et des sorties prévues, jour par jour"
        data-testid="treasury-flows-chart"
        onMouseLeave={() => setHovered(null)}
      >
        <line
          x1={PAD.left}
          x2={WIDTH - PAD.right}
          y1={y(0)}
          y2={y(0)}
          stroke={theme.palette.text.secondary}
          strokeWidth="1"
        />
        <polyline
          points={line((day) => day.cumulativeInflowCents)}
          fill="none"
          stroke={inflowColor}
          strokeWidth="2.5"
          strokeLinejoin="round"
        />
        <polyline
          points={line((day) => day.cumulativeOutflowCents)}
          fill="none"
          stroke={outflowColor}
          strokeWidth="2.5"
          strokeDasharray="6 4"
          strokeLinejoin="round"
        />

        {projection.map((day, index) => (
          <rect
            key={`hit-${day.date}`}
            x={x(index) - plotWidth / Math.max(1, projection.length) / 2}
            y={PAD.top}
            width={plotWidth / Math.max(1, projection.length)}
            height={plotHeight}
            fill="transparent"
            data-testid="treasury-flows-day"
            onMouseEnter={() => setHovered(index)}
          />
        ))}

        {active && (
          <>
            <circle cx={x(hovered as number)} cy={y(active.cumulativeInflowCents)} r={5} fill={inflowColor} />
            <circle cx={x(hovered as number)} cy={y(active.cumulativeOutflowCents)} r={5} fill={outflowColor} />
          </>
        )}

        {projection.length > 0 && (
          <>
            <text x={PAD.left} y={HEIGHT - 6} fontSize="11" fill={theme.palette.text.secondary}>
              {shortDay(projection[0].date)}
            </text>
            <text x={WIDTH - PAD.right} y={HEIGHT - 6} fontSize="11" textAnchor="end" fill={theme.palette.text.secondary}>
              {shortDay(projection[projection.length - 1].date)}
            </text>
          </>
        )}
      </svg>

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }} data-testid="treasury-flows-detail" aria-live="polite">
        {active
          ? `${shortDay(active.date)} : entrées cumulées ${formatCents(active.cumulativeInflowCents)}, sorties cumulées ${formatCents(active.cumulativeOutflowCents)}${dayMovements(active)}`
          : 'Survolez un jour pour le détail.'}
      </Typography>
    </Box>
  )
}
