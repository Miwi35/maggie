import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { apiUrl, headers } from '../../../calendar/taskViews'
import { parisClock } from '../../../health/nutritionBalance'
import { instructionsOf } from '../bilanSession'
import type { BilanInstruction, BilanSession } from '../bilanSession'
import { BilanCard } from './BilanCard'
import type { BilanSlot } from './slot'

const TAGS: Record<BilanInstruction['kind'], string> = { reminder: 'Rappel', task: 'Tâche', instruction: 'Consigne' }

/** The reminder's time, from its event (a proaction of the « Maggie » agenda); nothing when the read fails. */
const useReminderTime = (instruction: BilanInstruction): string | null => {
  const [time, setTime] = useState<string | null>(null)
  const eventId = instruction.kind === 'reminder' ? instruction.targetId : null

  useEffect(() => {
    if (!eventId) return
    const controller = new AbortController()
    fetch(`${apiUrl}/events/${eventId}`, { headers: headers(), signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) return
        const event = (await response.json()) as { startAt?: string | null }
        if (event.startAt) setTime(parisClock(new Date(event.startAt)))
      })
      .catch(() => undefined)

    return () => controller.abort()
  }, [eventId])

  return time
}

const Instruction = ({ instruction }: { instruction: BilanInstruction }) => {
  const time = useReminderTime(instruction)

  return (
    <Box component="li" sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5, listStyle: 'none' }}>
      <Typography component="span" sx={{ fontSize: 12, fontWeight: 600, color: 'primary.main', minWidth: 72 }}>
        {TAGS[instruction.kind] ?? TAGS.instruction}
      </Typography>
      <Typography component="span" sx={{ flex: 1, minWidth: 0, fontSize: 15 }}>
        {instruction.text}
      </Typography>
      {time && (
        <Typography component="span" sx={{ fontSize: 14, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>
          {time}
        </Typography>
      )}
    </Box>
  )
}

/** The Consignes step: what Maggie noted for tomorrow, as it arrives in the status. */
export const useConsignesSlot = (session: BilanSession | null): BilanSlot => {
  const instructions = session ? instructionsOf(session) : []

  return {
    content: (
      <BilanCard label="Pour demain">
        {instructions.length === 0 ? (
          <Typography sx={{ fontSize: 14, color: 'text.secondary' }}>Aucune consigne pour l’instant.</Typography>
        ) : (
          <Box component="ul" sx={{ m: 0, p: 0, display: 'flex', flexDirection: 'column', gap: 1.25 }}>
            {instructions.map((instruction) => (
              <Instruction key={instruction.id} instruction={instruction} />
            ))}
          </Box>
        )}
      </BilanCard>
    ),
  }
}
