import { useState } from 'react'
import type { FormEvent, ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import { useCreate, useGetList, useNotify, useRedirect } from 'react-admin'
import { NameChipsInput } from './NameChipsInput'
import { utensilNames } from './recipeSheet'
import type { RecipeRecord } from './recipeSheet'

/**
 * Saves the popin's edit: it closes when the action answers null (the API kept it), shows the
 * refusal when it answers a message, and stays open as it is when it answers false.
 */
export const useDialogSave = (onClose: () => void) => {
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const run = async (action: () => Promise<string | null | false>) => {
    setSaving(true)
    setError(null)
    const refused = await action()
    setSaving(false)
    if (refused === false) return
    if (refused) setError(refused)
    else onClose()
  }

  return { error, setError, saving, run }
}

/** A popin that edits one block of the sheet. */
export const EditDialog = ({
  title,
  onClose,
  onSubmit,
  error,
  saving = false,
  submitDisabled = false,
  submitLabel = 'Enregistrer',
  cancelLabel = 'Annuler',
  children,
}: {
  title: string
  onClose: () => void
  onSubmit: () => void
  error?: string | null
  saving?: boolean
  submitDisabled?: boolean
  submitLabel?: string
  cancelLabel?: string
  children: ReactNode
}) => {
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!saving && !submitDisabled) onSubmit()
  }

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" aria-labelledby="recipe-dialog-title">
      <form onSubmit={submit} noValidate>
        <DialogTitle id="recipe-dialog-title">{title}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {children}
            {error && <Alert severity="error">{error}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>{cancelLabel}</Button>
          <Button type="submit" variant="contained" disabled={saving || submitDisabled}>
            {submitLabel}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}

type Save = (data: Record<string, unknown>) => Promise<string | null>

const parseTags = (text: string): string[] =>
  text
    .split(',')
    .map((tag) => tag.trim())
    .filter(Boolean)

export const HeaderDialog = ({
  recipe,
  creating = false,
  onSave,
  onClose,
}: {
  recipe: Pick<RecipeRecord, 'name' | 'servings' | 'tags'>
  creating?: boolean
  /** False keeps the popin as it is: creating redirects away, which closes it. */
  onSave: (data: Record<string, unknown>) => Promise<string | null | false>
  onClose: () => void
}) => {
  const [name, setName] = useState(recipe.name)
  const [servings, setServings] = useState(String(recipe.servings ?? 4))
  const [tags, setTags] = useState((recipe.tags ?? []).join(', '))
  const { error, saving, run } = useDialogSave(onClose)
  const people = Number.parseInt(servings, 10)
  const valid = name.trim() !== '' && Number.isInteger(people) && people >= 1

  return (
    <EditDialog
      title={creating ? 'Nouvelle recette' : 'Nom, portions et tags'}
      onClose={onClose}
      error={error}
      saving={saving}
      submitDisabled={!valid}
      submitLabel={creating ? 'Créer' : 'Enregistrer'}
      onSubmit={() => run(() => onSave({ name: name.trim(), servings: people, tags: parseTags(tags) }))}
    >
      <TextField label="Nom" value={name} onChange={(event) => setName(event.target.value)} required autoFocus fullWidth />
      <TextField
        label="Portions"
        type="number"
        value={servings}
        onChange={(event) => setServings(event.target.value)}
        slotProps={{ htmlInput: { min: 1 } }}
        sx={{ maxWidth: 160 }}
      />
      <TextField label="Tags (séparés par des virgules)" value={tags} onChange={(event) => setTags(event.target.value)} fullWidth />
    </EditDialog>
  )
}

export const NotesDialog = ({ notes, onSave, onClose }: { notes: string | null | undefined; onSave: Save; onClose: () => void }) => {
  const [text, setText] = useState(notes ?? '')
  const { error, saving, run } = useDialogSave(onClose)

  return (
    <EditDialog
      title="Notes"
      onClose={onClose}
      error={error}
      saving={saving}
      onSubmit={() => run(() => onSave({ notes: text.trim() === '' ? null : text }))}
    >
      <TextField label="Notes" value={text} onChange={(event) => setText(event.target.value)} multiline minRows={4} autoFocus fullWidth />
    </EditDialog>
  )
}

export const UtensilsDialog = ({ utensils, onSave, onClose }: { utensils: RecipeRecord['utensils']; onSave: Save; onClose: () => void }) => {
  const [names, setNames] = useState(utensilNames(utensils))
  const { error, saving, run } = useDialogSave(onClose)
  const { data } = useGetList<{ id: string; name: string }>('utensils', {
    pagination: { page: 1, perPage: 100 },
    sort: { field: 'name', order: 'ASC' },
  })

  return (
    <EditDialog title="Ustensiles" onClose={onClose} error={error} saving={saving} onSubmit={() => run(() => onSave({ utensils: names }))}>
      <NameChipsInput label="Ustensiles" create value={names} options={(data ?? []).map((utensil) => utensil.name)} onChange={setNames} />
    </EditDialog>
  )
}

const today = () => new Date().toLocaleDateString('sv-SE')

const SLOTS = [
  { value: 'lunch', label: 'Midi' },
  { value: 'dinner', label: 'Soir' },
]

export const PlanMealDialog = ({ recipe, onClose }: { recipe: RecipeRecord; onClose: () => void }) => {
  const [date, setDate] = useState(today())
  const [slot, setSlot] = useState('dinner')
  const [create, { isPending }] = useCreate()
  const notify = useNotify()
  const redirect = useRedirect()
  const [error, setError] = useState<string | null>(null)

  const plan = () => {
    setError(null)
    create(
      'meals',
      { data: { date, slot, recipes: [recipe['@id'] ?? `/api/recipes/${String(recipe.id).split('/').pop()}`] } },
      {
        onSuccess: () => {
          notify('Repas planifié', { type: 'success' })
          onClose()
          redirect('/meals')
        },
        onError: (failure) => setError((failure as Error)?.message || 'Impossible de planifier ce repas'),
      },
    )
  }

  return (
    <EditDialog title={`Planifier « ${recipe.name} »`} onClose={onClose} error={error} saving={isPending} submitDisabled={date === ''} submitLabel="Planifier" onSubmit={plan}>
      <TextField label="Jour" type="date" value={date} onChange={(event) => setDate(event.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
      <TextField label="Repas" select value={slot} onChange={(event) => setSlot(event.target.value)}>
        {SLOTS.map((option) => (
          <MenuItem key={option.value} value={option.value}>
            {option.label}
          </MenuItem>
        ))}
      </TextField>
    </EditDialog>
  )
}
