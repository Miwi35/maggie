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
import { quantityLabel, quantityParts } from '../grocery/packaging'
import { SeasonBadge } from '../grocery/SeasonBadge'
import { durationLabel, productPackaging, servingsLabel, shortName, stepRefLabel, stepsTitle, stepsUsing, utensilNames } from './recipeSheet'
import type { Nutrition, ProductInfo, RecipeLine, RecipeRecord, RecipeStep } from './recipeSheet'
import { dragId, nameOf } from './recipeSteps'
import type { NameRef } from './recipeSteps'

export const Section = ({ title, label, action, children }: { title: string; label?: string; action?: ReactNode; children: ReactNode }) => (
  <Box component="section" aria-label={label ?? title} sx={{ mb: 3 }}>
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

const QUANTITY_WIDTH = 44
const UNIT_WIDTH = 64

/** The quantity of a line, the number and its unit in two columns so the whole list lines up. */
const QuantityText = ({ quantity, unit }: { quantity: number; unit: string }) => {
  const parts = quantityParts(quantity, unit)

  return (
    <>
      <Typography component="span" sx={{ width: QUANTITY_WIDTH, textAlign: 'right', fontWeight: 600, fontVariantNumeric: 'tabular-nums', flexShrink: 0 }}>
        {parts.amount}
      </Typography>
      <Typography component="span" color="text.secondary" sx={{ width: UNIT_WIDTH, flexShrink: 0 }}>
        {parts.unit}
      </Typography>
    </>
  )
}

export const IngredientsBlock = ({
  recipe,
  scaled,
  products,
  simulating,
  creating,
  editing,
  servings,
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
  editing: boolean
  servings: number
  onAdd: () => void
  onEdit: (line: RecipeLine) => void
  onRemove: (line: RecipeLine) => void
  onQuantity: (line: RecipeLine, quantity: number) => Promise<string | null>
}) => {
  const lines = recipe.ingredients ?? []
  const steps = recipe.steps ?? []

  return (
    <Section
      title={`Ingrédients · ${servingsLabel(servings)}`}
      label="Ingrédients"
      action={
        editing && (
          <Button size="small" startIcon={<AddIcon />} onClick={onAdd} disabled={creating || simulating}>
            Ajouter un ingrédient
          </Button>
        )
      }
    >
      {lines.length === 0 && <Typography color="text.secondary">Aucun ingrédient pour l’instant.</Typography>}
      <Box component="ul" sx={{ m: 0, p: 0, listStyle: 'none' }}>
        {lines.map((line) => {
          const product = line.ingredient?.id ?? ''
          const reference = stepRefLabel(stepsUsing(steps, product))
          const packaging = productPackaging(products.get(product))
          const shown = (line.id && scaled.get(line.id)) || line

          return (
            <Box component="li" key={line.id ?? line.ingredientName} sx={{ display: 'flex', alignItems: 'baseline', gap: 1, py: 0.5 }}>
              {editing ? (
                <Box sx={{ width: QUANTITY_WIDTH + UNIT_WIDTH + 8, flexShrink: 0, textAlign: 'right' }}>
                  <QuantityEdit line={line} shown={shown} disabled={simulating} onSave={(quantity) => onQuantity(line, quantity)} />
                </Box>
              ) : (
                <QuantityText quantity={shown.quantity} unit={shown.unit} />
              )}
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Typography component="span">{line.ingredientName}</Typography>
                {packaging && (
                  <Typography variant="caption" color="text.secondary" display="block">
                    {packaging}
                  </Typography>
                )}
              </Box>
              <SeasonBadge inSeason={line.inSeason} />
              {reference && (
                <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
                  {reference}
                </Typography>
              )}
              {editing && (
                <ActionsMenu
                  label={`Actions pour ${line.ingredientName}`}
                  disabled={simulating}
                  actions={[
                    { label: 'Modifier', onClick: () => onEdit(line) },
                    { label: 'Retirer', onClick: () => onRemove(line), danger: true },
                  ]}
                />
              )}
            </Box>
          )
        })}
      </Box>
    </Section>
  )
}

/** The pastilles of a block or a step: the short name of each, the full one on hover. */
const Pastilles = ({ names, label, short = false }: { names: NameRef[]; label: string; short?: boolean }) =>
  names.length === 0 ? null : (
    <Box component="ul" aria-label={label} sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, listStyle: 'none', m: 0, p: 0 }}>
      {names.map((ref) => (
        <li key={nameOf(ref)} title={nameOf(ref)}>
          <Chip label={short ? shortName(nameOf(ref)) : nameOf(ref)} aria-label={nameOf(ref)} size="small" variant="outlined" />
        </li>
      ))}
    </Box>
  )

export const UtensilsBlock = ({ recipe, creating, editing, onEdit }: { recipe: RecipeRecord; creating: boolean; editing: boolean; onEdit: () => void }) => {
  const names = utensilNames(recipe.utensils)
  if (!editing && names.length === 0) return null

  return (
    <Section title="Ustensiles" action={editing && <EditButton label="Modifier les ustensiles" onClick={onEdit} disabled={creating} />}>
      {names.length === 0 ? <Typography color="text.secondary">Aucun ustensile.</Typography> : <Pastilles names={names} label="Ustensiles" />}
    </Section>
  )
}

export const NotesBlock = ({ recipe, creating, editing, onEdit }: { recipe: RecipeRecord; creating: boolean; editing: boolean; onEdit: () => void }) => {
  if (!editing && !recipe.notes) return null

  return (
    <Section title="Notes" action={editing && <EditButton label="Modifier les notes" onClick={onEdit} disabled={creating} />}>
      {recipe.notes ? (
        <Typography sx={{ whiteSpace: 'pre-wrap' }}>{recipe.notes}</Typography>
      ) : (
        <Typography color="text.secondary">Aucune note.</Typography>
      )}
    </Section>
  )
}

const StepContent = ({ step, position }: { step: RecipeStep; position: number }) => {
  const waitsFor = step.waitsFor ?? []

  return (
    <Box sx={{ flexGrow: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 0.5 }}>
      <Typography component="div">{step.text}</Typography>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, alignItems: 'center' }}>
        {step.durationMinutes !== null && <Chip size="small" label={`⏱ ${durationLabel(step.durationMinutes)}`} />}
        {step.background && <Chip size="small" color="info" label="en fond" />}
        {waitsFor.length > 0 && <Chip size="small" variant="outlined" label={`attend ${stepRefLabel(waitsFor)}`} />}
      </Box>
      <Pastilles short names={step.ingredients} label={`Ingrédients de l'étape ${position}`} />
      <Pastilles short names={step.utensils} label={`Ustensiles de l'étape ${position}`} />
    </Box>
  )
}

const StepNumber = ({ position }: { position: number }) => (
  <Box
    aria-hidden
    sx={{
      width: 28,
      height: 28,
      flexShrink: 0,
      borderRadius: '50%',
      bgcolor: 'primary.main',
      color: 'primary.contrastText',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontWeight: 700,
      fontVariantNumeric: 'tabular-nums',
    }}
  >
    {position}
  </Box>
)

const ReadStep = ({ step, index }: { step: RecipeStep; index: number }) => (
  <Paper component="li" variant="outlined" aria-label={`Étape ${index + 1}`} sx={{ p: 1.5, mb: 1, display: 'flex', alignItems: 'flex-start', gap: 1.5 }}>
    <StepNumber position={index + 1} />
    <StepContent step={step} position={index + 1} />
  </Paper>
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

  return (
    <Paper
      ref={setNodeRef}
      component="li"
      variant="outlined"
      aria-label={`Étape ${position}`}
      sx={{ p: 1.5, mb: 1, display: 'flex', alignItems: 'flex-start', gap: 1, opacity: isDragging ? 0.5 : 1 }}
      style={{ transform: CSS.Transform.toString(transform), transition }}
    >
      <IconButton size="small" aria-label={`Déplacer l'étape ${position}`} sx={{ cursor: 'grab' }} {...attributes} {...listeners}>
        <DragIndicatorIcon fontSize="small" />
      </IconButton>
      <StepNumber position={position} />
      <StepContent step={step} position={position} />
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
  editing,
  onAdd,
  onEdit,
  onMove,
  onRemove,
}: {
  steps: RecipeStep[]
  creating: boolean
  editing: boolean
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
      title={stepsTitle(steps)}
      label="Étapes"
      action={
        editing && (
          <Button size="small" startIcon={<AddIcon />} onClick={onAdd} disabled={creating}>
            Ajouter une étape
          </Button>
        )
      }
    >
      {steps.length === 0 && <Typography color="text.secondary">Aucune étape pour l’instant.</Typography>}
      {editing ? (
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
      ) : (
        <Box component="ol" sx={{ m: 0, p: 0, listStyle: 'none' }}>
          {steps.map((step, index) => (
            <ReadStep key={dragId(step, index)} step={step} index={index} />
          ))}
        </Box>
      )}
    </Section>
  )
}

/**
 * What one serving brings, at the end of the sheet. The share of the day's need only shows when the
 * reference is known (Santé › Configuration): without it the line is left out, not guessed.
 */
export const NutritionBlock = ({ nutrition, dailyKcal = null }: { nutrition: Nutrition | null; dailyKcal?: number | null }) => {
  if (!nutrition) return null

  const share = dailyKcal && dailyKcal > 0 ? Math.round((nutrition.kcal / dailyKcal) * 100) : null

  return (
    <Section title="Valeurs par personne">
      <Typography data-testid="nutrition-energy" sx={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
        {`≈ ${nutrition.kcal} kcal`}
        {share !== null && ` · ${share} % du besoin du jour`}
      </Typography>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 3, mt: 0.5, fontVariantNumeric: 'tabular-nums' }}>
        <Typography color="text.secondary">{`Protéines ${nutrition.protein} g`}</Typography>
        <Typography color="text.secondary">{`Lipides ${nutrition.fat} g`}</Typography>
        <Typography color="text.secondary">{`Glucides ${nutrition.carbs} g`}</Typography>
      </Box>
      {nutrition.skipped > 0 && (
        <Typography variant="caption" color="text.secondary" display="block" sx={{ mt: 0.5 }}>
          {nutrition.skipped === 1 ? '1 ingrédient compté à la pièce ou sans valeurs n’est pas inclus.' : `${nutrition.skipped} ingrédients comptés à la pièce ou sans valeurs ne sont pas inclus.`}
        </Typography>
      )}
    </Section>
  )
}
