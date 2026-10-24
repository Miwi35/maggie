import { useState } from 'react'
import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import IconButton from '@mui/material/IconButton'
import Menu from '@mui/material/Menu'
import MenuItem from '@mui/material/MenuItem'
import Paper from '@mui/material/Paper'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import MoreVertIcon from '@mui/icons-material/MoreVert'
import { DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors } from '@dnd-kit/core'
import type { DragEndEvent } from '@dnd-kit/core'
import { SortableContext, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { quantityLabel } from '../grocery/packaging'
import { SeasonBadge } from '../grocery/SeasonBadge'
import { durationLabel, productPackaging, stepsUsing, usedInLabel, utensilNames } from './recipeSheet'
import type { Nutrition, ProductInfo, RecipeLine, RecipeRecord, RecipeStep } from './recipeSheet'
import { dragId, nameOf } from './recipeSteps'
import type { NameRef } from './recipeSteps'

export const Section = ({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) => (
  <Box component="section" aria-label={title} sx={{ mb: 3 }}>
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
      <Typography variant="h6" component="h2" sx={{ flexGrow: 1 }}>
        {title}
      </Typography>
      {action}
    </Box>
    {children}
  </Box>
)

export const EditButton = ({ label, onClick, disabled = false }: { label: string; onClick: () => void; disabled?: boolean }) => (
  <IconButton size="small" aria-label={label} onClick={onClick} disabled={disabled}>
    <EditOutlinedIcon fontSize="small" />
  </IconButton>
)

export interface MenuAction {
  label: string
  onClick: () => void
  disabled?: boolean
  danger?: boolean
}

/** The ⋮ menu of a block, a line or a step. */
export const ActionsMenu = ({ label, actions, disabled = false }: { label: string; actions: MenuAction[]; disabled?: boolean }) => {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null)

  return (
    <>
      <IconButton size="small" aria-label={label} aria-haspopup="menu" disabled={disabled} onClick={(event) => setAnchor(event.currentTarget)}>
        <MoreVertIcon fontSize="small" />
      </IconButton>
      <Menu anchorEl={anchor} open={anchor !== null} onClose={() => setAnchor(null)}>
        {actions.map((action) => (
          <MenuItem
            key={action.label}
            disabled={action.disabled}
            sx={action.danger ? { color: 'error.main' } : undefined}
            onClick={() => {
              setAnchor(null)
              action.onClick()
            }}
          >
            {action.label}
          </MenuItem>
        ))}
      </Menu>
    </>
  )
}

/** The quantity of a line: one click on the number turns it into a field; Enter or leaving it saves. */
const QuantityEdit = ({
  line,
  shown,
  disabled,
  onSave,
}: {
  line: RecipeLine
  shown: { quantity: number; unit: string }
  disabled: boolean
  onSave: (quantity: number) => Promise<string | null>
}) => {
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)

  const commit = async () => {
    const quantity = Number.parseFloat(text.replace(',', '.'))
    if (!Number.isFinite(quantity) || quantity <= 0) {
      setError('La quantité doit être supérieure à 0')
      return
    }
    if (quantity === line.quantity) {
      setEditing(false)
      return
    }
    const refused = await onSave(quantity)
    if (refused) setError(refused)
    else setEditing(false)
  }

  if (!editing) {
    return (
      <Button
        size="small"
        color="inherit"
        disabled={disabled}
        aria-label={`Modifier la quantité de ${line.ingredientName}`}
        onClick={() => {
          setText(String(line.quantity))
          setError(null)
          setEditing(true)
        }}
        sx={{ minWidth: 0, textTransform: 'none', fontWeight: 600 }}
      >
        {quantityLabel(shown.quantity, shown.unit)}
      </Button>
    )
  }

  return (
    <TextField
      size="small"
      type="number"
      autoFocus
      value={text}
      error={error !== null}
      helperText={error}
      onChange={(event) => setText(event.target.value)}
      onBlur={commit}
      onKeyDown={(event) => {
        if (event.key === 'Enter') {
          event.preventDefault()
          commit()
        }
        if (event.key === 'Escape') setEditing(false)
      }}
      slotProps={{ htmlInput: { 'aria-label': `Quantité de ${line.ingredientName}`, min: 0, step: 'any' } }}
      sx={{ width: 110 }}
    />
  )
}

export const IngredientsBlock = ({
  recipe,
  scaled,
  products,
  simulating,
  creating,
  onAdd,
  onEdit,
  onRemove,
  onQuantity,
}: {
  recipe: RecipeRecord
  scaled: Map<string, { quantity: number; unit: string }>
  products: Map<string, ProductInfo>
  simulating: boolean
  creating: boolean
  onAdd: () => void
  onEdit: (line: RecipeLine) => void
  onRemove: (line: RecipeLine) => void
  onQuantity: (line: RecipeLine, quantity: number) => Promise<string | null>
}) => {
  const lines = recipe.ingredients ?? []
  const steps = recipe.steps ?? []

  return (
    <Section
      title="Ingrédients"
      action={
        <Button size="small" startIcon={<AddIcon />} onClick={onAdd} disabled={creating || simulating}>
          Ajouter un ingrédient
        </Button>
      }
    >
      {lines.length === 0 && <Typography color="text.secondary">Aucun ingrédient pour l’instant.</Typography>}
      <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
        {lines.map((line) => {
          const product = line.ingredient?.id ?? ''
          const usedIn = usedInLabel(stepsUsing(steps, product))
          const packaging = productPackaging(products.get(product))
          const shown = (line.id && scaled.get(line.id)) || line

          return (
            <Box component="li" key={line.id ?? line.ingredientName} sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 0.5 }}>
              <Box sx={{ flexGrow: 1 }}>
                <Typography component="span">{line.ingredientName}</Typography>
                {(usedIn || packaging) && (
                  <Typography variant="caption" color="text.secondary" display="block">
                    {[usedIn, packaging].filter(Boolean).join(' · ')}
                  </Typography>
                )}
              </Box>
              <SeasonBadge inSeason={line.inSeason} />
              <QuantityEdit line={line} shown={shown} disabled={simulating} onSave={(quantity) => onQuantity(line, quantity)} />
              <ActionsMenu
                label={`Actions pour ${line.ingredientName}`}
                disabled={simulating}
                actions={[
                  { label: 'Modifier', onClick: () => onEdit(line) },
                  { label: 'Retirer', onClick: () => onRemove(line), danger: true },
                ]}
              />
            </Box>
          )
        })}
      </Box>
    </Section>
  )
}

const Pastilles = ({ names, label }: { names: NameRef[]; label: string }) =>
  names.length === 0 ? null : (
    <Box component="ul" aria-label={label} sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, listStyle: 'none', m: 0, p: 0 }}>
      {names.map((ref) => (
        <li key={nameOf(ref)}>
          <Chip label={nameOf(ref)} size="small" variant="outlined" />
        </li>
      ))}
    </Box>
  )

export const UtensilsBlock = ({ recipe, creating, onEdit }: { recipe: RecipeRecord; creating: boolean; onEdit: () => void }) => {
  const names = utensilNames(recipe.utensils)

  return (
    <Section title="Matériel" action={<EditButton label="Modifier le matériel" onClick={onEdit} disabled={creating} />}>
      {names.length === 0 ? <Typography color="text.secondary">Aucun matériel.</Typography> : <Pastilles names={names} label="Matériel" />}
    </Section>
  )
}

export const NotesBlock = ({ recipe, creating, onEdit }: { recipe: RecipeRecord; creating: boolean; onEdit: () => void }) => (
  <Section title="Notes" action={<EditButton label="Modifier les notes" onClick={onEdit} disabled={creating} />}>
    {recipe.notes ? (
      <Typography sx={{ whiteSpace: 'pre-wrap' }}>{recipe.notes}</Typography>
    ) : (
      <Typography color="text.secondary">Aucune note.</Typography>
    )}
  </Section>
)

const SortableStep = ({
  step,
  index,
  count,
  onEdit,
  onMove,
  onRemove,
}: {
  step: RecipeStep
  index: number
  count: number
  onEdit: () => void
  onMove: (to: number) => void
  onRemove: () => void
}) => {
  const position = index + 1
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: dragId(step, index) })
  const waitsFor = step.waitsFor ?? []

  return (
    <Paper
      ref={setNodeRef}
      component="li"
      variant="outlined"
      aria-label={`Étape ${position}`}
      sx={{ p: 1, mb: 1, display: 'flex', alignItems: 'flex-start', gap: 1, opacity: isDragging ? 0.5 : 1 }}
      style={{ transform: CSS.Transform.toString(transform), transition }}
    >
      <IconButton size="small" aria-label={`Déplacer l'étape ${position}`} sx={{ cursor: 'grab' }} {...attributes} {...listeners}>
        <DragIndicatorIcon fontSize="small" />
      </IconButton>
      <Box sx={{ flexGrow: 1, display: 'flex', flexDirection: 'column', gap: 0.5 }}>
        <Typography component="div">
          <strong>{position}.</strong> {step.text}
        </Typography>
        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, alignItems: 'center' }}>
          {step.durationMinutes !== null && <Chip size="small" label={`⏱ ${durationLabel(step.durationMinutes)}`} />}
          {step.background && <Chip size="small" color="info" label="en fond" />}
          {waitsFor.length > 0 && <Chip size="small" variant="outlined" label={`attend ${waitsFor.join(', ')}`} />}
        </Box>
        <Pastilles names={step.ingredients} label={`Ingrédients de l'étape ${position}`} />
        <Pastilles names={step.utensils} label={`Ustensiles de l'étape ${position}`} />
      </Box>
      <ActionsMenu
        label={`Actions pour l'étape ${position}`}
        actions={[
          { label: 'Modifier', onClick: onEdit },
          { label: 'Monter', onClick: () => onMove(index - 1), disabled: index === 0 },
          { label: 'Descendre', onClick: () => onMove(index + 1), disabled: index === count - 1 },
          { label: 'Supprimer', onClick: onRemove, danger: true },
        ]}
      />
    </Paper>
  )
}

export const StepsBlock = ({
  steps,
  creating,
  onAdd,
  onEdit,
  onMove,
  onRemove,
}: {
  steps: RecipeStep[]
  creating: boolean
  onAdd: () => void
  onEdit: (index: number) => void
  onMove: (from: number, to: number) => void
  onRemove: (index: number) => void
}) => {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return
    const from = steps.findIndex((step, index) => dragId(step, index) === active.id)
    const to = steps.findIndex((step, index) => dragId(step, index) === over.id)
    if (from !== -1 && to !== -1) onMove(from, to)
  }

  return (
    <Section
      title="Étapes"
      action={
        <Button size="small" startIcon={<AddIcon />} onClick={onAdd} disabled={creating}>
          Ajouter une étape
        </Button>
      }
    >
      {steps.length === 0 && <Typography color="text.secondary">Aucune étape pour l’instant.</Typography>}
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
        <SortableContext items={steps.map(dragId)} strategy={verticalListSortingStrategy}>
          <Box component="ol" sx={{ m: 0, p: 0, listStyle: 'none' }}>
            {steps.map((step, index) => (
              <SortableStep
                key={dragId(step, index)}
                step={step}
                index={index}
                count={steps.length}
                onEdit={() => onEdit(index)}
                onMove={(to) => onMove(index, to)}
                onRemove={() => onRemove(index)}
              />
            ))}
          </Box>
        </SortableContext>
      </DndContext>
    </Section>
  )
}

export const NutritionBlock = ({ nutrition }: { nutrition: Nutrition | null }) => {
  if (!nutrition) return null

  return (
    <Section title="Valeurs nutritionnelles par personne">
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
        <Chip label={`${nutrition.kcal} kcal`} />
        <Chip variant="outlined" label={`${nutrition.protein} g de protéines`} />
        <Chip variant="outlined" label={`${nutrition.carbs} g de glucides`} />
        <Chip variant="outlined" label={`${nutrition.fat} g de lipides`} />
      </Box>
      {nutrition.skipped > 0 && (
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
          {nutrition.skipped === 1 ? '1 ingrédient compté à la pièce ou sans valeurs n’est pas inclus.' : `${nutrition.skipped} ingrédients comptés à la pièce ou sans valeurs ne sont pas inclus.`}
        </Typography>
      )}
    </Section>
  )
}
