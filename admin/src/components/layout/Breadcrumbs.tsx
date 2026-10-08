import { useMemo } from 'react'
import { useGetOne } from 'react-admin'
import { Link as RouterLink, useLocation } from 'react-router-dom'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import MuiBreadcrumbs from '@mui/material/Breadcrumbs'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import { useNarrowScreen } from '../../hooks/useNarrowScreen'
import { recordName, resolveTrail } from './navigation'
import type { SxProps, Theme } from '@mui/material/styles'

const legend: SxProps<Theme> = {
  fontSize: '0.8125rem',
  fontVariant: 'all-small-caps',
  letterSpacing: '0.08em',
  color: 'text.secondary',
  lineHeight: 1.6,
}

const ellipsis = { minWidth: 0, maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } as const

const truncated: SxProps<Theme> = { ...legend, ...ellipsis }

const NameSkeleton = () => (
  <Skeleton data-testid="breadcrumb-loading" variant="text" width={96} sx={{ display: 'inline-block' }} />
)

/**
 * Where the page sits, and the way back up (MAG-352).
 *
 * Built from `navigation.ts` plus the name of the record being looked at —
 * pages do not write their own. The record is read with the same `getOne` the
 * page issues, so react-query serves it from the page's cache.
 */
export const Breadcrumbs = () => {
  const { pathname } = useLocation()
  const isNarrow = useNarrowScreen()
  const trail = useMemo(() => resolveTrail(pathname), [pathname])
  const record = trail.find((crumb) => crumb.record)?.record

  const { data, isLoading } = useGetOne(
    record?.resource ?? '',
    { id: record?.id ?? '' },
    { enabled: record !== undefined },
  )

  if (trail.length === 0) {
    return null
  }

  const crumbs = trail.map((crumb) => ({
    to: crumb.to,
    label: crumb.record ? (isLoading ? undefined : recordName(data)) : crumb.label,
  }))
  const last = crumbs.length - 1

  const text = (label: string | undefined, current: boolean) =>
    label === undefined ? (
      <NameSkeleton />
    ) : (
      <Typography
        component="span"
        noWrap
        aria-current={current ? 'page' : undefined}
        sx={{ ...truncated, ...(current && { color: 'text.primary' }) }}
      >
        {label}
      </Typography>
    )

  if (isNarrow) {
    const parent = crumbs[last - 1]

    return (
      <nav aria-label="Fil d'Ariane" style={{ minWidth: 0, padding: '8px 16px 0' }}>
        {parent ? (
          <Link
            component={RouterLink}
            to={parent.to}
            underline="hover"
            color="inherit"
            sx={{ display: 'inline-flex', alignItems: 'center', maxWidth: '100%', minHeight: 32, ...legend }}
          >
            <ChevronLeftIcon fontSize="small" aria-hidden />
            {parent.label === undefined ? <NameSkeleton /> : <Box component="span" sx={ellipsis}>{parent.label}</Box>}
          </Link>
        ) : (
          text(crumbs[last].label, true)
        )}
      </nav>
    )
  }

  return (
    <nav aria-label="Fil d'Ariane" style={{ minWidth: 0, padding: '8px 16px 0' }}>
      <MuiBreadcrumbs separator="›" sx={{ ...legend, '& .MuiBreadcrumbs-ol': { flexWrap: 'nowrap' }, '& .MuiBreadcrumbs-li': { minWidth: 0 } }}>
        {crumbs.map((crumb, index) =>
          index === last ? (
            <span key={index}>{text(crumb.label, true)}</span>
          ) : (
            <Link
              key={index}
              component={RouterLink}
              to={crumb.to}
              underline="hover"
              color="inherit"
              sx={truncated}
            >
              {crumb.label ?? <NameSkeleton />}
            </Link>
          ),
        )}
      </MuiBreadcrumbs>
    </nav>
  )
}
