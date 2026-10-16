import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { formatCents } from './accountTypes'

/**
 * A static example, owner decision of 13 Oct: the card holds its place in the
 * layout of the finance home, and is neither clickable nor labelled as a
 * placeholder. MAG-425 replaces this constant with the real projects.
 */
export const NEXT_PROJECT_EXAMPLE = {
  title: 'Vélo électrique',
  savedCents: 74000,
  targetCents: 120000,
  delay: 'dans 4 mois',
  following: [
    { title: 'Week-end à Lisbonne', amountCents: 90000, delay: 'dans 9 mois' },
    { title: 'Machine à café', amountCents: 25000, delay: 'dans 11 mois' },
  ],
  negativeReturn: {
    label: 'Acheter le vélo dès maintenant laisserait le solde disponible à',
    balanceCents: -46000,
  },
}

export const NextProjectCard = () => {
  const project = NEXT_PROJECT_EXAMPLE
  const percent = Math.round((project.savedCents / project.targetCents) * 100)

  return (
    <Card sx={{ mb: 2 }} data-testid="next-project-card">
      <CardContent>
        <Typography variant="subtitle2" sx={{ fontWeight: 600, mb: 1 }}>
          Prochain projet
        </Typography>
        <Stack direction="row" justifyContent="space-between" alignItems="baseline">
          <Typography variant="h6">{project.title}</Typography>
          <Typography variant="body2" color="text.secondary">
            {project.delay}
          </Typography>
        </Stack>
        <Typography variant="body2" sx={{ mb: 0.5 }}>
          {formatCents(project.savedCents)} sur {formatCents(project.targetCents)}
        </Typography>
        <LinearProgress
          variant="determinate"
          value={percent}
          aria-label={`${project.title} : ${percent} % atteint`}
          sx={{ height: 6, borderRadius: 3, mb: 1.5 }}
        />

        {project.following.map((next) => (
          <Stack key={next.title} data-testid="next-project-following" direction="row" justifyContent="space-between" sx={{ mt: 0.5 }}>
            <Typography variant="caption" color="text.secondary">
              {next.title}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {formatCents(next.amountCents)} · {next.delay}
            </Typography>
          </Stack>
        ))}

        <Stack data-testid="next-project-negative" direction="row" justifyContent="space-between" sx={{ mt: 1 }}>
          <Typography variant="caption" color="text.secondary">
            {project.negativeReturn.label}
          </Typography>
          <Typography variant="caption" color="error.main">
            {formatCents(project.negativeReturn.balanceCents)}
          </Typography>
        </Stack>
      </CardContent>
    </Card>
  )
}
