import { useEffect, useId, useLayoutEffect, useRef } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Modal from '@mui/material/Modal'
import Typography from '@mui/material/Typography'
import { alpha, keyframes } from '@mui/material/styles'
import { TOKENS } from '../../design/tokens'
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

/** The veil is the night of the dark theme in both modes: Maggie speaks over a dimmed screen. */
const NIGHT = TOKENS.surface.dark.background

interface MaggieInterruptionProps {
  open: boolean
  /** Which interruption is shown: a new one replacing the current rings and takes the focus again. */
  id?: string
  /** What she is notifying, when it has a headline of its own. */
  title?: string
  message: string
  actionLabel: string
  onAction: () => void
  /** A second answer next to the action — « Refuser » beside « Autoriser ». */
  secondaryLabel?: string
  onSecondary?: () => void
  /** An answer is on its way: the buttons wait for it. */
  busy?: boolean
  error?: string | null
  onLater: () => void
}

/**
 * Maggie speaking unprompted (MAG-311): a dark veil, her face and a bubble
 * with the one action she proposes. Presentational — what triggers it and
 * what « Plus tard » means live in `useMaggieInterruption`.
 *
 * A MUI `Modal` carries the focus trap and the stacking: the last modal
 * mounted — this one, over any open dialog — owns Tab.
 */
export const MaggieInterruption = ({
  open,
  id = '',
  title,
  message,
  actionLabel,
  onAction,
  secondaryLabel,
  onSecondary,
  busy = false,
  error = null,
  onLater,
}: MaggieInterruptionProps) => {
  const narrow = useNarrowScreen()
  const titleId = useId()
  const messageId = useId()
  const actionRef = useRef<HTMLButtonElement>(null)
  const laterRef = useRef<HTMLButtonElement>(null)
  const previousFocus = useRef<HTMLElement | null>(null)
  const actedOn = useRef(false)
  const chimedFor = useRef<string | null>(null)
  const asksForAnswer = secondaryLabel !== undefined

  // Taken before the modal moves the focus; given back only when it was put off.
  // Opening the chat is not that: the chat's own input wants the focus.
  useLayoutEffect(() => {
    if (!open) return
    previousFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    actedOn.current = false
    return () => {
      if (!actedOn.current) previousFocus.current?.focus()
      previousFocus.current = null
    }
  }, [open])

  // Each interruption rings once and takes the focus — the next one replacing
  // this one included, and StrictMode's second run excluded.
  useEffect(() => {
    if (!open) {
      chimedFor.current = null
      return
    }
    // A question that acts when answered is not put under a stray Enter: the focus
    // lands on « Plus tard », which only puts it off.
    ;(asksForAnswer ? laterRef : actionRef).current?.focus()
    if (chimedFor.current === id) return
    chimedFor.current = id
    playChime()
  }, [open, id, asksForAnswer])

  // On the document, not on the modal: Escape is « Plus tard » even in the instant
  // before the focus has reached the dialog, when the modal would not hear it.
  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onLater()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, onLater])

  const handleAction = () => {
    // Opening something takes the focus with it; answering a question gives it back.
    actedOn.current = !asksForAnswer
    onAction()
  }

  return (
    <Modal
      open={open}
      hideBackdrop
      disableRestoreFocus
      disableEscapeKeyDown
    >
    <Box
      role="alertdialog"
      aria-modal="true"
      aria-labelledby={titleId}
      aria-describedby={messageId}
      sx={{
        position: 'fixed',
        inset: 0,
        outline: 'none',
        display: 'flex',
        alignItems: narrow ? 'flex-end' : 'center',
        justifyContent: 'center',
        overflowY: 'auto',
        overflowX: 'hidden',
        boxSizing: 'border-box',
        padding: narrow ? '16px' : '32px',
        background: `radial-gradient(ellipse at center, ${alpha(NIGHT, 0.62)} 0%, ${alpha(NIGHT, 0.88)} 100%)`,
        animation: `${fadeIn} 500ms ease-out both`,
      }}
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
          {title && (
            <Typography
              component="p"
              sx={{ margin: '8px 0 0', fontSize: 20, fontWeight: 600, lineHeight: 1.35, overflowWrap: 'anywhere' }}
            >
              {title}
            </Typography>
          )}
          <Typography
            id={messageId}
            component="p"
            sx={{
              margin: title ? '4px 0 20px' : '8px 0 20px',
              fontSize: 18,
              lineHeight: 1.5,
              overflowWrap: 'anywhere',
              whiteSpace: 'pre-wrap',
              ...(title && { color: 'text.secondary' }),
            }}
          >
            {message}
          </Typography>
          {error && (
            <Typography role="alert" component="p" color="error" sx={{ margin: '-8px 0 12px', fontSize: 14 }}>
              {error}
            </Typography>
          )}
          <Box sx={{ display: 'flex', flexDirection: narrow ? 'column' : 'row', flexWrap: 'wrap', gap: '12px' }}>
            <Button
              ref={actionRef}
              autoFocus={!asksForAnswer}
              variant="contained"
              color="primary"
              disabled={busy}
              onClick={handleAction}
              sx={{ borderRadius: 999, minHeight: 44, px: 3 }}
            >
              {actionLabel}
            </Button>
            {asksForAnswer && (
              <Button
                variant="outlined"
                disabled={busy}
                onClick={onSecondary}
                sx={{ borderRadius: 999, minHeight: 44, px: 3 }}
              >
                {secondaryLabel}
              </Button>
            )}
            <Button
              ref={laterRef}
              autoFocus={asksForAnswer}
              variant={asksForAnswer ? 'text' : 'outlined'}
              onClick={onLater}
              sx={{ borderRadius: 999, minHeight: 44, px: 3 }}
            >
              Plus tard
            </Button>
          </Box>
        </Box>
      </Box>
    </Box>
    </Modal>
  )
}
