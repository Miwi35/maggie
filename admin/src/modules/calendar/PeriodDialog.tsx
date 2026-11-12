import { useEffect, useState } from 'react'
import { useCreate, useDataProvider, useDelete, useGetList, useUpdate } from 'react-admin'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import { parseDay } from '../../dates'
import { RecurrencePicker } from './RecurrencePicker'
import { rruleToFrenchText } from './recurrenceUtils'
import { periodIri, REPEAT_PRESETS, toFormValues, toPayload } from './periodsApi'
import type { Period, PeriodFormValues } from './periodsApi'

interface Agenda {
  id: string
  name: string
  default?: boolean
  module?: string | null
}

const CUSTOM = 'custom'

const repeatChoice = (rrule: string | null): string =>
  rrule === null ? '' : (REPEAT_PRESETS.find((preset) => preset.value === rrule)?.value ?? CUSTOM)

interface PeriodDialogProps {
  open: boolean
  /** The period to edit; null opens the form blank, to create one. */
  period: Period | null
  onClose: () => void
}

/** The one centred dialog a period is created, edited and deleted in (MAG-816). */
export const PeriodDialog = ({ open, period, onClose }: PeriodDialogProps) => {
  const { data: agendas } = useGetList<Agenda>(
    'agendas',
    { pagination: { page: 1, perPage: 50 }, sort: { field: 'name', order: 'ASC' }, filter: {} },
    { enabled: open },
  )
  const dataProvider = useDataProvider()
  const [create, { isPending: creating }] = useCreate()
  const [update, { isPending: updating }] = useUpdate()
  const [deleteOne, { isPending: deleting }] = useDelete()

  const [values, setValues] = useState<PeriodFormValues>(() => toFormValues(null))
  const [customOpen, setCustomOpen] = useState(false)
  const [nameError, setNameError] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)

  useEffect(() => {
    if (!open) return
    setValues(toFormValues(period))
    setCustomOpen(false)
    setNameError(false)
    setError(null)
    setConfirming(false)
  }, [open, period])

  // A row born from a Mercure event carries no agenda: read it, rather than let « Enregistrer » move the period to the default one.
  useEffect(() => {
    if (!open || !period || period.agenda) return
    let current = true
    dataProvider
      .getOne('periods', { id: periodIri(period.id) })
      .then(({ data }) => {
        if (current && data.agenda) setValues((values) => ({ ...values, agenda: String(data.agenda) }))
      })
      .catch(() => {})

    return () => {
      current = false
    }
  }, [open, period, dataProvider])

  // No agenda chosen yet: the default one, as soon as the list is there.
  useEffect(() => {
    if (!open || values.agenda || !agendas) return
    const fallback = agendas.find((a) => a.default) ?? agendas[0]
    if (fallback) setValues((current) => ({ ...current, agenda: String(fallback.id) }))
  }, [open, values.agenda, agendas])

  const set = (patch: Partial<PeriodFormValues>) => setValues((current) => ({ ...current, ...patch }))

  const toggleAllDay = (allDay: boolean) => {
    const { startAt, endAt } = values
    if (!startAt) return set({ allDay })
    set(
      allDay
        ? { allDay, startAt: startAt.slice(0, 10), endAt: endAt.slice(0, 10) || startAt.slice(0, 10) }
        : { allDay, startAt: `${startAt.slice(0, 10)}T09:00`, endAt: `${(endAt || startAt).slice(0, 10)}T10:00` },
    )
  }

  const chooseRepeat = (choice: string) => {
    if (choice === CUSTOM) {
      setCustomOpen(true)
      set({ rrule: null })
    } else {
      setCustomOpen(false)
      set({ rrule: choice === '' ? null : choice })
    }
  }

  const failure = (verb: string) => () => setError(`Erreur : la période n'a pas été ${verb}. Réessayez.`)

  const save = () => {
    if (!values.name.trim()) {
      setNameError(true)
      return
    }
    setError(null)
    const data = toPayload({ ...values, name: values.name.trim() })
    if (period) {
      update('periods', { id: periodIri(period.id), data, previousData: period }, { onSuccess: onClose, onError: failure('enregistrée') })
    } else {
      create('periods', { data }, { onSuccess: onClose, onError: failure('enregistrée') })
    }
  }

  const remove = () => {
    if (!period) return
    setConfirming(false)
    setError(null)
    deleteOne('periods', { id: periodIri(period.id), previousData: period }, { onSuccess: onClose, onError: failure('supprimée') })
  }

  const busy = creating || updating || deleting
  const inputType = values.allDay ? 'date' : 'datetime-local'
  const choice = customOpen ? CUSTOM : repeatChoice(values.rrule)

  return (
    <>
      <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
        <DialogTitle>{period ? period.name : 'Nouvelle période'}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ mt: 1 }}>
            {error && <Alert severity="error">{error}</Alert>}
            <TextField
              label="Nom"
              value={values.name}
              onChange={(e) => {
                set({ name: e.target.value })
                if (e.target.value.trim()) setNameError(false)
              }}
              error={nameError}
              helperText={nameError ? 'Le nom est requis' : undefined}
              required
              autoFocus
            />
            <TextField label="Agenda" value={values.agenda} onChange={(e) => set({ agenda: e.target.value })} select required>
              {(agendas ?? [])
                .filter((a) => !a.module)
                .map((a) => (
                  <MenuItem key={a.id} value={String(a.id)}>
                    {a.name}
                  </MenuItem>
                ))}
            </TextField>
            <FormControlLabel
              control={<Switch checked={values.allDay} onChange={(e) => toggleAllDay(e.target.checked)} />}
              label="Toute la journée"
            />
            <TextField
              label="Début"
              type={inputType}
              value={values.startAt}
              onChange={(e) => set({ startAt: e.target.value })}
              required
              slotProps={{ inputLabel: { shrink: true } }}
            />
            <TextField
              label="Fin"
              type={inputType}
              value={values.endAt}
              onChange={(e) => set({ endAt: e.target.value })}
              required
              slotProps={{ inputLabel: { shrink: true } }}
            />
            <TextField
              label="Répéter"
              value={choice}
              onChange={(e) => chooseRepeat(e.target.value)}
              select
              helperText={choice === CUSTOM && values.rrule ? rruleToFrenchText(values.rrule) : undefined}
            >
              {REPEAT_PRESETS.map((preset) => (
                <MenuItem key={preset.label} value={preset.value}>
                  {preset.label}
                </MenuItem>
              ))}
              <MenuItem value={CUSTOM}>Personnalisé…</MenuItem>
            </TextField>
            {customOpen && (
              <RecurrencePicker
                value={values.rrule}
                onChange={(rrule) => set({ rrule })}
                eventStartDate={values.startAt ? (values.allDay ? parseDay(values.startAt) : new Date(values.startAt)) : null}
              />
            )}
          </Stack>
        </DialogContent>
        <DialogActions>
          {period && (
            <Button color="error" onClick={() => setConfirming(true)} disabled={busy} sx={{ mr: 'auto' }}>
              Supprimer
            </Button>
          )}
          <Button onClick={onClose}>Annuler</Button>
          <Button variant="contained" onClick={save} disabled={busy || !values.agenda}>
            Enregistrer
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={open && confirming} onClose={() => setConfirming(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Supprimer la période « {period?.name} » ?</DialogTitle>
        <DialogActions>
          <Button onClick={() => setConfirming(false)}>Annuler</Button>
          <Button color="error" variant="contained" onClick={remove}>
            Supprimer
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}
