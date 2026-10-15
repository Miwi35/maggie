import { useCallback, useEffect, useState } from 'react'
import { useNotify } from 'react-admin'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import CardHeader from '@mui/material/CardHeader'
import Checkbox from '@mui/material/Checkbox'
import CircularProgress from '@mui/material/CircularProgress'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useMercure } from '../../hooks/useMercure'

/**
 * Réglages › Tâches (MAG-407): when Maggie alerts about a task, by urgency,
 * and the weights the task order is computed from (MAG-406).
 *
 * The phone reads the same settings: `GET /api/task-settings/me`.
 */

const API_PATH = '/api/task-settings/me'
const MERCURE_TOPICS = ['/api/task_settings/{id}']

const URGENCIES = ['critical', 'high', 'normal'] as const
type Urgency = (typeof URGENCIES)[number]

const URGENCY_LABELS: Record<Urgency, string> = {
  critical: 'Critique',
  high: 'Haute',
  normal: 'Normale',
}

const TIME_PRESSURE_FIELDS = [
  ['overdue', 'En retard'],
  ['overduePerDay', 'Par jour de retard'],
  ['overdueMax', 'Plafond du retard'],
  ['today', "Aujourd'hui"],
  ['tomorrow', 'Demain'],
  ['in2To3Days', 'Dans 2 à 3 jours'],
  ['in4To7Days', 'Dans 4 à 7 jours'],
  ['in8To30Days', 'Dans 8 à 30 jours'],
  ['later', 'Plus tard ou sans date'],
] as const

const CRITICALITY_FIELDS = [
  ['critical', 'Critique'],
  ['high', 'Haute'],
  ['medium', 'Moyenne'],
  ['low', 'Basse'],
] as const

const PRIORITY_FIELDS = [
  ['high', 'Haute'],
  ['medium', 'Moyenne'],
  ['low', 'Basse'],
] as const

type WeightGroup = 'timePressure' | 'criticality' | 'priority'

const WEIGHT_GROUPS: { group: WeightGroup; title: string; fields: readonly (readonly [string, string])[] }[] = [
  { group: 'timePressure', title: 'Pression du temps', fields: TIME_PRESSURE_FIELDS },
  { group: 'criticality', title: 'Criticité', fields: CRITICALITY_FIELDS },
  { group: 'priority', title: 'Priorité', fields: PRIORITY_FIELDS },
]

interface UrgencySchedule {
  daysBefore: number[]
  dueDay: boolean
  overdueEveryDays: number | null
}

interface SettingsValues {
  orderWeights: Record<WeightGroup, Record<string, number>>
  alertSchedule: Record<Urgency, UrgencySchedule>
  morningTime: string
}

interface TaskSettingsResponse extends SettingsValues {
  id: string
  defaults: SettingsValues
}

/** The form holds what the user typed, as typed: it is parsed on save. */
interface UrgencyForm {
  daysBefore: string
  dueDay: boolean
  overdueEveryDays: string
}

interface FormState {
  morningTime: string
  schedule: Record<Urgency, UrgencyForm>
  weights: Record<WeightGroup, Record<string, string>>
}

type Errors = Record<string, string>

function apiFetch(path: string, options: RequestInit = {}) {
  const token = localStorage.getItem('token')
  return fetch(path, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  })
}

function toForm(values: SettingsValues): FormState {
  const weights = {} as FormState['weights']
  for (const { group, fields } of WEIGHT_GROUPS) {
    weights[group] = Object.fromEntries(fields.map(([key]) => [key, String(values.orderWeights[group]?.[key] ?? 0)]))
  }
  const schedule = {} as FormState['schedule']
  for (const urgency of URGENCIES) {
    const s = values.alertSchedule[urgency]
    schedule[urgency] = {
      daysBefore: (s?.daysBefore ?? []).join(', '),
      dueDay: s?.dueDay ?? false,
      overdueEveryDays: s?.overdueEveryDays == null ? '' : String(s.overdueEveryDays),
    }
  }
  return { morningTime: values.morningTime, schedule, weights }
}

const INTEGER = /^\d+$/
const TIME = /^([01]\d|2[0-3]):[0-5]\d$/

/** The field keys errors are filed under. */
const daysKey = (u: Urgency) => `${u}.daysBefore`
const overdueKey = (u: Urgency) => `${u}.overdueEveryDays`
const weightKey = (group: WeightGroup, key: string) => `${group}.${key}`

function parseForm(form: FormState): { values: SettingsValues | null; errors: Errors } {
  const errors: Errors = {}

  if (!TIME.test(form.morningTime)) {
    errors.morningTime = 'Heure au format HH:MM'
  }

  const alertSchedule = {} as SettingsValues['alertSchedule']
  for (const urgency of URGENCIES) {
    const entry = form.schedule[urgency]
    const parts = entry.daysBefore
      .split(',')
      .map((p) => p.trim())
      .filter((p) => p !== '')
    const days = parts.map(Number)
    if (parts.some((p) => !INTEGER.test(p)) || days.some((d) => d < 1 || d > 30)) {
      errors[daysKey(urgency)] = 'Des nombres de 1 à 30 séparés par des virgules'
    }

    const overdue = entry.overdueEveryDays.trim()
    let overdueEveryDays: number | null = null
    if (overdue !== '') {
      overdueEveryDays = Number(overdue)
      if (!INTEGER.test(overdue) || overdueEveryDays < 1 || overdueEveryDays > 30) {
        errors[overdueKey(urgency)] = 'Un nombre de 1 à 30, ou vide pour jamais'
      }
    }

    alertSchedule[urgency] = { daysBefore: days, dueDay: entry.dueDay, overdueEveryDays }
  }

  const orderWeights = {} as SettingsValues['orderWeights']
  for (const { group, fields } of WEIGHT_GROUPS) {
    orderWeights[group] = {}
    for (const [key] of fields) {
      const raw = form.weights[group][key].trim()
      if (!INTEGER.test(raw)) {
        errors[weightKey(group, key)] = 'Un entier positif ou nul'
      }
      orderWeights[group][key] = Number(raw)
    }
  }

  if (Object.keys(errors).length > 0) {
    return { values: null, errors }
  }
  return { values: { orderWeights, alertSchedule, morningTime: form.morningTime }, errors }
}

export const TaskSettings = () => {
  const notify = useNotify()
  const [settings, setSettings] = useState<TaskSettingsResponse | null>(null)
  const [form, setForm] = useState<FormState | null>(null)
  const [errors, setErrors] = useState<Errors>({})
  const [loadFailed, setLoadFailed] = useState(false)
  const [saving, setSaving] = useState(false)

  const apply = useCallback((data: TaskSettingsResponse) => {
    setSettings(data)
    setForm(toForm(data))
    setErrors({})
  }, [])

  const load = useCallback(async () => {
    try {
      const res = await apiFetch(API_PATH)
      if (!res.ok) throw new Error(String(res.status))
      apply(await res.json())
      setLoadFailed(false)
    } catch {
      setLoadFailed(true)
    }
  }, [apply])

  useEffect(() => {
    load()
  }, [load])

  // A change made elsewhere (the phone, Maggie) shows up here.
  const handleMercure = useCallback(() => {
    load()
  }, [load])

  useMercure(MERCURE_TOPICS, handleMercure)

  if (loadFailed && !form) {
    return (
      <Box maxWidth={700} mx="auto" mt={2}>
        <Alert severity="error">Impossible de charger les réglages des tâches</Alert>
      </Box>
    )
  }

  if (!form || !settings) {
    return (
      <Box display="flex" justifyContent="center" mt={4}>
        <CircularProgress />
      </Box>
    )
  }

  const setSchedule = (urgency: Urgency, patch: Partial<UrgencyForm>) =>
    setForm({ ...form, schedule: { ...form.schedule, [urgency]: { ...form.schedule[urgency], ...patch } } })

  const setWeight = (group: WeightGroup, key: string, value: string) =>
    setForm({ ...form, weights: { ...form.weights, [group]: { ...form.weights[group], [key]: value } } })

  const handleDefaults = () => {
    setForm(toForm(settings.defaults))
    setErrors({})
  }

  const handleSave = async () => {
    const { values, errors: found } = parseForm(form)
    setErrors(found)
    if (!values) return

    setSaving(true)
    try {
      const res = await apiFetch(API_PATH, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(values),
      })
      if (res.ok) {
        apply(await res.json())
        notify('Réglages des tâches enregistrés', { type: 'success' })
      } else {
        const body = await res.json().catch(() => null)
        const message =
          res.status === 400 && typeof body?.error === 'string'
            ? body.error
            : "Impossible d'enregistrer les réglages des tâches"
        notify(message, { type: 'error' })
      }
    } catch {
      notify("Impossible d'enregistrer les réglages des tâches", { type: 'error' })
    } finally {
      setSaving(false)
    }
  }

  return (
    <Box maxWidth={700} mx="auto" mt={2}>
      <Typography variant="h5" mb={3}>
        Tâches
      </Typography>

      <Card sx={{ mb: 3 }}>
        <CardHeader title="Alertes" />
        <CardContent>
          <Stack spacing={3}>
            <TextField
              type="time"
              label="Heure du matin"
              helperText={errors.morningTime ?? 'Les alertes du jour partent à cette heure.'}
              error={Boolean(errors.morningTime)}
              value={form.morningTime}
              onChange={(e) => setForm({ ...form, morningTime: e.target.value })}
              size="small"
              slotProps={{ inputLabel: { shrink: true } }}
              sx={{ maxWidth: 200 }}
            />

            {URGENCIES.map((urgency) => {
              const label = URGENCY_LABELS[urgency]
              const entry = form.schedule[urgency]
              return (
                <Box key={urgency}>
                  <Typography variant="subtitle2" gutterBottom>
                    {label}
                  </Typography>
                  <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ sm: 'flex-start' }}>
                    <TextField
                      label="Jours avant l'échéance"
                      helperText={errors[daysKey(urgency)] ?? 'ex. 3, 1 — J-3 et J-1'}
                      error={Boolean(errors[daysKey(urgency)])}
                      value={entry.daysBefore}
                      onChange={(e) => setSchedule(urgency, { daysBefore: e.target.value })}
                      size="small"
                      slotProps={{ htmlInput: { 'aria-label': `${label} — jours avant l'échéance` } }}
                    />
                    <FormControlLabel
                      control={
                        <Checkbox
                          checked={entry.dueDay}
                          onChange={(e) => setSchedule(urgency, { dueDay: e.target.checked })}
                          slotProps={{ input: { 'aria-label': `${label} — le jour J` } }}
                        />
                      }
                      label="Le jour J"
                    />
                    <TextField
                      type="number"
                      label="En retard, tous les … jours"
                      helperText={errors[overdueKey(urgency)] ?? 'Vide : jamais'}
                      error={Boolean(errors[overdueKey(urgency)])}
                      value={entry.overdueEveryDays}
                      onChange={(e) => setSchedule(urgency, { overdueEveryDays: e.target.value })}
                      size="small"
                      slotProps={{ htmlInput: { min: 1, max: 30, 'aria-label': `${label} — en retard, tous les … jours` } }}
                    />
                  </Stack>
                </Box>
              )
            })}

            <Typography variant="body2" color="text.secondary">
              Basse : jamais d'alerte, seulement l'affichage.
            </Typography>
          </Stack>
        </CardContent>
      </Card>

      <Card sx={{ mb: 3 }}>
        <CardHeader title="Ordre des tâches" />
        <CardContent>
          <Stack spacing={3}>
            {WEIGHT_GROUPS.map(({ group, title, fields }) => (
              <Box key={group}>
                <Typography variant="subtitle2" gutterBottom>
                  {title}
                </Typography>
                <Box display="grid" gridTemplateColumns={{ xs: '1fr', sm: '1fr 1fr 1fr' }} gap={2}>
                  {fields.map(([key, fieldLabel]) => {
                    const errorKey = weightKey(group, key)
                    return (
                      <TextField
                        key={key}
                        type="number"
                        label={fieldLabel}
                        error={Boolean(errors[errorKey])}
                        helperText={errors[errorKey]}
                        value={form.weights[group][key]}
                        onChange={(e) => setWeight(group, key, e.target.value)}
                        size="small"
                        slotProps={{ htmlInput: { min: 0, 'aria-label': `${title} — ${fieldLabel}` } }}
                      />
                    )
                  })}
                </Box>
              </Box>
            ))}
          </Stack>
        </CardContent>
      </Card>

      <Stack direction="row" spacing={2} justifyContent="flex-end" mb={4}>
        <Button variant="outlined" onClick={handleDefaults} disabled={saving}>
          Valeurs par défaut
        </Button>
        <Button variant="contained" onClick={handleSave} disabled={saving}>
          Enregistrer
        </Button>
      </Stack>
    </Box>
  )
}
