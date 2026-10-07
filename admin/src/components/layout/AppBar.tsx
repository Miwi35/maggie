import { AppBar as RAAppBar } from 'react-admin'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Badge from '@mui/material/Badge'
import Tooltip from '@mui/material/Tooltip'
import ChatIcon from '@mui/icons-material/Chat'
import PsychologyIcon from '@mui/icons-material/Psychology'
import MicIcon from '@mui/icons-material/Mic'
import StopIcon from '@mui/icons-material/Stop'
import CircularProgress from '@mui/material/CircularProgress'
import { TOKENS } from '../../design/tokens'
import { NotificationBell } from '../notifications/NotificationBell'
import { SearchBar } from '../../modules/search/SearchBar'
import { useVoiceRecorder } from '../../hooks/useVoiceRecorder'
import { useTranscription } from '../../hooks/useTranscription'
import { useNarrowScreen } from '../../hooks/useNarrowScreen'
import { useChatContext } from './ChatContext'

export const CustomAppBar = () => {
  const {
    chatOpen,
    sidebarTab,
    onChatToggle,
    onMindToggle,
    unreadChat,
    onVoiceMessage,
  } = useChatContext()
  const recorder = useVoiceRecorder()
  const transcription = useTranscription()
  const isNarrow = useNarrowScreen()

  const handleMicClick = async () => {
    if (recorder.state === 'recording') {
      const blob = await recorder.stopRecording()
      // Straight to Maggie: no cleanup, she reads through a hesitation (MAG-222).
      const text = await transcription.transcribe(blob)
      if (text) {
        onVoiceMessage(text)
      }
      recorder.resetState()
    } else {
      await recorder.startRecording()
    }
  }

  const isTranscribing = recorder.state === 'processing' || transcription.loading

  // The refusal rides on the tooltip: the bar has no error line and no room for one,
  // so « Je n'ai rien entendu » would be unreachable from here otherwise (MAG-222).
  // The chat widget, which has the room, shows it on its own line — that is the full
  // treatment, this is the message at least arriving. The button keeps a name of its
  // own below, so a refusal does not rename the control until the next dictation.
  const micTooltip = isTranscribing
    ? 'Transcription...'
    : recorder.state === 'recording'
      ? `Enregistrement... ${recorder.duration}s`
      : (transcription.error ?? 'Parler à Maggie')

  return (
    <RAAppBar
      // The title is what gives way when the bar runs out of room. Flex gives
      // a text item `min-width: auto` — its full unbroken width — so without
      // this it refuses to shrink and the controls spill under the avatar,
      // ellipsis or no ellipsis. The controls keep `min-width: auto` for the
      // same reason, the other way round: they are six fixed 44px targets and
      // there is nothing in them to give.
      sx={{ '& .RaAppBar-title': { minWidth: 0 } }}
      toolbar={
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, flex: 1 }}>
          <SearchBar />
          <Tooltip title={micTooltip}>
            <span>
              <IconButton
                color="inherit"
                aria-label="Parler à Maggie"
                onClick={handleMicClick}
                disabled={isTranscribing}
              >
                {isTranscribing ? (
                  <CircularProgress size={24} color="inherit" />
                ) : recorder.state === 'recording' ? (
                  <StopIcon sx={{ color: TOKENS.signal.danger }} />
                ) : (
                  <MicIcon />
                )}
              </IconButton>
            </span>
          </Tooltip>
          <NotificationBell />
          {/* One shortcut too many for a phone's app bar: below `md` the Mind
              tab inside the chat sheet is the way in, and dropping the button
              is what leaves room for the rest (MAG-38). */}
          {!isNarrow && (
            <Tooltip title="Maggie's Mind">
              <IconButton color="inherit" onClick={onMindToggle}>
                <PsychologyIcon
                  sx={{ color: chatOpen && sidebarTab === 'mind' ? 'primary.main' : 'inherit' }}
                />
              </IconButton>
            </Tooltip>
          )}
          {/* The only icon button in the bar with no name of its own — a
              screen reader announced it as "button", and a journey had no way
              to address it. Its neighbour gets one from its tooltip. */}
          <IconButton color="inherit" onClick={onChatToggle} aria-label="Chat avec Maggie">
            <Badge variant="dot" color="error" invisible={!unreadChat || (chatOpen && sidebarTab === 'chat')}>
              <ChatIcon />
            </Badge>
          </IconButton>
        </Box>
      }
    />
  )
}
