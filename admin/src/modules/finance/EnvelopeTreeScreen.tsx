import { useState } from 'react'
import type { KeyboardEvent } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutline'
import WarningAmberIcon from '@mui/icons-material/WarningAmber'
import { useGetList, useNotify } from 'react-admin'
import type { RaRecord } from 'react-admin'
import { formatCents } from './accountTypes'
import { consumedPercent } from './budgetModes'
import type { CategoryNode } from './categoryTree'
import { EnvelopeNodePane } from './EnvelopeNodePane'
import { bareId, buildEnvelopeTree, consumptionState, outsideEntries, sameSelection } from './envelopeTree'
import type { Selection } from './envelopeTree'
import type { BudgetLine, BudgetStatus, EnvelopeConsumption, OneOffBudgetLine } from './useBudgetStatus'

const INDENT = 2.5

/** Overspent is red, overcommitted orange — an icon and a word as well, never the colour alone. */
const NodeState = ({ line }: { line: EnvelopeConsumption }) => {
  const state = consumptionState(line)
  if (state === 'ok') return null

  const overspent = state === 'overspent'

  return (
    <Stack direction="row" alignItems="center" spacing={0.5} color={overspent ? 'error.main' : 'warning.main'}>
      {overspent ? <ErrorOutlineIcon fontSize="inherit" aria-hidden /> : <WarningAmberIcon fontSize="inherit" aria-hidden />}
      <Typography variant="caption" data-testid="envelope-node-state" data-state={state}>
        {overspent ? 'Dépassement' : 'Surengagement'}
      </Typography>
    </Stack>
  )
}

const NodeConsumption = ({ line }: { line: EnvelopeConsumption }) => {
  const state = consumptionState(line)

  return (
    <Box sx={{ width: '100%' }}>
      <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={1}>
        <NodeState line={line} />
        <Typography
          variant="body2"
          color={state === 'overspent' ? 'error.main' : 'text.secondary'}
          sx={{ ml: 'auto', whiteSpace: 'nowrap' }}
          data-testid="envelope-node-amount"
        >
          {formatCents(line.consumedCents, line.currency)} / {formatCents(line.amountCents, line.currency)}
        </Typography>
      </Stack>
      <LinearProgress
        variant="determinate"
        value={consumedPercent(line.consumedCents, line.amountCents)}
        color={state === 'overspent' ? 'error' : state === 'overcommitted' ? 'warning' : 'primary'}
        sx={{ height: 4, borderRadius: 2, mt: 0.5 }}
        aria-hidden
      />
    </Box>
  )
}

const rowSx = (selected: boolean, indent: number) => ({
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'flex-start',
  gap: 0.25,
  py: 1,
  pr: 1.5,
  pl: 1.5 + indent * INDENT,
  borderRadius: 1,
  cursor: 'pointer',
  bgcolor: selected ? 'action.selected' : 'transparent',
  '&:hover': { bgcolor: selected ? 'action.selected' : 'action.hover' },
  '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: -2 },
})

const onActivate = (select: () => void) => (event: KeyboardEvent<HTMLElement>) => {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    select()
  }
}

export type BudgetView = 'month' | 'outside'

interface EnvelopeTreeScreenProps {
  year: number
  month: number
  /** « Mois »: the category tree. « Hors budget »: the annual and one-off envelopes, apart from the month. */
  view?: BudgetView
  status: BudgetStatus | null
  loading: boolean
  /** Reads the budget status again. */
  refresh: () => void | Promise<void>
}

const hasNoEnvelope = (status: BudgetStatus | null): boolean =>
  status != null &&
  status.budgets.length === 0 &&
  (status.outsideMonthlyBudget?.annual.length ?? 0) === 0 &&
  (status.outsideMonthlyBudget?.oneOff.length ?? 0) === 0

/**
 * The envelopes of a period as a tree of categories (or, apart, the annual and
 * one-off ones), and the editor of the one selected — shown only once one is.
 * Nothing here is a rule of its own: the figures are the budget status', the
 * writes are the envelopes' ordinary ones.
 */
export const EnvelopeTreeScreen = ({ year, month, view = 'month', status, loading, refresh }: EnvelopeTreeScreenProps) => {
  const [selection, setSelection] = useState<Selection | null>(null)
  const notify = useNotify()
  const { data: categories = [] } = useGetList<CategoryNode & RaRecord>('categories', {
    pagination: { page: 1, perPage: 200 },
    sort: { field: 'name', order: 'ASC' },
  })

  const rows = buildEnvelopeTree(categories, status)
  const outside = outsideEntries(status)

  const isSelected = (candidate: Selection) => sameSelection(selection, candidate)

  const onSaved = (next: Selection) => {
    setSelection(next)
    refresh()
  }

  const onDeleted = (line: BudgetLine | OneOffBudgetLine) => {
    // A monthly envelope leaves its node in the tree, now without one.
    setSelection(line.mode === 'monthly' && 'categoryId' in line ? { kind: 'category', categoryId: line.categoryId } : null)
    refresh()
  }

  return (
    <Box
      sx={{
        display: 'grid',
        gap: 2,
        gridTemplateColumns: { xs: '1fr', md: selection ? 'minmax(320px, 2fr) 3fr' : '1fr' },
        alignItems: 'start',
      }}
      data-testid="envelope-tree-screen"
    >
      <Card>
        <CardContent>
          {loading && !status && <LinearProgress sx={{ mb: 1 }} />}

          {view === 'month' && (
            <Box role="tree" aria-label="Catégories et enveloppes" data-testid="envelope-tree">
              {rows.map(({ category, depth, line }) => {
                const candidate: Selection = { kind: 'category', categoryId: bareId(category.id) }
                const selected = isSelected(candidate)
                const select = () => setSelection(candidate)

                return (
                  <Box
                    key={category.id}
                    role="treeitem"
                    aria-level={depth}
                    aria-selected={selected}
                    tabIndex={0}
                    data-testid="envelope-node"
                    data-category={category.name}
                    data-depth={depth}
                    data-has-envelope={line ? 'true' : 'false'}
                    onClick={select}
                    onKeyDown={onActivate(select)}
                    sx={rowSx(selected, depth - 1)}
                  >
                    <Typography variant="body2" sx={{ fontWeight: line ? 600 : 400 }}>
                      {category.name}
                    </Typography>
                    {line ? (
                      <NodeConsumption line={line} />
                    ) : (
                      <Typography variant="caption" color="text.secondary">
                        Sans enveloppe
                      </Typography>
                    )}
                  </Box>
                )
              })}
              {rows.length === 0 && !hasNoEnvelope(status) && (
                <Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>
                  Aucune catégorie
                </Typography>
              )}
              {hasNoEnvelope(status) && (
                <Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>
                  Aucune enveloppe pour l&apos;instant
                </Typography>
              )}
            </Box>
          )}

          {view === 'outside' && (
            <Box data-testid="envelope-outside">
              <Box role="tree" aria-label="Enveloppes hors budget mensuel">
                {outside.map((entry) => {
                  const candidate: Selection = { kind: 'envelope', envelopeId: entry.id }
                  const selected = isSelected(candidate)
                  const select = () => setSelection(candidate)

                  return (
                    <Box
                      key={entry.id}
                      role="treeitem"
                      aria-selected={selected}
                      tabIndex={0}
                      data-testid="envelope-outside-node"
                      data-category={entry.label}
                      data-kind={entry.kind}
                      onClick={select}
                      onKeyDown={onActivate(select)}
                      sx={rowSx(selected, 0)}
                    >
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {entry.label}
                        <Typography component="span" variant="caption" color="text.secondary" sx={{ ml: 1 }}>
                          {entry.kind === 'annual' ? `Année ${year}` : 'Ponctuelle'}
                        </Typography>
                      </Typography>
                      <NodeConsumption line={entry.line} />
                    </Box>
                  )
                })}
                {outside.length === 0 && status && (
                  <Typography variant="body2" color="text.secondary" sx={{ py: 1 }}>
                    Aucune enveloppe pour l&apos;instant
                  </Typography>
                )}
              </Box>
              <Button
                size="small"
                startIcon={<AddIcon />}
                onClick={() => setSelection({ kind: 'new-one-off' })}
                sx={{ mt: 0.5 }}
              >
                Nouvelle ponctuelle
              </Button>
            </Box>
          )}
        </CardContent>
      </Card>

      {selection && (
        <Card>
          <CardContent>
            <EnvelopeNodePane
              selection={selection}
              status={status}
              loading={loading}
              categories={categories}
              year={year}
              month={month}
              onSaved={onSaved}
              onDeleted={onDeleted}
              onSplitSaved={() => {
                notify('Répartition enregistrée', { type: 'info' })
                refresh()
              }}
            />
          </CardContent>
        </Card>
      )}
    </Box>
  )
}
