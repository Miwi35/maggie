import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { useNotify } from 'react-admin'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import ReplayIcon from '@mui/icons-material/Replay'
import { agentFetch } from './agentFetch'

interface JournalInstruction {
  id: string | null
  content: string
}

interface JournalScheduled {
  id: string | null
  prompt: string | null
  summary: string | null
  scheduledAt: string | null
}

interface JournalToolCall {
  name: string
  input: Record<string, unknown>
  result: string
  error: string | null
}

/** One run as `GET /agent/journal` serves it (MAG-688): a daily planning, or a proaction. */
export interface JournalEntry {
  id: string
  kind: 'planning' | 'proaction'
  status: 'completed' | 'failed'
  day: string
  startedAt: string | null
  durationMs: number
  proactionId: string | null
  prompt: string | null
  summary: string | null
  instructions: JournalInstruction[]
  scheduled: JournalScheduled[]
  toolCalls: JournalToolCall[]
  response: string | null
  error: string | null
  inputTokens: number
  outputTokens: number
  origin?: { planningId: string; day: string; instructions: JournalInstruction[] } | null
}

const JOURNAL_DAYS = 30

function time(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}

function dayLabel(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
}

function duration(ms: number): string {
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1).replace('.', ',')} s`
}

function title(entry: JournalEntry): string {
  if (entry.kind === 'planning') return 'Planification du jour'
  return entry.summary || entry.prompt || 'Proaction'
}

/** The runs grouped by the day they started on, in the order they came (newest first). */
function byDay(entries: JournalEntry[]): Array<[string, JournalEntry[]]> {
  const groups = new Map<string, JournalEntry[]>()
  for (const entry of entries) {
    const label = dayLabel(entry.startedAt)
    groups.set(label, [...(groups.get(label) ?? []), entry])
  }
  return [...groups.entries()]
}

const Section = ({ label, children }: { label: string; children: ReactNode }) => (
  <Box>
    <Typography variant="subtitle2" gutterBottom>
      {label}
    </Typography>
    {children}
  </Box>
)

const Instructions = ({ instructions }: { instructions: JournalInstruction[] }) =>
  instructions.length === 0 ? (
    <Typography variant="body2" color="text.secondary">
      Aucune consigne lue.
    </Typography>
  ) : (
    <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
      {instructions.map((instruction) => (
        <li key={instruction.id ?? instruction.content}>
          <Typography variant="body2">{instruction.content}</Typography>
        </li>
      ))}
    </Box>
  )

const ToolCalls = ({ calls }: { calls: JournalToolCall[] }) =>
  calls.length === 0 ? (
    <Typography variant="body2" color="text.secondary">
      Aucun outil appelé.
    </Typography>
  ) : (
    <Stack spacing={1}>
      {calls.map((call, index) => (
        <Box
          key={`${call.name}-${index}`}
          data-testid="journal-tool-call"
          sx={{ borderLeft: 3, borderColor: call.error ? 'error.main' : 'divider', pl: 1.5 }}
        >
          <Typography variant="body2" fontWeight="medium" fontFamily="monospace">
            {call.name}({JSON.stringify(call.input)})
          </Typography>
          {call.error ? (
            <Typography variant="body2" color="error">
              Erreur : {call.error}
            </Typography>
          ) : (
            <Typography
              variant="caption"
              component="pre"
              color="text.secondary"
              sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-all', fontFamily: 'monospace', m: 0 }}
            >
              {call.result}
            </Typography>
          )}
        </Box>
      ))}
    </Stack>
  )

const EntryDetails = ({ entry }: { entry: JournalEntry }) => (
  <Stack spacing={2}>
    {entry.error && <Alert severity="error">{entry.error}</Alert>}
    {entry.kind === 'planning' ? (
      <>
        <Section label="Consignes lues">
          <Instructions instructions={entry.instructions} />
        </Section>
        <Section label="Proactions programmées">
          {entry.scheduled.length === 0 ? (
            <Typography variant="body2" color="text.secondary">
              Aucune proaction programmée.
            </Typography>
          ) : (
            <Box component="ul" sx={{ m: 0, pl: 2.5 }}>
              {entry.scheduled.map((scheduled, index) => (
                <li key={scheduled.id ?? index}>
                  <Typography variant="body2">
                    {time(scheduled.scheduledAt)} · {scheduled.summary || scheduled.prompt}
                  </Typography>
                </li>
              ))}
            </Box>
          )}
        </Section>
      </>
    ) : (
      <>
        <Section label="Consigne d'origine">
          {entry.origin ? (
            <>
              <Typography variant="body2" color="text.secondary" gutterBottom>
                Programmée par la planification du {entry.origin.day}, qui a lu :
              </Typography>
              <Instructions instructions={entry.origin.instructions} />
            </>
          ) : (
            <Typography variant="body2" color="text.secondary">
              Inconnue : elle n&apos;a pas été programmée par une planification encore au journal.
            </Typography>
          )}
        </Section>
        {entry.prompt && (
          <Section label="Demande">
            <Typography variant="body2">{entry.prompt}</Typography>
          </Section>
        )}
      </>
    )}
    <Section label="Outils appelés">
      <ToolCalls calls={entry.toolCalls} />
    </Section>
    {entry.response && (
      <Section label="Réponse">
        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
          {entry.response}
        </Typography>
      </Section>
    )}
  </Stack>
)

/**
 * The journal of what Maggie did on her own (MAG-688): each daily planning — the instructions it
 * read, the proactions it scheduled — and each proaction run, with its tool calls, duration and
 * tokens. « Relancer la planification maintenant » runs today's planning at once, to try a new
 * instruction without waiting for tomorrow.
 */
export const ProactionJournal = () => {
  const notify = useNotify()
  const [entries, setEntries] = useState<JournalEntry[] | null>(null)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [relaunching, setRelaunching] = useState(false)

  const load = useCallback(async () => {
    try {
      const res = await agentFetch(`/journal?days=${JOURNAL_DAYS}`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      setEntries(await res.json())
    } catch {
      setEntries([])
      notify('Le journal n’a pas pu être chargé', { type: 'error' })
    }
  }, [notify])

  useEffect(() => {
    void load()
  }, [load])

  const relaunch = async () => {
    setRelaunching(true)
    try {
      const res = await agentFetch('/journal/planning', { method: 'POST' })
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const entry = (await res.json()) as JournalEntry
      setEntries((prev) => [entry, ...(prev ?? []).filter((e) => e.id !== entry.id)])
      setExpanded(entry.id)
      notify(`Planification relancée : ${entry.scheduled.length} proaction(s) programmée(s)`, { type: 'success' })
    } catch {
      notify('La planification a échoué : son erreur est au journal', { type: 'error' })
      await load()
    } finally {
      setRelaunching(false)
    }
  }

  return (
    <Stack spacing={2}>
      <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between">
        <Typography variant="body2" color="text.secondary">
          Ce que Maggie a lu, décidé et programmé d&apos;elle-même, sur les {JOURNAL_DAYS} derniers jours.
        </Typography>
        <Button
          variant="contained"
          startIcon={relaunching ? <CircularProgress size={16} /> : <ReplayIcon />}
          onClick={relaunch}
          disabled={relaunching}
          sx={{ flexShrink: 0 }}
        >
          Relancer la planification maintenant
        </Button>
      </Stack>

      {entries === null ? (
        <Box display="flex" justifyContent="center" py={2}>
          <CircularProgress size={24} />
        </Box>
      ) : entries.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Rien au journal pour le moment.
        </Typography>
      ) : (
        byDay(entries).map(([day, runs]) => (
          <Box key={day} component="section" aria-label={day}>
            <Typography variant="overline" component="h3" sx={{ textTransform: 'capitalize' }}>
              {day}
            </Typography>
            {runs.map((entry) => (
              <Accordion
                key={entry.id}
                disableGutters
                expanded={expanded === entry.id}
                onChange={(_, open) => setExpanded(open ? entry.id : null)}
                slotProps={{ transition: { unmountOnExit: true } }}
              >
                <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                  <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
                    <Typography variant="body2" color="text.secondary">
                      {time(entry.startedAt)}
                    </Typography>
                    <Typography variant="body2" fontWeight="medium">
                      {title(entry)}
                    </Typography>
                    <Chip
                      size="small"
                      label={entry.status === 'failed' ? 'Échec' : 'Terminée'}
                      color={entry.status === 'failed' ? 'error' : 'success'}
                    />
                    {entry.kind === 'planning' && (
                      <Typography variant="caption" color="text.secondary">
                        {entry.instructions.length} consigne(s) · {entry.scheduled.length} proaction(s)
                      </Typography>
                    )}
                    <Typography variant="caption" color="text.secondary">
                      {duration(entry.durationMs)} · {entry.inputTokens + entry.outputTokens} tokens
                    </Typography>
                  </Stack>
                </AccordionSummary>
                <AccordionDetails>
                  <EntryDetails entry={entry} />
                </AccordionDetails>
              </Accordion>
            ))}
          </Box>
        ))
      )}
    </Stack>
  )
}
