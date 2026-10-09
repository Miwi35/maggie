import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import LinearProgress from '@mui/material/LinearProgress'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import { useGetList, useRecordContext } from 'react-admin'
import { useWatch } from 'react-hook-form'
import { FormSection } from '../../components/form/FormSection'
import { Amount } from './AmountField'
import { idOf } from './categorizationRules'
import { useRulePreviewContext } from './rulePreviewContext'
import {
  useCategorizationRulePreview,
  type RulePreviewCriteria,
  type RulePreviewMatch,
} from './useCategorizationRulePreview'

const NO_CATEGORY = 'Sans catégorie'

const frenchDay = (iso: string) => `${iso.slice(8, 10)}/${iso.slice(5, 7)}/${iso.slice(0, 4)}`

const textOrNull = (value: unknown) => (typeof value === 'string' && value !== '' ? value : null)
const centsOrNull = (value: unknown) => (typeof value === 'number' ? value : null)

interface PreviewRowProps {
  match: RulePreviewMatch
  categoryNames: Record<string, string>
  newCategoryName: string | null
}

const PreviewRow = ({ match, categoryNames, newCategoryName }: PreviewRowProps) => {
  const current = match.currentCategoryId
    ? (categoryNames[match.currentCategoryId] ?? NO_CATEGORY)
    : NO_CATEGORY

  return (
    <TableRow
      data-would-change={match.wouldChange}
      sx={match.wouldChange ? { bgcolor: 'action.selected' } : { color: 'text.secondary' }}
    >
      <TableCell sx={{ whiteSpace: 'nowrap' }}>{frenchDay(match.bookedAt)}</TableCell>
      <TableCell>{match.label}</TableCell>
      <TableCell sx={{ minWidth: 100 }}>
        <Amount cents={match.amountCents} signed />
      </TableCell>
      <TableCell sx={{ fontWeight: match.wouldChange ? 600 : undefined }}>
        {match.wouldChange ? `${current} → ${newCategoryName ?? 'cette catégorie'}` : current}
      </TableCell>
    </TableRow>
  )
}

/**
 * What the rule would catch, live: the sentence of the summary says what the
 * rule means, this says what it does to the history before it is saved.
 */
export const RulePreviewPanel = () => {
  const values = useWatch() as Record<string, unknown>
  const record = useRecordContext()
  const { report } = useRulePreviewContext()
  const { data: categories } = useGetList('categories', {
    pagination: { page: 1, perPage: 200 },
  })

  const categoryNames = Object.fromEntries(
    (categories ?? []).map((category) => [idOf(category.id as string), category.name as string]),
  )

  const criteria: RulePreviewCriteria = {
    labelPattern: typeof values.labelPattern === 'string' ? values.labelPattern : '',
    matchType: textOrNull(values.matchType) ?? 'contains',
    direction: textOrNull(values.direction) ?? 'any',
    minAmountCents: centsOrNull(values.minAmountCents),
    maxAmountCents: centsOrNull(values.maxAmountCents),
    categoryId: typeof values.category === 'string' && values.category ? idOf(values.category) : null,
    priority: typeof values.priority === 'number' ? values.priority : 0,
    isActive: values.isActive !== false,
    ruleId: typeof record?.id === 'string' ? idOf(record.id) : null,
  }

  const { status, result, error } = useCategorizationRulePreview(criteria)
  const changeCount = result?.changeCount ?? null

  useEffect(() => {
    report(changeCount)
  }, [report, changeCount])

  const newCategoryName = criteria.categoryId ? (categoryNames[criteria.categoryId] ?? null) : null

  return (
    <Box sx={{ width: '100%' }} aria-busy={status === 'loading'}>
      <FormSection
        title="Transactions trouvées"
        description="Mises à jour pendant la saisie, sans rien modifier."
      />

      {status === 'idle' && (
        <Alert severity="info" variant="outlined">
          Saisissez le texte à reconnaître pour voir les transactions concernées.
        </Alert>
      )}

      {status === 'error' && (
        <Alert severity="warning" variant="outlined">
          {error} Vous pouvez quand même enregistrer la règle.
        </Alert>
      )}

      {status === 'loading' && !result && <LinearProgress />}

      {result && result.total === 0 && (
        <Alert severity="info" variant="outlined">
          Aucune transaction trouvée
        </Alert>
      )}

      {result && result.total > 0 && (
        <>
          {status === 'loading' && <LinearProgress sx={{ mb: 1 }} />}
          <Typography variant="body2" sx={{ mb: 1 }}>
            {result.total} transaction(s) trouvée(s)
            {result.total > result.matches.length &&
              ` — ${result.matches.length} premières sur ${result.total}`}
            {criteria.categoryId
              ? ` · ${result.changeCount} changeraient de catégorie`
              : ' · choisissez une catégorie pour voir celles qui changeraient'}
          </Typography>
          <Box sx={{ overflowX: 'auto' }}>
            <Table size="small">
              <TableHead>
                <TableRow>
                  <TableCell>Date</TableCell>
                  <TableCell>Libellé</TableCell>
                  <TableCell align="right">Montant</TableCell>
                  <TableCell>Catégorie</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {result.matches.map((match) => (
                  <PreviewRow
                    key={match.transactionId}
                    match={match}
                    categoryNames={categoryNames}
                    newCategoryName={newCategoryName}
                  />
                ))}
              </TableBody>
            </Table>
          </Box>
        </>
      )}
    </Box>
  )
}
