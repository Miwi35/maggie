import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { formatCents, formatCentsRounded } from './accountTypes'
import { InvestmentChart } from './InvestmentChart'
import { InvestmentDialog } from './InvestmentDialog'
import { ganttMonthLabel, monthNumber } from './ProjectGantt'
import { removeInvestment, saveInvestment } from './projectsApi'
import type { InvestmentInput, PlannedProject, Project, ProjectInvestment } from './projectsApi'
import type { RenteSourceInvestment } from './useFinanceDashboard'
import { useLoanCurve } from './useLoanSimulation'

const numeric = { fontVariantNumeric: 'tabular-nums' } as const

const failureText = (error: unknown, fallback: string) => (error instanceof Error && error.message ? error.message : fallback)

const Figure = ({ id, label, value, hint }: { id: string; label: string; value: string; hint?: string }) => (
  <Box data-testid="investment-figure" data-figure={id}>
    <Typography variant="caption" color="text.secondary">
      {label}
    </Typography>
    <Typography variant="h6" sx={{ fontWeight: 300, ...numeric }}>
      {value}
    </Typography>
    {hint && (
      <Typography variant="caption" color="text.secondary">
        {hint}
      </Typography>
    )}
  </Box>
)

/** « Revente en juin 2028 · net 10 000 € · finance Voiture ». */
const resaleLine = (investment: ProjectInvestment, names: Map<string, string>, currency: string): string => {
  const resale = investment.resale
  if (!resale) return 'Aucune revente planifiée.'
  const target = resale.targetProjectId ? names.get(resale.targetProjectId) : undefined
  const where = resale.targetProjectId ? ` · finance ${target ?? 'un autre projet'}` : ' · accélère les projets en attente'

  return `Revente en ${ganttMonthLabel(resale.month)} · net ${formatCents(resale.netAmountCents, currency)}${where}`
}

/** « Loyers de nov. 2027 à mai 2028 », « Loyers dès nov. 2027 » (still rented at the end of the planning). */
const rentLine = (planned: PlannedProject | null): string | null => {
  const rent = planned?.investment
  if (!rent) return null
  if (rent.rentFromMonth === null) return 'Achat au-delà de 36 mois : pas encore de loyer au planning.'

  return rent.rentUntilMonth === null
    ? `Loyers perçus dès ${ganttMonthLabel(rent.rentFromMonth)}`
    : `Loyers perçus de ${ganttMonthLabel(rent.rentFromMonth)} à ${ganttMonthLabel(rent.rentUntilMonth)}`
}

/** The months held between the first rent and the last, when the planning dates the resale. */
const heldMonthsOf = (planned: PlannedProject | null): number | null => {
  const rent = planned?.investment
  if (!rent || rent.rentFromMonth === null || rent.rentUntilMonth === null) return null

  return Math.max(1, monthNumber(rent.rentUntilMonth) - monthNumber(rent.rentFromMonth) + 1)
}

/** « Rente comptée : 160 € / mois de loyer · indépendance + 9 points »; null when the projection gives no points. */
const renteCountedLine = (rente: RenteSourceInvestment | null | undefined): string | null => {
  if (!rente || rente.coveragePoints === null) return null

  return `Rente comptée : ${formatCentsRounded(rente.monthlyRentCents)} / mois de loyer · indépendance + ${rente.coveragePoints} point${rente.coveragePoints > 1 ? 's' : ''}`
}

interface ProjectInvestmentCardProps {
  project: Project
  /** Every project, to name the one a resale funds and to choose it. */
  projects: Project[]
  /** The project's line in the planning, for the dates the rent and the resale fall on. */
  planned: PlannedProject | null
  /** The investment's item of the independence projection (MAG-558), when it has one. */
  rente?: RenteSourceInvestment | null
  /** The project as the API answered the write. */
  onSaved: (project: Project) => void
}

/**
 * « Investissement · Box à louer » (MAG-557): what the project costs, the down payment, the loan, the rent
 * against the monthly payment, the planned resale and, month by month from the purchase, the capital still owed
 * against the rents received. Created, edited and removed here; the API refuses what does not add up.
 */
export const ProjectInvestmentCard = ({ project, projects, planned, rente, onSaved }: ProjectInvestmentCardProps) => {
  const investment = project.investment
  const [editing, setEditing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const names = new Map(projects.map((candidate) => [candidate.id, candidate.name]))
  const curve = useLoanCurve(investment?.loanId ?? undefined, JSON.stringify(investment))

  const save = async (input: InvestmentInput) => {
    onSaved(await saveInvestment(project.id, input))
    setFailure(null)
    setEditing(false)
  }

  const remove = async () => {
    setConfirming(false)
    try {
      onSaved(await removeInvestment(project.id))
      setFailure(null)
    } catch (error) {
      setFailure(failureText(error, "L'investissement n'a pas été supprimé."))
    }
  }

  const rent = rentLine(planned)
  const counted = renteCountedLine(rente)

  return (
    <Card variant="outlined" component="section" aria-label={`Investissement de ${project.name}`} data-testid="investment-card">
      <CardContent>
        <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', gap: 2, flexWrap: 'wrap', mb: 1 }}>
          <Typography variant="h6">{investment ? `Investissement · ${project.name}` : 'Investissement'}</Typography>
          <Stack direction="row" sx={{ gap: 1 }}>
            {investment ? (
              <>
                <Button size="small" onClick={() => setEditing(true)}>
                  Modifier l'investissement
                </Button>
                <Button size="small" color="error" onClick={() => setConfirming(true)}>
                  Supprimer l'investissement
                </Button>
              </>
            ) : (
              <Button size="small" variant="outlined" onClick={() => setEditing(true)}>
                Définir un investissement
              </Button>
            )}
          </Stack>
        </Stack>

        {failure && (
          <Alert severity="error" sx={{ mb: 1 }}>
            {failure}
          </Alert>
        )}

        {!investment && (
          <Typography variant="body2" color="text.secondary">
            Un investissement s'achète avec un apport et un prêt, rapporte un loyer et peut être revendu pour financer un autre projet.
          </Typography>
        )}

        {investment && (
          <Stack spacing={2}>
            <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))', gap: 2 }}>
              <Figure id="price" label="Prix" value={formatCents(investment.priceCents, project.currency)} />
              <Figure id="down-payment" label="Apport" value={formatCents(investment.downPaymentCents, project.currency)} />
              <Figure
                id="loan"
                label="Prêt"
                value={formatCents(Math.max(0, investment.priceCents - investment.downPaymentCents), project.currency)}
              />
              <Figure id="rent" label="Loyer" value={`${formatCents(investment.monthlyRentCents, project.currency)} / mois`} />
              <Figure id="payment" label="Mensualité" value={`${formatCents(investment.loanPaymentCents, project.currency)} / mois`} />
              <Figure
                id="balance"
                label="Loyer − mensualité"
                value={`${formatCents(investment.monthlyBalanceCents, project.currency)} / mois`}
                hint={investment.monthlyBalanceCents < 0 ? 'Le reste est à votre charge.' : undefined}
              />
            </Box>

            <Typography data-testid="investment-resale">{resaleLine(investment, names, project.currency)}</Typography>
            {rent && (
              <Typography variant="body2" color="text.secondary" data-testid="investment-rent-dates">
                {rent}
              </Typography>
            )}

            {counted && (
              <Typography variant="body2" color="text.secondary" data-testid="investment-rente-counted">
                {counted}
              </Typography>
            )}

            {curve && Array.isArray(curve.months) && curve.months.length > 0 && (
              <InvestmentChart
                remainingCents={curve.months.map((month) => month.remainingCents)}
                monthlyRentCents={investment.monthlyRentCents}
                heldMonths={heldMonthsOf(planned)}
                currency={project.currency}
              />
            )}
          </Stack>
        )}
      </CardContent>

      {editing && <InvestmentDialog project={project} projects={projects} onClose={() => setEditing(false)} onSubmit={save} />}

      <Dialog open={confirming} onClose={() => setConfirming(false)} maxWidth="xs" fullWidth>
        <DialogTitle>Supprimer l'investissement « {project.name} » ?</DialogTitle>
        <DialogContent>
          <Typography variant="body2">Le projet redevient un projet ordinaire : son prêt, son loyer et sa revente disparaissent.</Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirming(false)}>Annuler</Button>
          <Button color="error" variant="contained" onClick={remove}>
            Supprimer
          </Button>
        </DialogActions>
      </Dialog>
    </Card>
  )
}
