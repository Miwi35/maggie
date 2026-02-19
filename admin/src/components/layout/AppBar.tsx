import { AppBar as RAAppBar } from 'react-admin'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Badge from '@mui/material/Badge'
import Tooltip from '@mui/material/Tooltip'
import ChatIcon from '@mui/icons-material/Chat'
import MicIcon from '@mui/icons-material/Mic'
import StopIcon from '@mui/icons-material/Stop'
import CircularProgress from '@mui/material/CircularProgress'
import { NotificationBell } from '../notifications/NotificationBell'
import { useVoiceRecorder } from '../../hooks/useVoiceRecorder'
import { useTranscription } from '../../hooks/useTranscription'

interface CustomAppBarProps {
  chatOpen: boolean
  onChatToggle: () => void
  unreadChat: boolean
  onVoiceMessage: (text: string) => void
}

export const CustomAppBar = ({ chatOpen, onChatToggle, unreadChat, onVoiceMessage }: CustomAppBarProps) => {
  const recorder = useVoiceRecorder()
  const transcription = useTranscription()

  const handleMicClick = async () => {
    if (recorder.state === 'recording') {
      const blob = await recorder.stopRecording()
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

  const micTooltip = isTranscribing
    ? 'Transcription...'
    : recorder.state === 'recording'
      ? `Enregistrement... ${recorder.duration}s`
      : 'Parler à Maggie'

  return (
    <RAAppBar
      toolbar={
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
          <Tooltip title={micTooltip}>
            <span>
              <IconButton color="inherit" onClick={handleMicClick} disabled={isTranscribing}>
                {isTranscribing ? (
                  <CircularProgress size={24} color="inherit" />
                ) : recorder.state === 'recording' ? (
                  <StopIcon sx={{ color: '#ff5252' }} />
                ) : (
                  <MicIcon />
                )}
              </IconButton>
            </span>
          </Tooltip>
          <NotificationBell />
          <IconButton color="inherit" onClick={onChatToggle}>
            <Badge variant="dot" color="error" invisible={!unreadChat || chatOpen}>
              <ChatIcon />
            </Badge>
          </IconButton>
        </Box>
      }
    />
  )
}
