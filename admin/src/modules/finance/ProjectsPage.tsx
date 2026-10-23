import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import { Title, useNotify } from 'react-admin'
import { DndContext, closestCenter, PointerSensor, useSensor, useSensors } from '@dnd-kit/core'
import type { Announcements, DragEndEvent } from '@dnd-kit/core'
import { SortableContext, arrayMove, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Tab from '@mui/material/Tab'
import Tabs from '@mui/material/Tabs'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline'
import DragIndicatorIcon from '@mui/icons-material/DragIndicator'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import PaymentsOutlinedIcon from '@mui/icons-material/PaymentsOutlined'
import { useMercure } from '../../hooks/useMercure'
import { formatCents } from './accountTypes'
import { bareId } from './envelopeTree'
import { ProjectDialog } from './ProjectDialog'
import { ProjectFundingPanel } from './ProjectFundingPanel'
import { ProjectFundingChart } from './ProjectFundingChart'
import { ProjectGantt } from './ProjectGantt'
import { ProjectInvestmentCard } from './ProjectInvestmentCard'
import { ProjectOptions } from './ProjectOptions'
import {
  byPosition,
  createProject,
  deleteProject,
  fetchProjects,
  joinNames,
  patchProject,
  PROJECT_TOPIC,
  projectDateLabel,
  reorderProjects,
  toProject,
  updateProject,
  wellbeingLabel,
} from './projectsApi'
import type { Project, ProjectInput } from './projectsApi'
import { renteOfInvestment, useRenteSources } from './useRenteSources'
import { ScenariosPanel } from './ScenariosPanel'
import { useProjectPlanning } from './useProjectPlanning'

const PROJECT_TOPICS = [PROJECT_TOPIC]

const KEYBOARD_INSTRUCTIONS =
  'Espace ou Entrée pour saisir le projet, flèches haut et bas pour le déplacer, Espace ou Entrée pour le déposer, Échap pour annuler.'

const visuallyHidden = {
  position: 'absolute',
  width: 1,
  height: 1,
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
} as const

const failureText = (error: unknown, fallback: string) => (error instanceof Error && error.message ? error.message : fallback)

interface RowProps {
  project: Project
  rank: number
  total: number
  names: Map<string, string>
  picked: boolean
  opened: boolean
  onOpen: (project: Project) => void
  onHandleKeyDown: (event: KeyboardEvent, project: Project) => void
  onHandleBlur: (project: Project) => void
  registerHandle: (id: string, node: HTMLButtonElement | null) => void
  onEdit: (project: Project) => void
  onDelete: (project: Project) => void
}

/** Positions follow the order on screen: a Mercure update re-sorts by position. */
const renumbered = (projects: Project[]): Project[] => projects.map((project, position) => ({ ...project, position }))

const namesOf = (ids: string[], names: Map<string, string>) => ids.flatMap((id) => (names.has(id) ? [names.get(id) as string] : []))

function ProjectRow({ project, rank, total, names, picked, opened, onOpen, onHandleKeyDown, onHandleBlur, registerHandle, onEdit, onDelete }: RowProps) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, transition, isDragging } = useSortable({
    id: project.id,
  })
  const after = namesOf(project.dependsOn, names)
  const unmet = namesOf(project.unmetDependencies, names)

  return (
    <ListItem
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition, opacity: isDragging ? 0.5 : undefined }}
      data-testid="project-row"
      data-project={project.name}
      disablePadding
      sx={{
        gap: 1,
        py: 1,
        alignItems: 'flex-start',
        borderBottom: 1,
        borderColor: 'divider',
        bgcolor: picked || opened ? 'action.selected' : undefined,
      }}
    >
      <IconButton
        ref={(node: HTMLButtonElement | null) => {
          setActivatorNodeRef(node)
          registerHandle(project.id, node)
        }}
        size="small"
        sx={{ cursor: 'grab', touchAction: 'none' }}
        {...attributes}
        {...listeners}
        aria-label={`Déplacer ${project.name}, priorité ${rank} sur ${total}`}
        aria-pressed={picked}
        onKeyDown={(event: KeyboardEvent) => onHandleKeyDown(event, project)}
        onBlur={() => onHandleBlur(project)}
      >
        <DragIndicatorIcon fontSize="small" sx={{ color: 'action.active' }} />
      </IconButton>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Stack direction="row" sx={{ gap: 1, alignItems: 'center', flexWrap: 'wrap' }}>
          <Typography sx={{ fontWeight: 600 }}>
            {rank}. {project.name}
          </Typography>
          <Chip
            size="small"
            variant={project.wellbeingScore >= 4 ? 'filled' : 'outlined'}
            color={project.wellbeingScore >= 3 ? 'primary' : 'default'}
            label={`${wellbeingLabel(project.wellbeing)} · ${project.wellbeingScore}`}
            title="Bien-être"
          />
          {project.investment && <Chip size="small" variant="outlined" label="Investissement" data-testid="project-investment-chip" />}
        </Stack>
        <Typography variant="body2" color="text.secondary">
          {formatCents(project.amountCents, project.currency)} · {projectDateLabel(project)}
          {after.length > 0 && ` · après ${joinNames(after)}`}
        </Typography>
        {project.note && (
          <Typography variant="body2" color="text.secondary" sx={{ fontStyle: 'italic' }}>
            {project.note}
          </Typography>
        )}
        {unmet.length > 0 && (
          <Alert severity="warning" sx={{ mt: 1, py: 0 }}>
            Dépendance non respectée : {joinNames(unmet)} {unmet.length > 1 ? 'sont prévus' : 'est prévu'} après.
          </Alert>
        )}
      </Box>
      <IconButton size="small" aria-label={`Financement de ${project.name}`} aria-pressed={opened} onClick={() => onOpen(project)}>
        <PaymentsOutlinedIcon fontSize="small" />
      </IconButton>
      <IconButton size="small" aria-label={`Modifier ${project.name}`} onClick={() => onEdit(project)}>
        <EditOutlinedIcon fontSize="small" />
      </IconButton>
      <IconButton size="small" aria-label={`Supprimer ${project.name}`} onClick={() => onDelete(project)}>
        <DeleteOutlineIcon fontSize="small" />
      </IconButton>
    </ListItem>
  )
}

/**
 * The user's projects in their priority order, moved with the mouse (dnd-kit)
 * or the keyboard (the handle: Espace, flèches, Espace), created, changed and
 * deleted here (MAG-519).
 */
const ProjectsView = () => {
  const notify = useNotify()
  const { status: planningStatus, planning, reload: reloadPlanning } = useProjectPlanning()
  const { sources: renteSources, reload: reloadRenteSources } = useRenteSources()
  const [projects, setProjects] = useState<Project[]>([])
  const [status, setStatus] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [editing, setEditing] = useState<Project | 'new' | null>(null)
  const [toDelete, setToDelete] = useState<Project | null>(null)
  // The project whose sources and options are on screen, by id: it follows the list.
  const [openId, setOpenId] = useState<string | null>(null)
  // The project carried with the keyboard, and the order to go back to on Échap.
  const [picked, setPicked] = useState<{ id: string; original: Project[] } | null>(null)
  const pickedRef = useRef(picked)
  useLayoutEffect(() => {
    pickedRef.current = picked
  }, [picked])
  const [announcement, setAnnouncement] = useState('')
  const handles = useRef(new Map<string, HTMLButtonElement>())
  const current = useRef<Project[]>(projects)
  useEffect(() => {
    current.current = projects
  }, [projects])

  const reload = useCallback(async () => {
    try {
      setProjects(await fetchProjects())
      setStatus('ready')
    } catch {
      setStatus((previous) => (previous === 'ready' ? previous : 'failed'))
    }
  }, [])

  useEffect(() => {
    reload()
  }, [reload])

  // The payload leaves with the write, before the index has it: applied, not read back.
  useMercure(
    PROJECT_TOPICS,
    useCallback(
      (data?: string) => {
        let payload: Record<string, unknown>
        try {
          payload = JSON.parse(data ?? '')
        } catch {
          reload()
          return
        }
        const iri = payload?.['@id']
        if (typeof iri !== 'string') {
          reload()
          return
        }
        const id = bareId(iri)
        if (payload.deleted) {
          setProjects((previous) => previous.filter((project) => project.id !== id))
          return
        }
        if (current.current.some((project) => project.id === id)) {
          setProjects((previous) => byPosition(previous.map((project) => (project.id === id ? patchProject(project, payload) : project))))
          return
        }
        if (typeof payload.name === 'string' && 'amountCents' in payload) {
          setProjects((previous) =>
            previous.some((project) => project.id === id) ? previous : byPosition([...previous, toProject({ ...payload, id })]),
          )
          return
        }
        reload()
      },
      [reload],
    ),
  )

  // A row moved with the keyboard keeps the focus on its handle — before the
  // blur check below runs, since React may move the focused node to reorder.
  useLayoutEffect(() => {
    if (picked) handles.current.get(picked.id)?.focus()
  }, [picked, projects])

  const registerHandle = useCallback((id: string, node: HTMLButtonElement | null) => {
    if (node) handles.current.set(id, node)
    else handles.current.delete(id)
  }, [])

  const commitOrder = async (next: Project[], previous: Project[]) => {
    setProjects(renumbered(next))
    try {
      await reorderProjects(next.map((project) => project.id))
      reloadPlanning()
    } catch (error) {
      setProjects(previous)
      setAnnouncement("Le nouvel ordre n'a pas été enregistré : les projets reprennent leur place.")
      notify(failureText(error, "Le nouvel ordre des projets n'a pas été enregistré."), { type: 'error' })
    }
  }

  const sensors = useSensors(useSensor(PointerSensor, { activationConstraint: { distance: 5 } }))

  const nameOf = (id: string | number) => projects.find((project) => project.id === String(id))?.name ?? 'Le projet'
  const placeOf = (id: string | number) => projects.findIndex((project) => project.id === String(id)) + 1

  const announcements: Announcements = {
    onDragStart: ({ active }) => `${nameOf(active.id)} saisi, priorité ${placeOf(active.id)} sur ${projects.length}.`,
    onDragOver: ({ active, over }) => (over ? `${nameOf(active.id)} au-dessus de la priorité ${placeOf(over.id)}.` : undefined),
    onDragEnd: ({ active, over }) =>
      over ? `${nameOf(active.id)} déposé en priorité ${placeOf(over.id)}.` : `${nameOf(active.id)} déposé.`,
    onDragCancel: ({ active }) => `Déplacement annulé : ${nameOf(active.id)} reste à sa place.`,
  }

  const handleDragEnd = ({ active, over }: DragEndEvent) => {
    if (!over || active.id === over.id) return
    const from = projects.findIndex((project) => project.id === active.id)
    const to = projects.findIndex((project) => project.id === over.id)
    if (from === -1 || to === -1) return

    commitOrder(arrayMove(projects, from, to), projects)
  }

  const handleKeyDown = (event: KeyboardEvent, project: Project) => {
    const index = projects.findIndex((candidate) => candidate.id === project.id)
    const total = projects.length
    const isAction = event.key === ' ' || event.key === 'Enter'

    if (!picked) {
      if (!isAction) return
      event.preventDefault()
      setPicked({ id: project.id, original: projects })
      setAnnouncement(`${project.name} saisi, priorité ${index + 1} sur ${total}. ${KEYBOARD_INSTRUCTIONS}`)
      return
    }
    if (picked.id !== project.id) return

    if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      event.preventDefault()
      const target = index + (event.key === 'ArrowUp' ? -1 : 1)
      if (target < 0 || target >= total) return
      setProjects(renumbered(arrayMove(projects, index, target)))
      setAnnouncement(`${project.name} : priorité ${target + 1} sur ${total}.`)
    } else if (isAction) {
      event.preventDefault()
      setPicked(null)
      const moved = projects.some((candidate, position) => candidate.id !== picked.original[position]?.id)
      setAnnouncement(`${project.name} déposé en priorité ${index + 1} sur ${total}.`)
      if (moved) commitOrder(projects, picked.original)
    } else if (event.key === 'Escape') {
      event.preventDefault()
      cancelPick(project)
    }
  }

  const cancelPick = (project: Project) => {
    const original = pickedRef.current?.original
    if (!original) return
    setProjects(original)
    setPicked(null)
    setAnnouncement(`Déplacement annulé : ${project.name} reste à sa place.`)
  }

  // Leaving the handle while carrying the project drops nothing: it goes back,
  // as with Échap. Checked once the reorder has given the focus back.
  const handleBlur = (project: Project) => {
    if (pickedRef.current?.id !== project.id) return
    setTimeout(() => {
      if (pickedRef.current?.id !== project.id) return
      if (document.activeElement === handles.current.get(project.id)) return
      cancelPick(project)
    }, 0)
  }

  const save = async (input: ProjectInput) => {
    if (editing === 'new') {
      const created = await createProject(input)
      setProjects((previous) =>
        previous.some((project) => project.id === created.id)
          ? previous.map((project) => (project.id === created.id ? created : project))
          : byPosition([...previous, created]),
      )
    } else if (editing) {
      const updated = await updateProject(editing, input)
      setProjects((previous) => previous.map((project) => (project.id === updated.id ? updated : project)))
    }
    setEditing(null)
    reloadPlanning()
  }

  const saved = (project: Project) =>
    setProjects((previous) => previous.map((candidate) => (candidate.id === project.id ? { ...candidate, ...project } : candidate)))

  // An investment also changes the project its resale funds, and the planning: both are read again.
  const investmentSaved = (project: Project) => {
    saved(project)
    reload()
    reloadPlanning()
    reloadRenteSources()
  }

  const remove = async (project: Project) => {
    setToDelete(null)
    if (openId === project.id) setOpenId(null)
    const previous = projects
    setProjects((list) =>
      list
        .filter((candidate) => candidate.id !== project.id)
        .map((candidate) => ({
          ...candidate,
          dependsOn: candidate.dependsOn.filter((id) => id !== project.id),
          unmetDependencies: candidate.unmetDependencies.filter((id) => id !== project.id),
        })),
    )
    try {
      await deleteProject(project.id)
      reloadPlanning()
    } catch (error) {
      setProjects(previous)
      notify(failureText(error, `Le projet « ${project.name} » n'a pas été supprimé.`), { type: 'error' })
    }
  }

  const names = new Map(projects.map((project) => [project.id, project.name]))
  const open = projects.find((project) => project.id === openId) ?? null
  // Rebuilt when the sources or the amount change from elsewhere (Mercure): the form starts again from them.
  const detailKey = open ? JSON.stringify([open.id, open.amountCents, open.fundingSources, open.investment]) : ''

  return (
    <>
      <Title title="Finance · Projets" />
      <Box sx={{ display: 'flex', gap: 4, alignItems: 'flex-start', flexWrap: 'wrap' }}>
      <Box sx={{ flex: '1 1 420px', maxWidth: open ? 520 : 820, minWidth: 0 }}>
        <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 1, gap: 2 }}>
          <Typography variant="h6">Projets</Typography>
          <Button variant="contained" startIcon={<AddIcon />} onClick={() => setEditing('new')}>
            Nouveau projet
          </Button>
        </Stack>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Le premier passe avant les autres. Glissez un projet par sa poignée pour changer sa priorité, ou, au
          clavier, Espace sur la poignée puis les flèches.
        </Typography>

        <Box role="status" aria-live="polite" sx={visuallyHidden}>
          {announcement}
        </Box>

        {status === 'loading' && <Skeleton data-testid="projects-loading" height={64} />}
        {status === 'failed' && (
          <Alert
            severity="error"
            action={
              <Button color="inherit" size="small" onClick={() => reload()}>
                Réessayer
              </Button>
            }
          >
            Les projets n'ont pas pu être chargés.
          </Alert>
        )}
        {status === 'ready' && projects.length === 0 && (
          <Typography color="text.secondary">Aucun projet pour l'instant.</Typography>
        )}
        {status === 'ready' && projects.length > 0 && (
          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragEnd={handleDragEnd}
            accessibility={{ announcements, screenReaderInstructions: { draggable: KEYBOARD_INSTRUCTIONS } }}
          >
            <SortableContext items={projects.map((project) => project.id)} strategy={verticalListSortingStrategy}>
              <List component="ol" disablePadding aria-label="Projets par priorité">
                {projects.map((project, index) => (
                  <ProjectRow
                    key={project.id}
                    project={project}
                    rank={index + 1}
                    total={projects.length}
                    names={names}
                    picked={picked?.id === project.id}
                    opened={openId === project.id}
                    onOpen={(candidate) => setOpenId((previous) => (previous === candidate.id ? null : candidate.id))}
                    onHandleKeyDown={handleKeyDown}
                    onHandleBlur={handleBlur}
                    registerHandle={registerHandle}
                    onEdit={setEditing}
                    onDelete={setToDelete}
                  />
                ))}
              </List>
            </SortableContext>
          </DndContext>
        )}
      </Box>

      {open && (
        <Box component="section" aria-label={`Financement de ${open.name}`} sx={{ flex: '2 1 560px', minWidth: 0 }}>
          <Stack direction="row" sx={{ alignItems: 'baseline', justifyContent: 'space-between', mb: 2, gap: 2 }}>
            <Typography variant="h6">{open.name}</Typography>
            <Typography color="text.secondary">{formatCents(open.amountCents, open.currency)}</Typography>
          </Stack>
          <Box sx={{ mb: 3 }}>
            <ProjectInvestmentCard
              project={open}
              projects={projects}
              planned={planning?.projects.find((candidate) => candidate.projectId === open.id) ?? null}
              rente={renteOfInvestment(renteSources, open.id)}
              onSaved={investmentSaved}
            />
          </Box>
          <ProjectFundingPanel key={detailKey} project={open} names={names} onSaved={saved} />
          <Box sx={{ mt: 4 }}>
            <ProjectOptions key={detailKey} project={open} />
          </Box>
        </Box>
      )}
      </Box>

      {status === 'ready' && projects.length > 0 && !(planningStatus === 'ready' && planning?.isIncomeKnown === false) && (
        <Box sx={{ mt: 4 }} data-testid="project-planning">
          <Typography variant="h6" sx={{ mb: 1 }}>
            Planning
          </Typography>
          {planningStatus === 'loading' && <Skeleton data-testid="gantt-loading" height={120} />}
          {planningStatus === 'failed' && (
            <Alert
              severity="error"
              action={
                <Button color="inherit" size="small" onClick={() => reloadPlanning()}>
                  Réessayer
                </Button>
              }
            >
              Le planning n'a pas pu être chargé.
            </Alert>
          )}
          {planningStatus === 'ready' && planning && (
            <ProjectGantt planning={planning}>
              <ProjectFundingChart planning={planning} />
            </ProjectGantt>
          )}
        </Box>
      )}

      {editing !== null && (
        <ProjectDialog
          project={editing === 'new' ? undefined : editing}
          projects={projects}
          onClose={() => setEditing(null)}
          onSubmit={save}
        />
      )}

      <Dialog open={toDelete !== null} onClose={() => setToDelete(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Supprimer le projet « {toDelete?.name} » ?</DialogTitle>
        <DialogContent>
          <Typography variant="body2">Les projets qui l'attendaient ne l'attendront plus.</Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setToDelete(null)}>Annuler</Button>
          <Button color="error" variant="contained" onClick={() => toDelete && remove(toDelete)}>
            Supprimer
          </Button>
        </DialogActions>
      </Dialog>
    </>
  )
}

/**
 * Finance › Planification › Projets: the projects and their Gantt (MAG-519),
 * and the scenarios built on them (MAG-551), in a tab of their own.
 */
export const ProjectsPage = () => {
  const [tab, setTab] = useState<'projects' | 'scenarios'>('projects')

  return (
    <>
      <Tabs value={tab} onChange={(_, next) => setTab(next)} aria-label="Projets et scénarios" sx={{ mb: 3, borderBottom: 1, borderColor: 'divider' }}>
        <Tab value="projects" label="Projets" id="projects-tab" aria-controls="projects-panel" />
        <Tab value="scenarios" label="Scénarios" id="scenarios-tab" aria-controls="scenarios-panel" />
      </Tabs>
      <Box role="tabpanel" id={`${tab}-panel`} aria-labelledby={`${tab}-tab`}>
        {tab === 'projects' ? <ProjectsView /> : <ScenariosPanel />}
      </Box>
    </>
  )
}
