import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogContentText from '@mui/material/DialogContentText'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import { ThemeProvider, createTheme } from '@mui/material/styles'
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome'
import CloseIcon from '@mui/icons-material/Close'
import { ChatWidget } from '../chat/ChatWidget'
import type { SidebarTab } from '../layout/ChatContext'
import type { AgentState, ContextState, ToolCallState } from '../mind/types'
import { TOKENS } from '../../design/tokens'
import { veilleuseDarkTheme } from '../../theme'
import { activityHalo } from './activityHalo'
import { keyBelongsElsewhere } from './keyBelongsElsewhere'
import { useActivityFullscreen } from './useActivityFullscreen'
import { useWakeLock } from './useWakeLock'

/** The buttons the shell draws are as big as the ones of the activities it frames. */
const TOUCH_TARGET = 64

interface ActivityShellProps {
  /** The activity's colour (Cuisine `#8FB8FF`): the accent of everything inside. */
  color: string
  /** Names the activity for the screen reader: « Quitter <name> » is the ✕. */
  name: string
  /** Asks « Quitter l'activité ? » before leaving (e.g. while a timer runs). */
  confirmQuit?: boolean
  /** What the confirmation says is lost, when it asks. */
  confirmDetail?: string
  /** Called once the owner leaves, after the confirmation if there is one: go back where the activity was launched from. */
  onQuit: () => void
  /** The activity's progress, drawn at the left of the top bar. */
  progress?: ReactNode
  children: ReactNode
}

/**
 * The frame every activity runs in (MAG-580): full screen on the screen the window is on, or the
 * whole window when the browser refuses; the screen kept on; a ✕ and Échap to leave, asking
 * « Quitter l'activité ? » first when the activity says there is something to lose; Maggie one
 * tap away. The activity itself is its children. The background is the night with the activity's
 * [color] as a radial halo (MAG-617): no activity draws its own.
 */
export const ActivityShell = ({ color, name, confirmQuit = false, confirmDetail, onQuit, progress, children }: ActivityShellProps) => {
  const [frame, setFrame] = useState<HTMLElement | null>(null)
  const frameRef = useRef<HTMLElement | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [chatOpen, setChatOpen] = useState(false)
  const [sidebarTab, setSidebarTab] = useState<SidebarTab>('chat')
  const [contexts, setContexts] = useState<ContextState[]>([])
  const [toolCalls, setToolCalls] = useState<ToolCallState[]>([])
  const [agentState, setAgentState] = useState<AgentState>('idle')

  const setFrameElement = useCallback((element: HTMLElement | null) => {
    frameRef.current = element
    setFrame(element)
  }, [])

  // Dialogs, menus and the chat open inside the frame: only what is inside the full-screen element is drawn.
  const theme = useMemo(() => {
    const inFrame = () => frame ?? document.body

    return createTheme(veilleuseDarkTheme, {
      palette: { primary: { main: color, contrastText: TOKENS.brand.onPrimary } },
      components: {
        MuiModal: { defaultProps: { container: inFrame } },
        MuiPopper: { defaultProps: { container: inFrame } },
        MuiPortal: { defaultProps: { container: inFrame } },
      },
    })
  }, [color, frame])

  useWakeLock()

  const quitRef = useRef(onQuit)
  useEffect(() => {
    quitRef.current = onQuit
  })

  const asked = useCallback(() => {
    if (confirmQuit) setConfirming(true)
    else quitRef.current()
  }, [confirmQuit])

  const { mode, leave } = useActivityFullscreen(frameRef, asked)

  const quit = useCallback(async () => {
    setConfirming(false)
    await leave()
    quitRef.current()
  }, [leave])

  const requestQuit = useCallback(() => {
    if (confirmQuit) setConfirming(true)
    else void quit()
  }, [confirmQuit, quit])

  // In full screen the browser keeps Échap for itself (the fullscreenchange above carries it);
  // in a window it is the activity's.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || document.fullscreenElement) return
      if (keyBelongsElsewhere(event)) return
      event.preventDefault()
      requestQuit()
    }
    window.addEventListener('keydown', onKey)

    return () => window.removeEventListener('keydown', onKey)
  }, [requestQuit])

  return (
    <ThemeProvider theme={theme}>
      <Box
        ref={setFrameElement}
        data-testid="activity-shell"
        data-mode={mode}
        sx={{
          position: 'fixed',
          inset: 0,
          width: '100vw',
          height: '100dvh',
          display: 'flex',
          flexDirection: 'column',
          bgcolor: 'background.default',
          backgroundImage: activityHalo(color),
          color: 'text.primary',
          overflow: 'hidden',
        }}
      >
        <Box component="header" sx={{ display: 'flex', alignItems: 'center', gap: 1, px: { xs: 2, md: 4 }, pt: 2, pb: 1 }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>{progress}</Box>
          <IconButton
            aria-label="Maggie"
            onClick={() => setChatOpen((open) => !open)}
            color={chatOpen ? 'primary' : 'default'}
            sx={{ width: TOUCH_TARGET, height: TOUCH_TARGET }}
          >
            <AutoAwesomeIcon />
          </IconButton>
          <IconButton aria-label={`Quitter ${name}`} onClick={requestQuit} sx={{ width: TOUCH_TARGET, height: TOUCH_TARGET }}>
            <CloseIcon />
          </IconButton>
        </Box>

        <Box component="main" sx={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          {children}
        </Box>

        <ChatWidget
          open={chatOpen}
          sidebarTab={sidebarTab}
          onTabChange={(_, tab) => setSidebarTab(tab)}
          onClose={() => setChatOpen(false)}
          onUnread={() => {}}
          agentState={agentState}
          onAgentStateChange={setAgentState}
          contexts={contexts}
          onContextsChange={setContexts}
          toolCalls={toolCalls}
          onToolCallsChange={setToolCalls}
        />

        <Dialog open={confirming} onClose={() => setConfirming(false)} aria-labelledby="activity-quit-title">
          <DialogTitle id="activity-quit-title">Quitter l'activité ?</DialogTitle>
          {confirmDetail && (
            <DialogContent>
              <DialogContentText>{confirmDetail}</DialogContentText>
            </DialogContent>
          )}
          <DialogActions sx={{ p: 2, gap: 1 }}>
            <Button autoFocus onClick={() => setConfirming(false)} sx={{ minHeight: TOUCH_TARGET, px: 3 }}>
              Rester
            </Button>
            <Button variant="contained" onClick={() => void quit()} sx={{ minHeight: TOUCH_TARGET, px: 3 }}>
              Quitter
            </Button>
          </DialogActions>
        </Dialog>
      </Box>
    </ThemeProvider>
  )
}
