import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { useTheme } from '@mui/material/styles'
import ArrowForwardIcon from '@mui/icons-material/ArrowForward'
import LockOutlinedIcon from '@mui/icons-material/LockOutlined'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import { chartColor } from '../../design/tokens'
import { formatCents } from './accountTypes'
import { projectDateLabel } from './projectsApi'
import type { PlannedProject, ProjectPlanning } from './projectsApi'

export type GanttVariant = 'full' | 'compact'

/** The compact variant (the home tile, MAG-529) shows the first projects only. */
export const COMPACT_PROJECT_COUNT = 3

const LABEL_WIDTH: Record<GanttVariant, number> = { full: 168, compact: 112 }
const COLUMN_MIN: Record<GanttVariant, number> = { full: 22, compact: 10 }
const ROW_HEIGHT: Record<GanttVariant, number> = { full: 34, compact: 24 }
const BAR_HEIGHT: Record<GanttVariant, number> = { full: 14, compact: 10 }
const INITIALS = ['J', 'F', 'M', 'A', 'M', 'J', 'J', 'A', 'S', 'O', 'N', 'D']

/** `YYYY-MM` as a month count, so two months subtract. */
const monthNumber = (month: string): number => {
  const [year, number] = month.split('-').map(Number)

  return (year ?? 0) * 12 + (number ?? 1) - 1
}

/** « oct. 2026 » from `2026-10`. */
export const ganttMonthLabel = (month: string): string =>
  new Intl.DateTimeFormat('fr-FR', { month: 'short', year: 'numeric', timeZone: 'UTC' }).format(new Date(`${month}-01T00:00:00Z`))

interface BarProps {
  from: number
  to: number
  color: string
  tip: string
  testId: string
  project?: string
  height: number
  /** The bar goes on past the right edge. */
  runsOn?: boolean
}

function Bar({ from, to, color, tip, testId, project, height, runsOn = false }: BarProps) {
  const theme = useTheme()

  return (
    <Tooltip title={tip} enterTouchDelay={0} leaveTouchDelay={4000} placement="top" arrow>
      <Box
        tabIndex={0}
        data-testid={testId}
        data-project={project}
        data-from={from}
        data-to={to}
        sx={{
          gridColumn: `${from + 1} / ${to + 2}`,
          gridRow: 1,
          alignSelf: 'center',
          height,
          borderRadius: `${height / 2}px`,
          bgcolor: color,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'flex-end',
          color: theme.palette.getContrastText(color),
          '&:focus-visible': { outline: `2px solid ${theme.palette.text.primary}`, outlineOffset: 1 },
        }}
      >
        {runsOn && <ArrowForwardIcon data-testid="gantt-beyond" sx={{ fontSize: height }} />}
      </Box>
    </Tooltip>
  )
}

function Mark({ at, tip, project, size }: { at: number; tip: string; project: string; size: number }) {
  const theme = useTheme()

  return (
    <Tooltip title={tip} enterTouchDelay={0} leaveTouchDelay={4000} placement="top" arrow>
      <Box
        tabIndex={0}
        data-testid="gantt-purchase"
        data-project={project}
        data-at={at}
        sx={{
          gridColumn: at + 1,
          gridRow: 1,
          alignSelf: 'center',
          justifySelf: 'center',
          width: size,
          height: size,
          transform: 'rotate(45deg)',
          bgcolor: theme.palette.text.primary,
          border: `2px solid ${theme.palette.background.paper}`,
          zIndex: 1,
          '&:focus-visible': { outline: `2px solid ${theme.palette.text.primary}`, outlineOffset: 2 },
        }}
      />
    </Tooltip>
  )
}

interface RowProps {
  label: string
  variant: GanttVariant
  horizon: number
  /** Names the row for a test and a screen reader. */
  testId: string
  project?: string
  signs?: ReactNode
  children: ReactNode
}

function Row({ label, variant, horizon, testId, project, signs, children }: RowProps) {
  return (
    <Box
      data-testid={testId}
      data-project={project}
      sx={{
        display: 'grid',
        gridTemplateColumns: `${LABEL_WIDTH[variant]}px 1fr`,
        minHeight: ROW_HEIGHT[variant],
        borderBottom: 1,
        borderColor: 'divider',
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.5,
          pr: 1,
          position: 'sticky',
          left: 0,
          zIndex: 2,
          bgcolor: 'background.paper',
          minWidth: 0,
        }}
      >
        <Typography variant={variant === 'compact' ? 'caption' : 'body2'} noWrap sx={{ fontWeight: 600, minWidth: 0 }} title={label}>
          {label}
        </Typography>
        {signs}
      </Box>
      <Box sx={{ display: 'grid', gridTemplateColumns: `repeat(${horizon}, minmax(0, 1fr))`, position: 'relative' }}>
        <Box sx={{ gridColumn: 1, gridRow: 1, bgcolor: 'action.hover' }} />
        {children}
      </Box>
    </Box>
  )
}

function ProjectBars({ project, planning, variant }: { project: PlannedProject; planning: ProjectPlanning; variant: GanttVariant }) {
  const theme = useTheme()
  const mode = theme.palette.mode === 'dark' ? 'dark' : 'light'
  const horizon = planning.horizonMonths
  const start = monthNumber(planning.startMonth)
  const column = (month: string) => monthNumber(month) - start
  const clamp = (index: number) => Math.min(horizon - 1, Math.max(0, index))
  const height = BAR_HEIGHT[variant]
  const amounts = `Réuni ${formatCents(project.gatheredCents, planning.currency)} sur ${formatCents(project.amountCents, planning.currency)}`

  const savingFrom = clamp(column(project.savingFromMonth))
  const purchase = project.purchaseMonth === null ? null : column(project.purchaseMonth)
  const savingTo = purchase === null ? horizon - 1 : clamp(purchase)
  const savingTip =
    purchase === null
      ? `${project.name} · épargne dès ${ganttMonthLabel(project.savingFromMonth)} · achat au-delà de ${horizon} mois · ${amounts}`
      : `${project.name} · épargne de ${ganttMonthLabel(project.savingFromMonth)} à ${ganttMonthLabel(project.purchaseMonth as string)} · ${amounts}`

  const loan = project.loan
  const loanFrom = loan === null ? 0 : column(loan.startMonth)
  const loanEnd = loan === null || loan.endMonth === null ? null : column(loan.endMonth)
  const loanRunsOn = loan !== null && (loanEnd === null || loanEnd >= horizon)

  const deadline = project.dateMode === 'deadline' && project.targetOn ? column(project.targetOn.slice(0, 7)) : null

  return (
    <>
      {deadline !== null && deadline >= 0 && deadline < horizon && (
        <Tooltip title={`${project.name} · ${projectDateLabel(project)}`} enterTouchDelay={0} leaveTouchDelay={4000} placement="top" arrow>
          <Box
            tabIndex={0}
            data-testid="gantt-deadline"
            data-project={project.name}
            data-at={deadline}
            sx={{
              gridColumn: deadline + 1,
              gridRow: 1,
              borderRight: `2px dashed ${theme.palette.text.primary}`,
              zIndex: 1,
            }}
          />
        </Tooltip>
      )}
      <Bar
        testId="gantt-saving"
        project={project.name}
        from={Math.min(savingFrom, savingTo)}
        to={savingTo}
        color={chartColor(1, mode)}
        tip={savingTip}
        height={height}
        runsOn={purchase === null}
      />
      {purchase !== null && purchase < horizon && (
        <Mark
          at={Math.max(0, purchase)}
          project={project.name}
          size={height}
          tip={`${project.name} · achat en ${ganttMonthLabel(project.purchaseMonth as string)} · ${amounts}`}
        />
      )}
      {loan !== null && loanFrom < horizon && (
        <Bar
          testId="gantt-loan"
          project={project.name}
          from={clamp(loanFrom)}
          to={loanEnd === null ? horizon - 1 : clamp(loanEnd)}
          color={chartColor(2, mode)}
          tip={`${project.name} · emprunt dès ${ganttMonthLabel(loan.startMonth)}${
            loan.endMonth === null ? '' : ` jusqu'en ${ganttMonthLabel(loan.endMonth)}`
          } · ${formatCents(loan.monthlyPaymentCents, planning.currency)} par mois`}
          height={height}
          runsOn={loanRunsOn}
        />
      )}
    </>
  )
}

function Axis({ planning, variant }: { planning: ProjectPlanning; variant: GanttVariant }) {
  const start = monthNumber(planning.startMonth)

  return (
    <Box data-testid="gantt-axis" sx={{ display: 'grid', gridTemplateColumns: `${LABEL_WIDTH[variant]}px 1fr`, borderBottom: 1, borderColor: 'divider' }}>
      <Box sx={{ position: 'sticky', left: 0, zIndex: 2, bgcolor: 'background.paper' }} />
      <Box sx={{ display: 'grid', gridTemplateColumns: `repeat(${planning.horizonMonths}, minmax(0, 1fr))` }}>
        {Array.from({ length: planning.horizonMonths }, (_, index) => {
          const total = start + index
          const month = total % 12
          const year = Math.floor(total / 12)
          const label = `${year}-${String(month + 1).padStart(2, '0')}`

          return (
            <Box
              key={label}
              data-testid={index === 0 ? 'gantt-today' : 'gantt-month'}
              data-month={label}
              aria-current={index === 0 ? 'date' : undefined}
              aria-label={ganttMonthLabel(label)}
              sx={{ textAlign: 'center', lineHeight: 1.2, py: 0.5, bgcolor: index === 0 ? 'action.hover' : undefined, fontWeight: index === 0 ? 700 : 400 }}
            >
              <Typography component="span" sx={{ display: 'block', fontSize: 10, height: 12, whiteSpace: 'nowrap', overflow: 'visible' }} color="text.secondary">
                {index === 0 || month === 0 ? year : ''}
              </Typography>
              <Typography component="span" variant="caption" sx={{ display: 'block', fontWeight: 'inherit' }}>
                {INITIALS[month]}
              </Typography>
            </Box>
          )
        })}
      </Box>
    </Box>
  )
}

interface ProjectGanttProps {
  planning: ProjectPlanning
  variant?: GanttVariant
}

/**
 * The projects over the next 36 months (MAG-527), the order of the planning
 * (MAG-526): a « Matelas » line while the safety cushion builds or recharges,
 * then one line per project — saving bar, purchase mark, loan bar. A project
 * moved later is a fact drawn, not an alert: its bar runs on, in the same
 * colours. The compact variant (home tile, MAG-529) keeps the first three
 * projects and drops the axis.
 */
export function ProjectGantt({ planning, variant = 'full' }: ProjectGanttProps) {
  const theme = useTheme()
  if (!planning.isIncomeKnown || planning.projects.length === 0) return null

  const horizon = planning.horizonMonths
  const projects = variant === 'compact' ? planning.projects.slice(0, COMPACT_PROJECT_COUNT) : planning.projects
  const cushion = planning.cushion
  const showCushion = variant === 'full' && cushion.state !== 'complete'
  const cushionEnd = cushion.completeMonth === null ? horizon - 1 : Math.min(horizon - 1, Math.max(0, monthNumber(cushion.completeMonth) - monthNumber(planning.startMonth)))
  const cushionTip =
    cushion.completeMonth === null
      ? `Matelas · complet au-delà de ${horizon} mois`
      : `Matelas · ${cushion.state === 'building' ? 'constitué' : 'rechargé'} en ${ganttMonthLabel(cushion.completeMonth)}`

  return (
    <Box role="group" aria-label={`Planning des projets sur ${horizon} mois`} data-testid="project-gantt" data-variant={variant} sx={{ overflowX: 'auto' }}>
      <Box sx={{ minWidth: LABEL_WIDTH[variant] + horizon * COLUMN_MIN[variant] }}>
        {variant === 'full' && <Axis planning={planning} variant={variant} />}
        {showCushion && (
          <Row label="Matelas" variant={variant} horizon={horizon} testId="gantt-cushion-row">
            <Bar
              testId="gantt-cushion"
              from={0}
              to={cushionEnd}
              color={theme.palette.grey[theme.palette.mode === 'dark' ? 600 : 500]}
              tip={cushionTip}
              height={BAR_HEIGHT[variant]}
              runsOn={cushion.completeMonth === null}
            />
          </Row>
        )}
        {projects.map((project) => (
          <Row
            key={project.projectId}
            label={project.name}
            variant={variant}
            horizon={horizon}
            testId="gantt-row"
            project={project.name}
            signs={
              <>
                {project.dateMode === 'fixed' && (
                  <LockOutlinedIcon data-testid="gantt-fixed" titleAccess="Date fixe" sx={{ fontSize: 14, color: 'text.secondary' }} />
                )}
                {project.unmetDependencies.length > 0 && (
                  <WarningAmberIcon
                    data-testid="gantt-unmet"
                    titleAccess="Dépendance non respectée"
                    sx={{ fontSize: 14, color: 'warning.main' }}
                  />
                )}
              </>
            }
          >
            <ProjectBars project={project} planning={planning} variant={variant} />
          </Row>
        ))}
      </Box>
    </Box>
  )
}
