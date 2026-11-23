import { useEffect, useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import LinearProgress from '@mui/material/LinearProgress'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import CheckIcon from '@mui/icons-material/Check'
import MicIcon from '@mui/icons-material/Mic'
import { ActivityShell } from '../../../components/activity/ActivityShell'
import { useActivityOrigin } from '../../../components/activity/activityOrigin'
import { MaggieAvatar } from '../../../components/maggie/MaggieAvatar'
import { localDay } from '../../../dates'
import { BILAN_STEPS, DEFAULT_SAY, STEP_HEADINGS, STEP_LABELS, stepIndex } from './bilanSession'
import type { BilanStep } from './bilanSession'
import { BilanError } from './steps/BilanCard'
import { useConsignesSlot } from './steps/ConsignesStep'
import { useJourneeSlot } from './steps/JourneeStep'
import { useSanteSlot } from './steps/SanteStep'
import type { BilanSlot } from './steps/slot'
import { useBilanActivity } from './useBilanActivity'

export const BILAN_LOAD_FAILED = 'Le bilan n’a pas pu être chargé.'

const WAVE = [10, 18, 26, 14, 22, 28, 12, 20, 8]

const DATE = new Intl.DateTimeFormat('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' })
const TIME = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })

const capitalized = (text: string): string => text.charAt(0).toUpperCase() + text.slice(1)

const useNow = (): Date => {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30_000)

    return () => window.clearInterval(timer)
  }, [])

  return now
}

const StepsColumn = ({ step, onGo }: { step: BilanStep; onGo: (step: BilanStep) => void }) => {
  const current = stepIndex(step)

  return (
    <Box component="nav" aria-label="Étapes du bilan" sx={{ width: 220, flexShrink: 0, display: 'flex', flexDirection: 'column', gap: 1 }}>
      <Typography sx={{ fontSize: 13, color: 'text.secondary', mb: 1 }}>{`Étape ${current + 1} sur ${BILAN_STEPS.length}`}</Typography>
      <Box component="ol" sx={{ m: 0, p: 0, display: 'flex', flexDirection: 'column', gap: 0.5 }}>
        {BILAN_STEPS.map((name, index) => {
          const passed = index < current
          const active = index === current

          return (
            <Box component="li" key={name} sx={{ listStyle: 'none' }}>
              <Box
                component="button"
                type="button"
                aria-current={active ? 'step' : undefined}
                onClick={() => onGo(name)}
                sx={(theme) => ({
                  appearance: 'none',
                  border: 0,
                  font: 'inherit',
                  width: '100%',
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 1.5,
                  px: 1.5,
                  py: 1.25,
                  borderRadius: 3,
                  textAlign: 'left',
                  bgcolor: active ? theme.palette.veilleuse.raised : 'transparent',
                  color: active ? theme.palette.text.primary : theme.palette.text.secondary,
                })}
              >
                <Box
                  component="span"
                  aria-hidden
                  sx={(theme) => ({
                    width: 28,
                    height: 28,
                    borderRadius: '50%',
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 13,
                    fontWeight: 600,
                    bgcolor: active || passed ? theme.palette.primary.main : theme.palette.veilleuse.raised,
                    color: active || passed ? theme.palette.primary.contrastText : theme.palette.text.secondary,
                  })}
                >
                  {passed ? <CheckIcon sx={{ fontSize: 16 }} /> : index + 1}
                </Box>
                <span>{STEP_LABELS[name]}</span>
              </Box>
            </Box>
          )
        })}
      </Box>
    </Box>
  )
}

const MaggieColumn = ({ say, asks, onReply }: { say: string; asks: NonNullable<BilanSlot['asks']>; onReply: () => void }) => (
  <Box component="aside" aria-label="Maggie" sx={{ display: 'flex', flexDirection: 'column', gap: 2.5, alignItems: 'flex-start', minWidth: 0 }}>
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
      <MaggieAvatar size={72} />
      <Box aria-hidden sx={{ display: 'flex', alignItems: 'center', gap: '3px', height: 32 }}>
        {WAVE.map((height, index) => (
          <Box key={index} sx={{ width: 3, height, borderRadius: 2, bgcolor: 'primary.main', opacity: 0.8 }} />
        ))}
      </Box>
    </Box>
    <Typography data-testid="bilan-say" sx={{ fontSize: 24, lineHeight: 1.3 }}>
      {say}
    </Typography>
    <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap' }}>
      {asks.map((ask) => (
        <Button key={ask.label} variant="outlined" onClick={ask.onClick} sx={{ minHeight: 48, borderRadius: 999, px: 3 }}>
          {ask.label}
        </Button>
      ))}
      <Button variant="text" startIcon={<MicIcon />} onClick={onReply} sx={{ minHeight: 48, borderRadius: 999, px: 2 }}>
        Répondre
      </Button>
    </Box>
  </Box>
)

const Bilan = ({ today, goBack }: { today: string; goBack: () => void }) => {
  const color = useTheme().palette.primary.main
  const { load, session, retry, moveTo, record, end } = useBilanActivity(today)
  const [chatOpen, setChatOpen] = useState(false)
  const now = useNow()
  const journee = useJourneeSlot(today, session, record)
  const sante = useSanteSlot(today, session, record)
  const consignes = useConsignesSlot(session)

  useEffect(() => {
    if (load === 'closed') goBack()
  }, [load, goBack])

  const step = session?.step
  const slot: BilanSlot | undefined = useMemo(() => {
    if (step === 'journee') return journee
    if (step === 'sante') return sante
    if (step === 'consignes') return consignes

    return undefined
  }, [step, journee, sante, consignes])

  const last = step === BILAN_STEPS[BILAN_STEPS.length - 1]
  const index = step ? stepIndex(step) : 0
  const following = step && !last ? BILAN_STEPS[index + 1] : null

  const header = (
    <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.5 }}>
      <Typography component="h1" sx={{ fontSize: 18, fontWeight: 500 }}>
        Bilan de la journée
      </Typography>
      <Typography component="span" sx={{ fontSize: 14, color: 'text.secondary' }}>
        {capitalized(DATE.format(now))}
      </Typography>
    </Box>
  )

  return (
    <ActivityShell
      color={color}
      name="l’activité"
      confirmQuit
      closeAt="start"
      maggieButton={false}
      chatOpen={chatOpen}
      onChatOpenChange={setChatOpen}
      onQuit={goBack}
      progress={header}
      actions={
        <Typography component="time" sx={{ fontSize: 18, color: 'text.secondary', fontVariantNumeric: 'tabular-nums', pr: 1 }}>
          {TIME.format(now)}
        </Typography>
      }
    >
      {load === 'loading' && <LinearProgress aria-label="Chargement du bilan" />}
      {load === 'error' && (
        <Box sx={{ p: 4 }}>
          <BilanError message={BILAN_LOAD_FAILED} onRetry={retry} />
        </Box>
      )}
      {load === 'ready' && session && step && (
        <>
          <Box sx={{ flex: 1, minHeight: 0, display: 'grid', gridTemplateColumns: '220px minmax(0, 1.35fr) minmax(0, 1fr)', gap: 5, px: { xs: 2, md: 4 }, py: 2 }}>
            <StepsColumn step={step} onGo={moveTo} />
            <Box component="section" aria-label={STEP_HEADINGS[step]} sx={{ minWidth: 0, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 2 }}>
              <Typography component="h2" sx={{ fontSize: 40, fontWeight: 500, lineHeight: 1.15 }}>
                {STEP_HEADINGS[step]}
              </Typography>
              {slot?.content}
            </Box>
            <MaggieColumn say={slot?.say ?? DEFAULT_SAY[step]} asks={slot?.asks ?? []} onReply={() => setChatOpen(true)} />
          </Box>
          <Box component="footer" sx={{ display: 'flex', alignItems: 'center', gap: 2, px: { xs: 2, md: 4 }, py: 2 }}>
            <Button variant="outlined" disabled={index === 0} onClick={() => moveTo(BILAN_STEPS[index - 1])} sx={{ minHeight: 52, borderRadius: 999, px: 3 }}>
              Précédent
            </Button>
            <Box sx={{ flex: 1 }} />
            {following && (
              <Typography component="span" sx={{ fontSize: 14, color: 'text.secondary' }}>
                {`Ensuite · ${STEP_LABELS[following]}`}
              </Typography>
            )}
            <Button variant="contained" onClick={() => (following ? moveTo(following) : end())} sx={{ minHeight: 52, borderRadius: 999, px: 4 }}>
              {last ? 'Bonne nuit' : 'Suivant'}
            </Button>
          </Box>
        </>
      )}
    </ActivityShell>
  )
}

/** « Bilan de la journée » (MAG-961), full screen: `/activites/bilan`. The status is the API's, any device resumes it. */
export const BilanActivityPage = () => {
  const goBack = useActivityOrigin('/')
  const today = useMemo(() => localDay(new Date()), [])

  return <Bilan today={today} goBack={goBack} />
}
