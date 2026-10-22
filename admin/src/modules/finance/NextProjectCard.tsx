import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'
import { delayLabel, joinNames, purchaseLabel } from './nextProject'
import { ganttMonthLabel } from './ProjectGantt'
import type { NextProject } from './useFinanceDashboard'

/**
 * « Prochain projet » (MAG-529): the first project of the planning, what is
 * gathered, when it falls, the two after it, and — a fact, not a reproach — what
 * the month's overspending pushes back. Not clickable.
 */
export const NextProjectCard = ({ project }: { project: NextProject }) => {
  const percent = Math.min(100, Math.round((project.gatheredCents / Math.max(1, project.amountCents)) * 100))
  const delay = project.delay

  return (
    <Card sx={{ mb: 2 }} data-testid="next-project-card">
      <CardContent>
        <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
          Prochain projet
        </Typography>
        <Stack direction="row" justifyContent="space-between" alignItems="baseline">
          <Typography variant="h6">{project.name}</Typography>
          <Typography variant="body2" color="text.secondary" data-testid="next-project-date">
            {purchaseLabel(project.purchaseMonth)}
          </Typography>
        </Stack>
        <Typography variant="body2" sx={{ mb: 0.5 }}>
          {formatCents(project.gatheredCents)} sur {formatCents(project.amountCents)}
        </Typography>
        <LinearProgress
          variant="determinate"
          value={percent}
          aria-label={`${project.name} : ${percent} % atteint`}
          sx={{ height: 6, borderRadius: 3, mb: 1.5 }}
        />

        {project.following.length > 0 && (
          <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1 }}>
            {project.following.map((next) => (
              <Chip
                key={next.projectId}
                data-testid="next-project-following"
                size="small"
                variant="outlined"
                label={`${next.name} · ${formatCents(next.amountCents)} · ${ganttMonthLabel(next.purchaseMonth)}`}
              />
            ))}
          </Stack>
        )}

        {delay && (
          <Typography data-testid="next-project-negative" variant="caption" color="error.main" sx={{ display: 'block', mt: 1 }}>
            Les dépassements de ce mois ({joinNames(delay.envelopes)}, +{formatCents(delay.overspentCents)}) repoussent {delay.name}{' '}
            {delayLabel(delay.months)}.
          </Typography>
        )}
      </CardContent>
    </Card>
  )
}
