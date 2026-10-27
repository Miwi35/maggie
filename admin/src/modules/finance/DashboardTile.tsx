import type { ReactNode } from 'react'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router-dom'

interface DashboardTileProps {
  title: string
  /** The menu the tile opens. */
  to: string
  testId: string
  children: ReactNode
}

/**
 * One tile of the finance home: a card that opens its menu.
 *
 * The whole card is the click target, through a stretched link (the title's
 * `::after` covers the card) rather than a link wrapping the content, so the
 * content may hold its own link — the salary configuration — without nesting
 * anchors. Such a link sits above the overlay with `position: relative` and
 * a z-index (see `tileInnerLinkSx`).
 */
export const DashboardTile = ({ title, to, testId, children }: DashboardTileProps) => (
  <Card
    data-testid={testId}
    sx={{
      flex: '1 1 320px',
      position: 'relative',
      transition: 'box-shadow 120ms',
      '&:hover': { boxShadow: 3 },
    }}
  >
    <CardContent>
      <Stack direction="row" justifyContent="space-between" alignItems="baseline" sx={{ mb: 1 }}>
        <Typography variant="subtitle1" component="h2" sx={{ fontWeight: 600 }}>
          <Link
            component={RouterLink}
            to={to}
            color="inherit"
            underline="none"
            sx={{
              '&::after': { content: '""', position: 'absolute', inset: 0 },
              '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' },
            }}
          >
            {title}
          </Link>
        </Typography>
        <Typography variant="body2" color="primary" aria-hidden="true">
          Ouvrir →
        </Typography>
      </Stack>
      {children}
    </CardContent>
  </Card>
)

/** For a link inside a tile: it must stay above the tile's stretched link. */
export const tileInnerLinkSx = { position: 'relative', zIndex: 1 } as const
