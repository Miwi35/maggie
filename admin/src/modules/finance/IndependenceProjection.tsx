import Box from '@mui/material/Box'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import { formatCentsRounded } from './accountTypes'
import { formatDuration, formatMonth, hasProjection, monthsBetween, nDateLabel } from './independenceDates'
import type {
  IndependenceCounter,
  IndependenceMilestoneKey,
  IndependenceProjection as Projection,
} from './useFinanceDashboard'

const MILESTONES: IndependenceMilestoneKey[] = ['25', '50', '75', '100']

const WIDTH = 600
const HEIGHT = 220
const PAD = { top: 12, right: 16, bottom: 28, left: 40 }

/**
 * The coverage of the train de vie by the rentes, month after month, as a
 * plain SVG line: Y from 0 to 100 %, X in years. A summary text stands for it
 * to a screen reader, which cannot read a line.
 */
const CoverageCurve = ({ projection }: { projection: Projection }) => {
  const theme = useTheme()
  const { curve } = projection
  const innerW = WIDTH - PAD.left - PAD.right
  const innerH = HEIGHT - PAD.top - PAD.bottom
  const x = (i: number) => PAD.left + (curve.length > 1 ? (i / (curve.length - 1)) * innerW : 0)
  const y = (coverage: number) => PAD.top + (1 - Math.min(1, Math.max(0, coverage))) * innerH

  const points = curve.map((p, i) => `${x(i).toFixed(1)},${y(p.coverage).toFixed(1)}`).join(' ')

  // One tick per year (at January), thinned so the labels never touch.
  const januaries = curve.map((p, i) => ({ p, i })).filter(({ p }) => p.month.endsWith('-01'))
  const step = Math.max(1, Math.ceil(januaries.length / 6))
  const ticks = januaries.filter((_, n) => n % step === 0)

  const first = curve[0]
  const last = curve[curve.length - 1]
  const summary =
    `Courbe du taux de couverture du train de vie par les rentes, de ${formatMonth(first.month)} ` +
    `(${Math.round(first.coverage * 100)} %) à ${formatMonth(last.month)} (${Math.round(last.coverage * 100)} %).`

  return (
    <Box data-testid="independence-curve" sx={{ mt: 2 }}>
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={summary}
        style={{ width: '100%', height: 'auto', display: 'block' }}
      >
        {[0, 0.25, 0.5, 0.75, 1].map((level) => (
          <g key={level}>
            <line
              x1={PAD.left}
              x2={WIDTH - PAD.right}
              y1={y(level)}
              y2={y(level)}
              stroke={theme.palette.divider}
              strokeWidth={1}
            />
            <text
              x={PAD.left - 6}
              y={y(level)}
              textAnchor="end"
              dominantBaseline="middle"
              fontSize={11}
              fill={theme.palette.text.secondary}
            >
              {Math.round(level * 100)} %
            </text>
          </g>
        ))}

        {ticks.map(({ p, i }) => (
          <text
            key={p.month}
            x={x(i)}
            y={HEIGHT - 8}
            textAnchor="middle"
            fontSize={11}
            fill={theme.palette.text.secondary}
          >
            {p.month.slice(0, 4)}
          </text>
        ))}

        {curve.length > 1 ? (
          <polyline
            points={points}
            fill="none"
            stroke={theme.palette.primary.main}
            strokeWidth={2.5}
            strokeLinejoin="round"
            strokeLinecap="round"
          />
        ) : (
          <circle cx={x(0)} cy={y(first.coverage)} r={3.5} fill={theme.palette.primary.main} />
        )}
      </svg>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
        {summary}
      </Typography>
    </Box>
  )
}

const milestoneLabel = (projection: Projection, key: IndependenceMilestoneKey): string => {
  const month = projection.milestones[key]
  if (null === month) return 'non atteint d\'ici 20 ans'

  return month === projection.startMonth ? 'atteint' : formatMonth(month)
}

/**
 * « Sources des rentes à venir » (MAG-558): what the projection counts beyond the measured rentes, the
 * rent of the investments and the placements withdrawn at 4 %. Simulations, said as such; nothing here
 * advises. Drawn only when there is at least one of them.
 */
export const RenteSourcesBlock = ({ counter }: { counter: IndependenceCounter }) => {
  const sources = counter.renteSources
  if (!sources || (sources.placements.count === 0 && sources.investments.count === 0)) return null

  return (
    <Box data-testid="independence-rente-sources" sx={{ mt: 3 }}>
      <Typography variant="subtitle2" component="h3" sx={{ fontWeight: 600 }}>
        Sources des rentes à venir
      </Typography>
      <Stack component="ul" sx={{ listStyle: 'none', p: 0, m: 0, mt: 1, fontVariantNumeric: 'tabular-nums' }} spacing={0.5}>
        {sources.placements.count > 0 && (
          <Typography component="li" variant="body2" data-testid="rente-source-placements">
            Placements · {sources.placements.count} · ≈ {formatCentsRounded(sources.placements.monthlyRentCents)} / mois à terme
          </Typography>
        )}
        {sources.investments.count > 0 && (
          <Typography component="li" variant="body2" data-testid="rente-source-investments">
            Investissements · {sources.investments.count} · ≈ {formatCentsRounded(sources.investments.monthlyRentCents)} / mois de loyer
          </Typography>
        )}
      </Stack>
      {sources.placements.count > 0 && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }} data-testid="rente-source-caption">
          Simulation · retrait de {String(sources.withdrawalRateBasisPoints / 100).replace('.', ',')} % par an sur le capital placé
        </Typography>
      )}
    </Box>
  )
}

/**
 * The date N, the coverage curve and the dated milestones of the Indépendance
 * page (MAG-549). Plain statements: a date that is not reached is said, not
 * blamed, and nothing here advises.
 */
export const IndependenceProjectionBlock = ({ counter }: { counter: IndependenceCounter }) => {
  if (!hasProjection(counter)) return null

  const { projection } = counter
  const reachedAlready = projection.nDate === projection.startMonth

  return (
    <Box data-testid="independence-projection" sx={{ mt: 3 }}>
      <Box data-testid="independence-n-date">
        {null === projection.nDate ? (
          <Typography variant="h6">N non atteinte d'ici 20 ans</Typography>
        ) : reachedAlready ? (
          <Typography variant="h6">Les rentes couvrent déjà le train de vie</Typography>
        ) : (
          <>
            <Typography variant="h6">{nDateLabel(projection)}</Typography>
            <Typography variant="body2" color="text.secondary">
              Dans {formatDuration(monthsBetween(projection.startMonth, projection.nDate))}
            </Typography>
          </>
        )}
      </Box>

      {projection.curve.length > 0 && <CoverageCurve projection={projection} />}

      <Stack component="ul" data-testid="independence-milestones" sx={{ listStyle: 'none', p: 0, m: 0, mt: 2 }} spacing={0.5}>
        {MILESTONES.map((key) => (
          <Typography component="li" variant="body2" key={key} data-milestone={key}>
            {key} % · {milestoneLabel(projection, key)}
          </Typography>
        ))}
      </Stack>

      <RenteSourcesBlock counter={counter} />

      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
        Projection d'après la planification actuelle des projets : 4 % par an sur ce que la
        planification laisse de la capacité d'épargne, matelas intact, revenus et train de vie stables.
      </Typography>
    </Box>
  )
}
