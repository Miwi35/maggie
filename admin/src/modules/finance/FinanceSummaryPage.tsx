import Alert from '@mui/material/Alert'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import MuiLink from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Title } from 'react-admin'
import { Link, useParams } from 'react-router-dom'
import { FINANCE_DASHBOARD_PATH, FINANCE_MENUS } from '../../components/layout/navigation'
import type { NavAspect } from '../../components/layout/navigation'
import { SUMMARY_BLOCKS } from './FinanceSummaryBlocks'

const SummaryBlock = ({ aspect }: { aspect: NavAspect }) => {
  const Content = SUMMARY_BLOCKS[aspect.id]
  const titleId = `summary-block-${aspect.id}`

  return (
    <Card
      data-testid="summary-block"
      data-aspect={aspect.id}
      sx={{ flex: '1 1 320px', position: 'relative', '&:hover': aspect.path ? { boxShadow: 3 } : undefined }}
    >
      <CardContent>
        <Typography id={titleId} variant="subtitle2" sx={{ fontWeight: 600 }}>
          {aspect.path ? (
            <MuiLink
              component={Link}
              to={aspect.path}
              color="inherit"
              underline="none"
              // The whole card is the target, the link's name stays the block's title.
              sx={{ '&::after': { content: '""', position: 'absolute', inset: 0 } }}
            >
              {aspect.label}
            </MuiLink>
          ) : (
            aspect.label
          )}
        </Typography>
        {Content && <Content />}
      </CardContent>
    </Card>
  )
}

/**
 * A Finance menu's page (MAG-427): one block per aspect, the main figures of
 * each, every block opening on its detailed screen. Never a list of links.
 */
export const FinanceSummaryPage = () => {
  const { key } = useParams()
  const menu = FINANCE_MENUS.find((candidate) => candidate.key === key && candidate.aspects.length > 0)

  if (!menu) {
    return (
      <>
        <Title title="Finance" />
        <Alert
          severity="warning"
          action={
            <MuiLink component={Link} to={FINANCE_DASHBOARD_PATH} color="inherit">
              Retour au Dashboard
            </MuiLink>
          }
        >
          Ce menu n'existe pas.
        </Alert>
      </>
    )
  }

  return (
    <>
      <Title title={`Finance · ${menu.label}`} />
      <Typography variant="h6" sx={{ mb: 2 }}>
        {menu.label}
      </Typography>
      <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2 }}>
        {menu.aspects.map((aspect) => (
          <SummaryBlock key={aspect.id} aspect={aspect} />
        ))}
      </Stack>
    </>
  )
}
