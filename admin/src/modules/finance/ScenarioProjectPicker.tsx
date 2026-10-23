import { DndContext, KeyboardSensor, PointerSensor, closestCenter, useSensor, useSensors } from '@dnd-kit/core'
import type { DragEndEvent } from '@dnd-kit/core'
import { SortableContext, arrayMove, sortableKeyboardCoordinates, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import CloseIcon from '@mui/icons-material/Close'
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'
import { formatCents } from './accountTypes'
import type { Project } from './projectsApi'

interface RowProps {
  project: Project
  rank: number
  onRemove: (id: string) => void
}

function PickedRow({ project, rank, onRemove }: RowProps) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition } = useSortable({ id: project.id })

  return (
    <ListItem
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      data-testid="scenario-project"
      data-project={project.name}
      disablePadding
      sx={{ gap: 1, borderBottom: 1, borderColor: 'divider' }}
    >
      <IconButton
        ref={setActivatorNodeRef}
        size="small"
        sx={{ cursor: 'grab', touchAction: 'none' }}
        {...attributes}
        {...listeners}
        aria-label={`Déplacer ${project.name}, priorité ${rank}`}
      >
        <DragIndicatorIcon fontSize="small" />
      </IconButton>
      <Typography sx={{ flex: 1 }}>
        {rank}. {project.name}{' '}
        <Typography component="span" variant="body2" color="text.secondary">
          {formatCents(project.amountCents, project.currency)}
        </Typography>
      </Typography>
      <IconButton size="small" aria-label={`Retirer ${project.name}`} onClick={() => onRemove(project.id)}>
        <CloseIcon fontSize="small" />
      </IconButton>
    </ListItem>
  )
}

interface Props {
  /** Every project of the user. */
  projects: Project[]
  /** The ones the scenario keeps, in order. */
  selected: string[]
  onChange: (ids: string[]) => void
}

/** The projects a scenario keeps, picked from the user's list and moved by their handle (mouse or keyboard). */
export const ScenarioProjectPicker = ({ projects, selected, onChange }: Props) => {
  const byId = new Map(projects.map((project) => [project.id, project]))
  const picked = selected.flatMap((id) => (byId.has(id) ? [byId.get(id) as Project] : []))
  const left = projects.filter((project) => !selected.includes(project.id))
  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 5 } }), useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }))

  const onDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return
    const from = selected.indexOf(String(active.id))
    const to = selected.indexOf(String(over.id))
    if (from !== -1 && to !== -1) onChange(arrayMove(selected, from, to))
  }

  return (
    <Box>
      <Typography variant="subtitle2" sx={{ mb: 0.5 }}>
        Projets, par priorité
      </Typography>
      {picked.length === 0 && <Typography color="text.secondary">Aucun projet.</Typography>}
      <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
        <SortableContext items={picked.map((project) => project.id)} strategy={verticalListSortingStrategy}>
          <List component="ol" disablePadding aria-label="Projets du scénario">
            {picked.map((project, index) => (
              <PickedRow key={project.id} project={project} rank={index + 1} onRemove={(id) => onChange(selected.filter((candidate) => candidate !== id))} />
            ))}
          </List>
        </SortableContext>
      </DndContext>
      {left.length > 0 && (
        <Stack direction="row" sx={{ flexWrap: 'wrap', gap: 1, mt: 1 }}>
          {left.map((project) => (
            <Button key={project.id} size="small" variant="outlined" startIcon={<AddIcon />} onClick={() => onChange([...selected, project.id])}>
              {project.name}
            </Button>
          ))}
        </Stack>
      )}
    </Box>
  )
}
