import { useEffect, useId, useRef } from 'react'
import type { KeyboardEvent } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Typography from '@mui/material/Typography'
import { alpha, keyframes } from '@mui/material/styles'
import { useNarrowScreen } from '../../hooks/useNarrowScreen'
import { MaggieAvatar } from './MaggieAvatar'
import { playChime } from './chime'

const fadeIn = keyframes`
  from { opacity: 0; }
  to { opacity: 1; }
`
const slideIn = keyframes`
  from { opacity: 0; transform: translateX(32px); }
  to { opacity: 1; transform: translateX(0); }
`

const FOCUSABLE = 'button:not([disabled]), [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'

interface MaggieInterruptionProps {
  open: boolean
  message: string
  actionLabel: string
  onAction: () => void
  onLater: () => void
}

/**
 * Maggie speaking unprompted (MAG-311): a dark veil, her face and a bubble
 * with the one action she proposes. Presentational — what triggers it and
 * what « Plus tard » means live in `useMaggieInterruption`.
 */
export const MaggieInterruption = ({ open, message, actionLabel, onAction, onLater }: MaggieInterruptionProps) => {
  const narrow = useNarrowScreen()
  const titleId = useId()
  const messageId = useId()
  const rootRef = useRef<HTMLDivElement>(null)
  const actionRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    actionRef.current?.focus()
    playChime()
    return () => previous?.focus()
  }, [open])

  if (!open) return null

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.stopPropagation()
      onLater()
      return
    }
    if (event.key !== 'Tab') return
    const focusable = Array.from(rootRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])
    if (focusable.length === 0) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    const active = document.activeElement
    if (event.shiftKey && (active === first || !rootRef.current?.contains(active))) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && (active === last || !rootRef.current?.contains(active))) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <Box
      ref={rootRef}
      role="alertdialog"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={messageId}
      onKeyDown={handleKeyDown}
      sx={(theme) => ({
        position: 'fixed',
        inset: 0,
        zIndex: theme.zIndex.modal + 1,
        display: 'flex',
        alignItems: narrow ? 'flex-end' : 'center',
        justifyContent: 'center',
        overflowY: 'auto',
        overflowX: 'hidden',
        boxSizing: 'border-box',
        padding: narrow ? '16px' : '32px',
        background: `radial-gradient(ellipse at center, ${alpha('#05030f', 0.62)} 0%, ${alpha('#05030f', 0.88)} 100%)`,
        animation: `${fadeIn} 500ms ease-out both`,
      })}
    >
      <Box
        sx={{
          display: 'flex',
          flexDirection: narrow ? 'column' : 'row',
          alignItems: narrow ? 'stretch' : 'flex-start',
          gap: narrow ? '12px' : '24px',
          width: '100%',
          maxWidth: 680,
          minWidth: 0,
          margin: 'auto',
        }}
      >
        <Box sx={{ display: 'flex', justifyContent: 'center', alignSelf: narrow ? 'center' : 'auto' }}>
          <MaggieAvatar size={narrow ? 72 : 96} animated />
        </Box>
        <Box
          data-testid="maggie-interruption-bubble"
          sx={(theme) => ({
            flex: 1,
            minWidth: 0,
            boxSizing: 'border-box',
            padding: narrow ? '20px' : '24px 28px',
            borderRadius: '30px 30px 8px 30px',
            backgroundColor: theme.palette.maggie.bubble,
            boxShadow: `inset 0 0 0 1px ${alpha(theme.palette.primary.main, 0.3)}`,
            animation: `${slideIn} 520ms cubic-bezier(0.2, 0.8, 0.3, 1) 180ms both`,
            '@media (prefers-reduced-motion: reduce)': {
              animation: `${fadeIn} 300ms ease-out both`,
            },
          })}
        >
          <Typography
            id={titleId}
            component="p"
            sx={(theme) => ({
              margin: 0,
              fontSize: 12,
              fontWeight: 700,
              letterSpacing: '0.12em',
              fontVariant: 'all-small-caps',
              color: theme.palette.mode === 'dark' ? theme.palette.maggie.avatarFrom : theme.palette.primary.main,
            })}
          >
            MAGGIE · maintenant
          </Typography>
          <Typography
            id={messageId}
            component="p"
            sx={{ margin: '8px 0 20px', fontSize: 18, lineHeight: 1.5, overflowWrap: 'anywhere', whiteSpace: 'pre-wrap' }}
          >
            {message}
          </Typography>
          <Box sx={{ display: 'flex', flexDirection: narrow ? 'column' : 'row', flexWrap: 'wrap', gap: '12px' }}>
            <Button
              ref={actionRef}
              variant="contained"
              color="primary"
              onClick={onAction}
              sx={{ borderRadius: 999, minHeight: 44, px: 3 }}
            >
              {actionLabel}
            </Button>
            <Button variant="outlined" onClick={onLater} sx={{ borderRadius: 999, minHeight: 44, px: 3 }}>
              Plus tard
            </Button>
          </Box>
        </Box>
      </Box>
    </Box>
  )
}
