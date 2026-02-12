import type { Theme } from '@mui/material/styles'
import type { SxProps } from '@mui/system'

/**
 * Google Calendar-style FullCalendar CSS overrides.
 */
export const getCalendarThemeSx = (theme: Theme): SxProps<Theme> => {
  const isDark = theme.palette.mode === 'dark'

  return {
    fontFamily: theme.typography.fontFamily,
    flex: 1,
    minWidth: 0,

    // ── FC CSS custom properties ──────────────────────────────────────
    '& .fc': {
      '--fc-border-color': theme.palette.divider,
      '--fc-event-bg-color': theme.palette.primary.main,
      '--fc-event-border-color': theme.palette.primary.main,
      '--fc-event-text-color': '#fff',
      '--fc-today-bg-color': isDark ? 'rgba(66,133,244,0.08)' : 'rgba(66,133,244,0.04)',
      '--fc-page-bg-color': 'transparent',
      '--fc-neutral-bg-color': theme.palette.action.hover,
      '--fc-now-indicator-color': '#ea4335',
    },

    // ── Remove outer scrollgrid borders ───────────────────────────────
    '& .fc .fc-scrollgrid': {
      borderWidth: 0,
    },
    '& .fc .fc-scrollgrid-section > td': {
      border: 0,
    },
    '& .fc .fc-scrollgrid-section-header > th': {
      borderRight: 0,
    },

    // ── Day column headers ────────────────────────────────────────────
    '& .fc .fc-col-header-cell': {
      borderBottom: `1px solid ${theme.palette.divider}`,
      borderRight: 0,
      borderLeft: 0,
    },
    '& .fc .fc-col-header-cell-cushion': {
      color: theme.palette.text.secondary,
      fontFamily: theme.typography.fontFamily,
      textDecoration: 'none',
      fontSize: '0.7rem',
      fontWeight: 500,
      textTransform: 'uppercase',
      letterSpacing: '0.05em',
      padding: '8px 0',
    },

    // ── Day numbers ──────────────────────────────────────────────────
    '& .fc .fc-daygrid-day-number': {
      color: theme.palette.text.secondary,
      fontFamily: theme.typography.fontFamily,
      textDecoration: 'none',
      fontSize: '0.75rem',
      padding: '4px 0',
      width: '100%',
      textAlign: 'center',
    },
    '& .fc .fc-daygrid-day-top': {
      justifyContent: 'center',
    },

    // ── Today: blue circle on the day number ─────────────────────────
    '& .fc .fc-day-today .fc-daygrid-day-number': {
      backgroundColor: '#1a73e8',
      color: '#fff',
      borderRadius: '50%',
      width: 26,
      height: 26,
      lineHeight: '26px',
      padding: 0,
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontWeight: 600,
    },

    // ── All-day events (month view) – colored rounded bars ───────────
    '& .fc .fc-daygrid-event': {
      borderRadius: '4px',
      border: 'none',
      fontSize: '0.72rem',
      lineHeight: 1.4,
      padding: '1px 6px',
    },
    '& .fc .fc-daygrid-event .fc-event-title': {
      fontWeight: 500,
    },
    '& .fc .fc-daygrid-dot-event': {
      padding: '1px 4px',
    },
    // Dot events: small colored circle + time + title
    '& .fc .fc-daygrid-dot-event .fc-daygrid-event-dot': {
      width: 8,
      height: 8,
      borderRadius: '50%',
      margin: '0 4px 0 0',
      border: 'none',
      backgroundColor: 'var(--fc-event-border-color, currentColor)',
    },
    '& .fc .fc-daygrid-dot-event .fc-event-time': {
      fontSize: '0.7rem',
      fontWeight: 500,
      color: theme.palette.text.primary,
    },
    '& .fc .fc-daygrid-dot-event .fc-event-title': {
      fontSize: '0.7rem',
      fontWeight: 400,
      color: theme.palette.text.primary,
    },

    // ── "+N autres" more-link ────────────────────────────────────────
    '& .fc .fc-daygrid-more-link': {
      fontSize: '0.7rem',
      fontWeight: 500,
      color: theme.palette.text.secondary,
      padding: '2px 6px',
      '&:hover': {
        backgroundColor: theme.palette.action.hover,
        borderRadius: '4px',
      },
    },

    // ── More-events popover ──────────────────────────────────────────
    '& .fc .fc-more-popover': {
      borderRadius: '8px',
      boxShadow: theme.shadows[8],
      border: `1px solid ${theme.palette.divider}`,
      backgroundColor: theme.palette.background.paper,
    },
    '& .fc .fc-more-popover .fc-popover-header': {
      backgroundColor: theme.palette.background.paper,
      padding: '8px 12px',
      fontSize: '0.8rem',
      fontWeight: 500,
    },

    // ── Event hover ──────────────────────────────────────────────────
    '& .fc .fc-event': {
      cursor: 'pointer',
      transition: 'filter 0.15s',
      '&:hover': {
        filter: isDark ? 'brightness(1.15)' : 'brightness(0.92)',
      },
    },

    // ── Week / Day — timegrid slots ──────────────────────────────────
    '& .fc .fc-timegrid-slot': {
      height: '48px',
    },
    '& .fc .fc-timegrid-slot-label-cushion': {
      fontSize: '0.65rem',
      color: theme.palette.text.secondary,
      fontFamily: theme.typography.fontFamily,
    },
    '& .fc .fc-timegrid-event': {
      borderRadius: '4px',
      border: 'none',
      fontSize: '0.72rem',
      boxShadow: 'none',
    },
    '& .fc .fc-timegrid-event .fc-event-main': {
      padding: '2px 4px',
    },

    // ── Now indicator (red line) ─────────────────────────────────────
    '& .fc .fc-timegrid-now-indicator-line': {
      borderColor: '#ea4335',
      borderWidth: '2px 0 0 0',
    },
    '& .fc .fc-timegrid-now-indicator-arrow': {
      borderColor: '#ea4335',
      borderWidth: '5px 0 5px 8px',
      borderTopColor: 'transparent',
      borderBottomColor: 'transparent',
    },

    // ── Day cells ────────────────────────────────────────────────────
    '& .fc .fc-daygrid-day': {
      borderColor: theme.palette.divider,
    },
    '& .fc td, & .fc th': {
      borderColor: theme.palette.divider,
    },
  }
}
