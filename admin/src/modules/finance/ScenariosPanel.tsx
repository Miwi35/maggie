import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Skeleton from '@mui/material/Skeleton'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import { fetchPlacements } from './placementsApi'
import type { Placement } from './placementsApi'
import { fetchProjects } from './projectsApi'
import type { Project } from './projectsApi'
import { ScenarioCard } from './ScenarioCard'
import { ScenarioComparison } from './ScenarioComparison'
import { ScenarioCurrentBanner } from './ScenarioCurrentBanner'
import { ScenarioEditor } from './ScenarioEditor'
import {
  clearCurrent,
  createScenario,
  deleteScenario,
  duplicateScenario,
  fetchLoanChoices,
  isAdvisor,
  makeCurrent,
  MAX_COMPARED,
  updateScenario,
} from './scenariosApi'
import type { LoanChoice, Scenario, ScenarioInput } from './scenariosApi'
import { useGapsAgainstCurrent, useScenarioComparison, useScenarios } from './useScenarios'

interface EditorState {
  /** Absent for a new scenario. */
  scenario?: Scenario
  projects: Project[]
  placements: Placement[]
  loans: LoanChoice[]
}

/** Lists that fail to load leave the editor without those choices rather than closed. */
const orEmpty = <T,>(read: Promise<T[]>): Promise<T[]> => read.catch(() => [])

const messageOf = (error: unknown, fallback: string) => (error instanceof Error && error.message ? error.message : fallback)

const DEFAULT_COMPARED = 4

/**
 * Finance › Planification › Projets › Scénarios (MAG-551): the three offered
 * scenarios, the user's own and the advisor's suggestions as cards, the one
 * « courant », and the chosen ones compared on one chart and one table.
 */
export const ScenariosPanel = () => {
  const { status, scenarios, version, reload } = useScenarios()
  const [failure, setFailure] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [editor, setEditor] = useState<EditorState | null>(null)
  const [toDelete, setToDelete] = useState<Scenario | null>(null)
  // null: the user has not chosen, the current scenario and the first others are compared.
  const [chosen, setChosen] = useState<string[] | null>(null)

  const current = scenarios.find((scenario) => scenario.current) ?? null
  const known = new Set(scenarios.map((scenario) => scenario.id))
  const defaults = [...(current ? [current] : []), ...scenarios.filter((scenario) => !scenario.current)].map((scenario) => scenario.id).slice(0, DEFAULT_COMPARED)
  const comparedIds = (chosen ?? defaults).filter((id) => known.has(id))
  const comparison = useScenarioComparison(comparedIds, version)
  const gaps = useGapsAgainstCurrent(
    current?.id ?? null,
    scenarios.filter((scenario) => !scenario.current).map((scenario) => scenario.id),
    version,
  )

  const act = async (work: () => Promise<unknown>) => {
    setBusy(true)
    setFailure(null)
    try {
      await work()
      await reload()
    } catch (error) {
      setFailure(messageOf(error, "L'opération n'a pas abouti."))
    } finally {
      setBusy(false)
    }
  }

  const toggleCompare = (scenario: Scenario) =>
    setChosen(comparedIds.includes(scenario.id) ? comparedIds.filter((id) => id !== scenario.id) : [...comparedIds, scenario.id])

  const openEditor = async (scenario?: Scenario) => {
    setFailure(null)
    try {
      const [projects, placements, loans] = await Promise.all([fetchProjects(), orEmpty(fetchPlacements()), orEmpty(fetchLoanChoices())])
      setEditor({ scenario, projects, placements, loans })
    } catch (error) {
      setFailure(messageOf(error, 'Les projets ne peuvent pas être chargés.'))
    }
  }

  const save = async (input: ScenarioInput) => {
    if (editor?.scenario) await updateScenario(editor.scenario.id, input)
    else await createScenario(input)
    setEditor(null)
    await reload()
  }

  const confirmDelete = async () => {
    const target = toDelete
    setToDelete(null)
    if (target) await act(() => deleteScenario(target.id))
  }

  const advisors = scenarios.filter(isAdvisor)
  const others = scenarios.filter((scenario) => !isAdvisor(scenario))

  const card = (scenario: Scenario) => (
    <ScenarioCard
      key={scenario.id}
      scenario={scenario}
      gap={current ? gaps[scenario.id] : undefined}
      compared={comparedIds.includes(scenario.id)}
      comparable={comparedIds.length < MAX_COMPARED}
      busy={busy}
      onToggleCompare={toggleCompare}
      onMakeCurrent={(target) => act(() => makeCurrent(target.id))}
      onDuplicate={(target) => act(() => duplicateScenario(target.id))}
      onEdit={openEditor}
      onDelete={(target) => (isAdvisor(target) ? act(() => deleteScenario(target.id)) : setToDelete(target))}
    />
  )

  return (
    <Box data-testid="scenarios-panel">
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', mb: 2, gap: 2, flexWrap: 'wrap' }}>
        <Typography variant="h6">Scénarios</Typography>
        <Button variant="contained" startIcon={<AddIcon />} onClick={() => openEditor()}>
          Scénario
        </Button>
      </Stack>

      {failure && (
        <Alert severity="error" onClose={() => setFailure(null)} sx={{ mb: 2 }}>
          {failure}
        </Alert>
      )}

      {status === 'loading' && <Skeleton data-testid="scenarios-loading" height={120} />}
      {status === 'failed' && (
        <Alert
          severity="error"
          action={
            <Button color="inherit" size="small" onClick={() => reload()}>
              Réessayer
            </Button>
          }
        >
          Les scénarios n'ont pas pu être chargés.
        </Alert>
      )}
      {status === 'ready' && scenarios.length === 0 && <Typography color="text.secondary">Aucun scénario pour l'instant.</Typography>}
      {status === 'ready' && scenarios.length > 0 && (
        <Stack spacing={3}>
          <ScenarioCurrentBanner
            current={current}
            scenarios={scenarios}
            busy={busy}
            onMakeCurrent={(target) => act(() => makeCurrent(target.id))}
            onClear={() => act(clearCurrent)}
          />
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))' }}>
            {advisors.map(card)}
            {others.map(card)}
          </Box>
          <ScenarioComparison status={comparison.status} comparison={comparison.comparison} error={comparison.error} />
        </Stack>
      )}

      {editor && (
        <ScenarioEditor
          scenario={editor.scenario}
          projects={editor.projects}
          placements={editor.placements}
          loans={editor.loans}
          onClose={() => setEditor(null)}
          onSubmit={save}
        />
      )}

      <Dialog open={toDelete !== null} onClose={() => setToDelete(null)} maxWidth="xs" fullWidth>
        <DialogTitle>Supprimer le scénario « {toDelete?.name} » ?</DialogTitle>
        <DialogContent>
          <Typography variant="body2">Les projets ne sont pas modifiés.</Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setToDelete(null)}>Annuler</Button>
          <Button color="error" variant="contained" onClick={confirmDelete}>
            Supprimer
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  )
}
