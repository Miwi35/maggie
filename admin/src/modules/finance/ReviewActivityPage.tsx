import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { ThemeProvider, createTheme } from '@mui/material/styles'
import CloseIcon from '@mui/icons-material/Close'
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome'
import { useLocation, useNavigate } from 'react-router-dom'
import { ChatWidget } from '../../components/chat/ChatWidget'
import type { SidebarTab } from '../../components/layout/ChatContext'
import type { AgentState, ContextState, ToolCallState } from '../../components/mind/types'
import { TOKENS } from '../../design/tokens'
import { veilleuseDarkTheme } from '../../theme'
import { EnvelopeAdjustments } from './EnvelopeAdjustments'
import { MonthBalanceTab } from './MonthBalanceTab'
import { NextProjectCard } from './NextProjectCard'
import { ReviewTransactionCard } from './ReviewTransactionCard'
import { REVIEW_FALLBACK_PATH, currentMonthKey } from './reviewActivity'
import type { ReviewLaunchState } from './reviewActivity'
import { UpcomingRemaining } from './UpcomingRemaining'
import { UpcomingWindow } from './UpcomingWindow'
import { monthLabel, windowRange } from './upcomingLabels'
import { useEnvelopeAdjustments } from './useEnvelopeAdjustments'
import { REVIEW_STEPS } from './useReviewProgress'
import type { ReviewStep } from './useReviewProgress'
import { useReviewProgress } from './useReviewProgress'
import { useReviewQueue } from './useReviewQueue'
import type { ReviewItem } from './useReviewQueue'
import type { UpcomingProjection } from './useUpcomingProjection'
import { useUpcomingProjection } from './useUpcomingProjection'

type ActiveStep = Exclude<ReviewStep, 'done'>

/** The five segments of the bar, in order (`done` is no step of its own). */
const STEPS = REVIEW_STEPS.filter((step): step is ActiveStep => step !== 'done')

const STEP_LABELS: Record<ActiveStep, string> = {
  review: 'Revue',
  balance: 'Bilan',
  envelopes: 'Enveloppes',
  upcoming: 'À venir',
  projects: 'Projets',
}

const STEP_TITLES: Record<ActiveStep, string> = {
  review: 'Revue des transactions',
  balance: 'Bilan du mois',
  envelopes: 'Ajuster les enveloppes',
  upcoming: 'À venir',
  projects: 'Projets',
}

/** Veilleuse, whatever the admin's mode: the dark night of the activities, the accounts' mint for accent. */
const activityTheme = createTheme(veilleuseDarkTheme, {
  palette: {
    primary: { main: TOKENS.module.comptes.dark, contrastText: TOKENS.brand.onPrimary },
  },
})

const upcomingIsEmpty = (projection: UpcomingProjection) =>
  [projection.week, projection.month].every((window) => window.incomes.length === 0 && window.expenses.length === 0)

/** True when the key was meant for a field, a list or a dialog, not for the activity. */
const keyBelongsElsewhere = (event: KeyboardEvent): boolean => {
  const target = event.target as HTMLElement | null
  if (!target || typeof target.closest !== 'function') return false
  if (target.closest('[role="dialog"], [role="listbox"], [role="menu"], [data-testid="chat-panel"]')) return true
  if (target.isContentEditable) return true
  const tag = target.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  // Enter on a button or a link already presses it.
  return event.key === 'Enter' && (tag === 'BUTTON' || tag === 'A' || target.getAttribute('role') === 'button')
}

/** The upcoming and the projects steps read the same projection. */
const UpcomingStep = ({ projection, error, retry }: { projection: UpcomingProjection | null; error: boolean; retry: () => void }) => (
  <Stack spacing={2} data-testid="review-upcoming">
    {error && (
      <Alert severity="error" action={<Button color="inherit" size="small" onClick={retry}>Réessayer</Button>}>
        Les prévisions n'ont pas pu être chargées.
      </Alert>
    )}
    {projection && (
      <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'stretch' }}>
        <UpcomingWindow
          testId="upcoming-week"
          title="La semaine suivante"
          subtitle={windowRange(projection.week)}
          window={projection.week}
        />
        <UpcomingWindow
          testId="upcoming-month"
          title="Le mois suivant"
          subtitle={monthLabel(projection.month.month)}
          window={projection.month}
        />
      </Stack>
    )}
  </Stack>
)

const ProjectsStep = ({ projection }: { projection: UpcomingProjection | null }) => (
  <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 2, alignItems: 'flex-start' }} data-testid="review-projects">
    {projection && <UpcomingRemaining month={projection.month} />}
    <Box sx={{ flex: '1 1 320px' }}>
      <NextProjectCard />
    </Box>
  </Stack>
)

/**
 * Comptes › la revue du mois, as an activity (MAG-456): full screen, out of the menus,
 * five steps — Revue, Bilan, Enveloppes, À venir, Projets — walked with the keyboard
 * (← Précédent, → or Entrée Suivant / Valider, Échap quitter) or the footer.
 *
 * Where the owner stands is the API's (`/api/finance/review/progress`): every line left
 * and every step reached is written at once, so a review quit at the third line
 * resumes at the fourth, here or on the phone. A step with nothing to show passes by
 * itself.
 */
export const ReviewActivityPage = () => {
  const navigate = useNavigate()
  const location = useLocation()
  const month = useMemo(() => currentMonthKey(), [])
  const from = (location.state as ReviewLaunchState | null)?.from

  const progress = useReviewProgress(month)
  const review = useReviewQueue(month)
  const envelopes = useEnvelopeAdjustments(month)
  const upcoming = useUpcomingProjection()

  const [step, setStep] = useState<ReviewStep | null>(null)
  const [cursor, setCursor] = useState(0)
  const [moving, setMoving] = useState(false)

  // The chat, on the side as in the layout this page leaves out.
  const [chatOpen, setChatOpen] = useState(false)
  const [sidebarTab, setSidebarTab] = useState<SidebarTab>('chat')
  const [contexts, setContexts] = useState<ContextState[]>([])
  const [toolCalls, setToolCalls] = useState<ToolCallState[]>([])
  const [agentState, setAgentState] = useState<AgentState>('idle')

  const items = useMemo(() => review.queue?.items ?? [], [review.queue])
  const handled = useMemo(() => new Set(progress.progress?.handledTransactionIds ?? []), [progress.progress])

  // A queue that could not be read is no empty review: nothing resumes (nor is written) until it is.
  const ready =
    progress.progress !== null &&
    review.queue !== null &&
    review.error === null &&
    !review.loading &&
    !envelopes.loading &&
    // The projection keeps loading on a Mercure refresh: only its first read is waited for.
    (upcoming.projection !== null || upcoming.error)

  /** Whether a step has anything to show; a read that failed shows its error rather than passing. */
  const hasContent = useCallback(
    (candidate: ActiveStep): boolean => {
      switch (candidate) {
        case 'review':
          return items.some((item) => !handled.has(item.id))
        case 'envelopes':
          return envelopes.loadError || (envelopes.data?.envelopes.length ?? 0) > 0
        case 'upcoming':
          return upcoming.error || (upcoming.projection !== null && !upcomingIsEmpty(upcoming.projection))
        default:
          // The balance always has its figures; the projects have no list of their own yet:
          // the step shows what is left toward them.
          return true
      }
    },
    [items, handled, envelopes.loadError, envelopes.data, upcoming.error, upcoming.projection],
  )

  const firstUnhandled = useCallback(
    (after: number) => items.findIndex((item, index) => index > after && !handled.has(item.id)),
    [items, handled],
  )

  /** The first step from `start` on that has something to show, or `done`. */
  const forwardFrom = useCallback(
    (start: ActiveStep): ReviewStep => STEPS.slice(STEPS.indexOf(start)).find(hasContent) ?? 'done',
    [hasContent],
  )

  const enter = useCallback(
    (target: ReviewStep, at = 0) => {
      setStep(target)
      setCursor(at)
      if (target !== progress.progress?.step) {
        void progress.save({ step: target })
      }
    },
    [progress],
  )

  // Resume, once everything that decides where is read.
  const resumed = useRef(false)
  useEffect(() => {
    if (resumed.current || !ready || !progress.progress) return
    resumed.current = true
    const stored = progress.progress.step
    if (stored === 'done') {
      setStep('done')

      return
    }
    const target = forwardFrom(stored)
    enter(target, target === 'review' ? firstUnhandled(-1) : 0)
  }, [ready, progress.progress, forwardFrom, firstUnhandled, enter])

  const quit = useCallback(() => {
    navigate(from && from !== location.pathname ? from : REVIEW_FALLBACK_PATH)
  }, [navigate, from, location.pathname])

  const current: ReviewItem | undefined = step === 'review' ? items[cursor] : undefined

  /** Leaves the current line: written as handled, then the next one not handled yet, or the next step. */
  const leaveLine = useCallback(
    async (item: ReviewItem) => {
      setMoving(true)
      let stored = false
      try {
        stored = await progress.save({ handledTransactionIds: [item.id] })
      } finally {
        setMoving(false)
      }
      // Not stored, not left: a relaunch would come back to it, so the line stays and the error says why.
      if (!stored) return
      const next = items.findIndex((candidate, index) => index > cursor && !handled.has(candidate.id) && candidate.id !== item.id)
      if (next >= 0) {
        setCursor(next)
      } else {
        enter(forwardFrom('balance'))
      }
    },
    [progress, items, cursor, handled, enter, forwardFrom],
  )

  const next = useCallback(async () => {
    if (step === null || step === 'done' || moving || review.busy) return
    if (step === 'review') {
      if (!current) {
        enter(forwardFrom('balance'))

        return
      }
      // « Valider »: a recognised line is checked, a suggestion accepted, anything else passed.
      const ok = current.recognised
        ? await review.control(current)
        : current.suggestion && !current.categoryId
          ? await review.accept(current)
          : true
      if (ok) await leaveLine(current)

      return
    }
    const following = STEPS[STEPS.indexOf(step) + 1]
    enter(following ? forwardFrom(following) : 'done')
  }, [step, moving, review, current, enter, forwardFrom, leaveLine])

  const previousTarget = useMemo((): { step: ActiveStep; at: number } | null => {
    if (step === null || step === 'done') return null
    if (step === 'review') return cursor > 0 ? { step: 'review', at: cursor - 1 } : null
    for (const candidate of STEPS.slice(0, STEPS.indexOf(step)).reverse()) {
      if (candidate === 'review') {
        if (items.length > 0) return { step: 'review', at: items.length - 1 }
      } else if (hasContent(candidate)) {
        return { step: candidate, at: 0 }
      }
    }

    return null
  }, [step, cursor, items.length, hasContent])

  const previous = useCallback(() => {
    if (!previousTarget || moving || review.busy) return
    if (previousTarget.step === step) {
      setCursor(previousTarget.at)
    } else {
      enter(previousTarget.step, previousTarget.at)
    }
  }, [previousTarget, moving, review.busy, step, enter])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
      if (event.key === 'Escape') {
        if (keyBelongsElsewhere(event)) return
        event.preventDefault()
        quit()

        return
      }
      if (keyBelongsElsewhere(event)) return
      if (event.key === 'ArrowLeft') {
        event.preventDefault()
        previous()
      } else if (event.key === 'ArrowRight' || event.key === 'Enter') {
        event.preventDefault()
        void next()
      }
    }
    window.addEventListener('keydown', onKey)

    return () => window.removeEventListener('keydown', onKey)
  }, [quit, previous, next])

  const restart = () => enter(forwardFrom('balance'))

  const activeIndex = step === null ? -1 : step === 'done' ? STEPS.length : STEPS.indexOf(step)
  const following = step && step !== 'done' ? STEPS.slice(STEPS.indexOf(step) + 1).find(hasContent) : undefined
  const loadError = progress.error ?? review.error
  const busy = moving || review.busy

  const primaryLabel =
    step === 'review' && current
      ? current.recognised || (current.suggestion && !current.categoryId)
        ? 'Valider'
        : 'Passer'
      : step === 'projects'
        ? 'Terminer'
        : 'Suivant'

  const body = () => {
    if (loadError) {
      return (
        <Alert
          severity="error"
          action={
            <Button
              color="inherit"
              onClick={() => {
                progress.retry()
                review.retry()
              }}
            >
              Réessayer
            </Button>
          }
        >
          {loadError}
        </Alert>
      )
    }
    if (step === null) return <LinearProgress aria-label="Chargement" />
    switch (step) {
      case 'done':
        return (
          <Stack spacing={2} alignItems="flex-start" data-testid="review-done">
            <Typography variant="h4" component="h2">
              Revue terminée
            </Typography>
            <Typography color="text.secondary">La revue de ce mois est faite. Vous pouvez la reprendre depuis le Bilan.</Typography>
            <Stack direction="row" spacing={1}>
              <Button variant="contained" onClick={restart}>
                Reprendre depuis le Bilan
              </Button>
              <Button onClick={quit}>Quitter</Button>
            </Stack>
          </Stack>
        )
      case 'review':
        return current ? (
          <ReviewTransactionCard
            key={current.id}
            item={current}
            position={cursor + 1}
            total={items.length}
            busy={busy}
            onAccept={() => {
              void review.accept(current).then(async (ok) => {
                if (ok) await leaveLine(current)
              })
            }}
            onCategory={async (category, path, qualification) => {
              const ok = current.recognised
                ? await review.correct(current, category, qualification)
                : await review.sort(current, category, path, qualification)
              if (ok) await leaveLine(current)

              return ok
            }}
            onOperationCreated={() => void leaveLine(current)}
            onJudge={(verdict) => void review.judge(current, verdict)}
          />
        ) : null
      case 'balance':
        return <MonthBalanceTab />
      case 'envelopes':
        return <EnvelopeAdjustments month={month} />
      case 'upcoming':
        return <UpcomingStep projection={upcoming.projection} error={upcoming.error} retry={() => void upcoming.refresh()} />
      case 'projects':
        return <ProjectsStep projection={upcoming.projection} />
    }
  }

  return (
    <ThemeProvider theme={activityTheme}>
      <Box
        data-testid="review-activity"
        data-step={step ?? 'loading'}
        sx={{ position: 'fixed', inset: 0, display: 'flex', bgcolor: 'background.default', color: 'text.primary' }}
      >
        <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
          <Box component="header" sx={{ px: { xs: 2, md: 4 }, pt: 2, pb: 1 }}>
            <Stack direction="row" alignItems="center" spacing={1}>
              <Box
                role="progressbar"
                aria-label="Étapes de la revue"
                aria-valuemin={1}
                aria-valuemax={STEPS.length}
                aria-valuenow={Math.min(activeIndex + 1, STEPS.length)}
                sx={{ flex: 1, display: 'flex', gap: 0.75 }}
              >
                {STEPS.map((segment, index) => (
                  <Box
                    key={segment}
                    data-testid={`review-segment-${segment}`}
                    data-state={index < activeIndex ? 'done' : index === activeIndex ? 'current' : 'todo'}
                    title={STEP_LABELS[segment]}
                    sx={{
                      flex: 1,
                      height: 4,
                      borderRadius: 2,
                      bgcolor: index <= activeIndex ? 'primary.main' : 'veilleuse.track',
                      opacity: index < activeIndex ? 0.6 : 1,
                    }}
                  />
                ))}
              </Box>
              <IconButton aria-label="Maggie" onClick={() => setChatOpen((open) => !open)} color={chatOpen ? 'primary' : 'default'}>
                <AutoAwesomeIcon />
              </IconButton>
              <IconButton aria-label="Quitter la revue" onClick={quit}>
                <CloseIcon />
              </IconButton>
            </Stack>
            {step && step !== 'done' && (
              <Stack direction="row" alignItems="baseline" spacing={2} sx={{ mt: 1.5, flexWrap: 'wrap' }}>
                <Typography variant="h5" component="h1" sx={{ fontWeight: 600 }}>
                  {STEP_TITLES[step]}
                </Typography>
                <Typography variant="body2" color="text.secondary" data-testid="review-then">
                  Ensuite… {following ? STEP_LABELS[following] : 'fin de la revue'}
                </Typography>
              </Stack>
            )}
          </Box>

          <Box component="main" sx={{ flex: 1, overflowY: 'auto', px: { xs: 2, md: 4 }, py: 2 }}>
            {(review.gestureError || progress.saveError) && (
              <Alert severity="error" sx={{ mb: 2 }}>
                {review.gestureError ?? progress.saveError}
              </Alert>
            )}
            {body()}
          </Box>

          {step && step !== 'done' && !loadError && (
            <Box
              component="footer"
              sx={{ px: { xs: 2, md: 4 }, py: 2, display: 'flex', justifyContent: 'space-between', borderTop: 1, borderColor: 'divider' }}
            >
              <Button onClick={previous} disabled={!previousTarget || busy}>
                ← Précédent
              </Button>
              <Typography variant="caption" color="text.secondary" sx={{ alignSelf: 'center', display: { xs: 'none', md: 'block' } }}>
                ← → Entrée · Échap pour quitter
              </Typography>
              <Button variant="contained" onClick={() => void next()} disabled={busy}>
                {primaryLabel} →
              </Button>
            </Box>
          )}
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
      </Box>
    </ThemeProvider>
  )
}
