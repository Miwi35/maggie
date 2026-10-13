import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import TextField from '@mui/material/TextField'
import ThumbDownAltIcon from '@mui/icons-material/ThumbDownAlt'
import ThumbDownOffAltIcon from '@mui/icons-material/ThumbDownOffAlt'
import ThumbUpAltIcon from '@mui/icons-material/ThumbUpAlt'
import ThumbUpOffAltIcon from '@mui/icons-material/ThumbUpOffAlt'
import { NARROW_QUERY } from '../../breakpoints'

export type FeedbackRating = 'up' | 'down'

interface MessageFeedbackProps {
  feedback?: FeedbackRating | null
  /** `null` takes the thumb back off. A comment only ever comes with a thumb down. */
  onRate: (rating: FeedbackRating | null, comment?: string) => void
}

const thumbSx = {
  color: 'text.secondary',
  [`@media ${NARROW_QUERY}`]: { width: 44, height: 44 },
}

/**
 * The owner's thumb under one of Maggie's answers (MAG-23).
 *
 * A thumb down is recorded at once, then offers a field for a word on why — optional, so a
 * quick tap is never lost to a form nobody fills. Tapping the chosen thumb again takes it back.
 * What the thumbs say is read back later as *proposed* style directives, never applied as is.
 */
export const MessageFeedback = ({ feedback, onRate }: MessageFeedbackProps) => {
  const [asking, setAsking] = useState(false)
  const [comment, setComment] = useState('')

  const rate = (rating: FeedbackRating) => {
    if (feedback === rating) {
      setAsking(false)
      onRate(null)
      return
    }
    onRate(rating)
    setAsking(rating === 'down')
  }

  const sendComment = () => {
    const text = comment.trim()
    if (text) onRate('down', text)
    setAsking(false)
    setComment('')
  }

  return (
    <Box data-testid="message-feedback" sx={{ alignSelf: 'flex-start', display: 'flex', flexDirection: 'column', gap: 0.5, pl: 0.5 }}>
      <Box sx={{ display: 'flex', gap: 0.25 }}>
        <IconButton
          size="small"
          aria-label="Réponse utile"
          title="Réponse utile"
          aria-pressed={feedback === 'up'}
          onClick={() => rate('up')}
          sx={thumbSx}
        >
          {feedback === 'up' ? <ThumbUpAltIcon sx={{ fontSize: 16 }} /> : <ThumbUpOffAltIcon sx={{ fontSize: 16 }} />}
        </IconButton>
        <IconButton
          size="small"
          aria-label="Réponse à revoir"
          title="Réponse à revoir"
          aria-pressed={feedback === 'down'}
          onClick={() => rate('down')}
          sx={thumbSx}
        >
          {feedback === 'down' ? <ThumbDownAltIcon sx={{ fontSize: 16 }} /> : <ThumbDownOffAltIcon sx={{ fontSize: 16 }} />}
        </IconButton>
      </Box>
      {asking && (
        <Box
          component="form"
          onSubmit={(e) => {
            e.preventDefault()
            sendComment()
          }}
          sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}
        >
          <TextField
            size="small"
            autoFocus
            placeholder="Qu'est-ce qui n'allait pas ? (facultatif)"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
            slotProps={{ htmlInput: { maxLength: 1000, 'aria-label': 'Commentaire sur la réponse' } }}
            sx={{ minWidth: 220 }}
          />
          <Button size="small" type="submit" disabled={!comment.trim()}>
            Envoyer
          </Button>
          <Button size="small" color="inherit" onClick={() => setAsking(false)}>
            Passer
          </Button>
        </Box>
      )}
    </Box>
  )
}
