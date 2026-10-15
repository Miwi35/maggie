import { useCallback, useEffect, useRef, useState } from 'react'
import ArrowDownwardIcon from '@mui/icons-material/ArrowDownward'
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward'
import Box from '@mui/material/Box'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Slider from '@mui/material/Slider'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { urgencyColor } from '../../design/tokens'
import { apiFetch, LEVEL_GROUPS, PREVIEW_PATH, useTaskSettings } from './taskSettingsApi'
import type { LevelGroup } from './taskSettingsApi'
import { DefaultsButton, TaskSettingsFrame } from './TaskSettingsParts'

/**
 * Réglages › Ce qui passe en premier (MAG-407): how much each of the three
 * signals weighs in the task order (MAG-406), as five levels, with five real
 * tasks reordered after each change.
 */

const GROUP_LABELS: Record<LevelGroup, string> = {
  timePressure: "L'échéance",
  criticality: 'La gravité',
  priority: 'Ma priorité',
}

const LEVEL_LABELS = ['Très peu', 'Peu', 'Normal', 'Beaucoup', 'Énormément'] as const

interface PreviewTask {
  id: string
  title: string
  dueDate: string | null
  urgency: string
  orderScore: number
}

type Move = 'up' | 'down' | null

interface PreviewRow extends PreviewTask {
  move: Move
}

/** Where each task went since the previous preview: up means it now comes earlier, or newly in the five. */
function compare(previous: string[] | null, tasks: PreviewTask[]): PreviewRow[] {
  return tasks.map((task, index) => {
    if (!previous) return { ...task, move: null }
    // A task that enters the five comes from below: it went up.
    const before = previous.indexOf(task.id)
    const move: Move = before < 0 || before > index ? 'up' : before < index ? 'down' : null
    return { ...task, move }
  })
}

/** The preview's tasks, or null when it cannot be read: it is a help, not the page. */
async function fetchPreview(): Promise<PreviewTask[] | null> {
  try {
    const res = await apiFetch(PREVIEW_PATH)
    if (!res.ok) return null
    return ((await res.json()) as { member?: PreviewTask[] }).member ?? []
  } catch {
    return null
  }
}

function usePreview() {
  const [rows, setRows] = useState<PreviewRow[]>([])
  const previous = useRef<string[] | null>(null)

  const show = useCallback((tasks: PreviewTask[] | null) => {
    if (!tasks) return
    const ids = tasks.map((t) => t.id)
    // The same order again (the Mercure echo of our own save) keeps its arrows.
    if (previous.current && ids.join() === previous.current.join()) {
      setRows((current) => tasks.map((task, i) => ({ ...task, move: current[i]?.move ?? null })))
      return
    }
    setRows(compare(previous.current, tasks))
    previous.current = ids
  }, [])

  const reload = useCallback(async () => show(await fetchPreview()), [show])

  useEffect(() => {
    fetchPreview().then(show)
  }, [show])

  return { rows, reload }
}

const Preview = ({ rows }: { rows: PreviewRow[] }) => {
  if (rows.length === 0) return null

  return (
    <Card variant="outlined">
      <Box component="ol" aria-label="Aperçu de l'ordre" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {rows.map((task, index) => (
          <Stack
            key={`${task.id}-${index}`}
            component="li"
            direction="row"
            alignItems="center"
            spacing={1.5}
            sx={{ px: 2, py: 1.25, borderTop: index > 0 ? 1 : 0, borderColor: 'divider' }}
          >
            <Box
              aria-hidden
              sx={{ width: 10, height: 10, flexShrink: 0, borderRadius: '50%', bgcolor: urgencyColor(task.urgency) }}
            />
            <Typography variant="body2" sx={{ flex: 1 }} noWrap>
              {task.title}
            </Typography>
            {task.move === 'up' && <ArrowUpwardIcon fontSize="small" color="success" titleAccess="Monte" />}
            {task.move === 'down' && <ArrowDownwardIcon fontSize="small" color="action" titleAccess="Descend" />}
          </Stack>
        ))}
      </Box>
    </Card>
  )
}

export const TaskOrderSettings = () => {
  const { rows, reload } = usePreview()
  const { settings, loadFailed, saved, save, update } = useTaskSettings(reload)

  const setLevel = (group: LevelGroup, level: number) =>
    update((s) => ({ ...s, orderLevels: { ...s.orderLevels, [group]: level } }))

  const saveLevels = async (orderLevels: Partial<Record<LevelGroup, number>>) => {
    await save({ orderLevels }, (s) => ({ ...s, orderLevels: { ...s.orderLevels, ...orderLevels } }))
    await reload()
  }

  return (
    <TaskSettingsFrame title="Ce qui passe en premier" loadFailed={loadFailed} ready={settings !== null} saved={saved}>
      {settings && (
        <Stack spacing={3}>
          <Card variant="outlined">
            <CardContent>
              <Stack spacing={2}>
                {LEVEL_GROUPS.map((group) => {
                  const level = settings.orderLevels[group]
                  return (
                    <Box key={group}>
                      <Stack direction="row" justifyContent="space-between" alignItems="baseline">
                        <Typography variant="subtitle1" component="h2">
                          {GROUP_LABELS[group]}
                        </Typography>
                        <Typography variant="body2" color="text.secondary" data-testid={`level-${group}`}>
                          {LEVEL_LABELS[level]}
                        </Typography>
                      </Stack>
                      <Slider
                        value={level}
                        min={0}
                        max={LEVEL_LABELS.length - 1}
                        step={1}
                        marks
                        aria-label={GROUP_LABELS[group]}
                        getAriaValueText={(value) => LEVEL_LABELS[value]}
                        onChange={(_, value) => setLevel(group, value as number)}
                        onChangeCommitted={(_, value) => saveLevels({ [group]: value as number })}
                      />
                    </Box>
                  )
                })}
              </Stack>
            </CardContent>
          </Card>

          <Preview rows={rows} />

          <DefaultsButton onConfirm={() => saveLevels(settings.defaults.orderLevels)} />
        </Stack>
      )}
    </TaskSettingsFrame>
  )
}
