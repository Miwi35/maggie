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
import { homeTitle, useHomeDay } from '../../modules/home/homeDay'
import { useAgentName } from '../../hooks/useAgentName'
import { AGENT_SETTINGS_PATH, EDIT_LABEL, NEW_LABEL, recordName, resolveTrail } from './navigation'
import type { SxProps, Theme } from '@mui/material/styles'

// « Finance › Revue » at 13px in normal case (`charte/Fil-Ariane`, MAG-620).
const legend: SxProps<Theme> = {
  fontSize: '0.8125rem',
  color: 'text.secondary',
  lineHeight: 1.6,
}

const ellipsis = { display: 'block', minWidth: 0, maxWidth: '100%', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' } as const

const truncated: SxProps<Theme> = { ...legend, ...ellipsis }

const visuallyHidden = {
  position: 'absolute',
  width: 1,
  height: 1,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
} as const

const NameSkeleton = () => (
  <>
    <Skeleton data-testid="breadcrumb-loading" variant="text" width={96} sx={{ display: 'inline-block' }} />
    <Box component="span" sx={visuallyHidden}>Chargement</Box>
  </>
)

interface ResolvedCrumb {
  to: string
  /** Undefined while the record that names it is still loading. */
  label: string | undefined
}

/** The trail of the current page, the record's crumb named once it has loaded. */
const useCrumbs = (): ResolvedCrumb[] => {
  const { pathname } = useLocation()
  const agentName = useAgentName()
  const trail = useMemo(() => resolveTrail(pathname), [pathname])
  const record = trail.find((crumb) => crumb.record)?.record

  const { data, isLoading } = useGetOne(
    record?.resource ?? '',
    { id: record?.id ?? '' },
    { enabled: record !== undefined },
  )

  return trail.map((crumb) => ({
    to: crumb.to,
    label: crumb.record
      ? isLoading
        ? undefined
        : recordName(data)
      : crumb.to === AGENT_SETTINGS_PATH
        ? agentName
        : crumb.label,
  }))
}

/**
 * The h1 of the page (MAG-620): the last crumb, or — on a record's edit page —
 * the record's name; « Nouveau · Événements » on a creation.
 */
const headingOf = (crumbs: ResolvedCrumb[]): string | undefined => {
  const last = crumbs[crumbs.length - 1]
  const before = crumbs[crumbs.length - 2]

  if (last?.label === EDIT_LABEL && before) return before.label
  if (last?.label === NEW_LABEL && before) return `${NEW_LABEL} · ${before.label}`

  return last?.label
}

/** The recipe sheet is titled by its own h1, which sits beside the sheet's actions in its reading column. */
const OWN_HEADING = /^\/recipes\/(create|[^/]+\/show)$/

/**
 * The page's title, above its content. Every page of the trail table has
 * exactly one, so pages do not draw a heading of their own for it.
 */
export const PageHeading = () => {
  const { pathname } = useLocation()
  const crumbs = useCrumbs()
  const [day] = useHomeDay()

  if (crumbs.length === 0 || OWN_HEADING.test(pathname)) {
    return null
  }

  // The Accueil is dated, with the day its selector shows (`accueil/Accueil-Admin`).
  const title = pathname === '/' ? homeTitle(day) : headingOf(crumbs)

  return (
    <Typography
      component="h1"
      sx={{ fontSize: { xs: 24, md: 28 }, fontWeight: 600, lineHeight: 1.25, px: 2, pt: 1, pb: 1.5, overflowWrap: 'anywhere' }}
    >
      {title ?? <NameSkeleton />}
    </Typography>
  )
}

/**
 * Where the page sits, and the way back up (MAG-352).
 *
 * Built from `navigation.ts` plus the name of the record being looked at —
 * pages do not write their own. The record is read with the same `getOne` the
 * page issues, so react-query serves it from the page's cache.
 */
export const Breadcrumbs = () => {
  const isNarrow = useNarrowScreen()
  const crumbs = useCrumbs()

  if (crumbs.length === 0) {
    return null
  }

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
      <MuiBreadcrumbs separator="›" sx={{ ...legend, '& .MuiBreadcrumbs-ol': { flexWrap: 'nowrap' }, '& .MuiBreadcrumbs-li': { minWidth: 0, overflow: 'hidden' } }}>
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
