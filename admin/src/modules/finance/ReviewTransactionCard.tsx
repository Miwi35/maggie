import { useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { useGetList } from 'react-admin'
import type { RaRecord } from 'react-admin'
import { formatCents } from './accountTypes'
import { categoryLabel } from './categoryTree'
import type { CategoryNode } from './categoryTree'
import { CreateFilterDialog } from './CreateFilterDialog'
import { Qualification } from './Qualification'
import { NO_QUALIFICATION } from './qualificationKinds'
import type { QualificationValue } from './qualificationKinds'
import { formatDay } from './recurringOperations'
import { SATISFACTION_CHOICES } from './satisfaction'
import type { Satisfaction } from './satisfaction'
import type { ReviewItem } from './useReviewQueue'

/** « Changer » or « Corriger »: a category chosen by hand, and what goes with it. */
const CategoryDialog = ({
  title,
  submitLabel,
  item,
  busy,
  onSave,
  onClose,
}: {
  title: string
  submitLabel: string
  item: ReviewItem
  busy: boolean
  onSave: (category: string, categoryPath: string | null, qualification: QualificationValue) => void
  onClose: () => void
}) => {
  const [category, setCategory] = useState<string | null>(null)
  const [qualification, setQualification] = useState<QualificationValue>(NO_QUALIFICATION)
  // The same list the picker reads (react-query shares it): the chosen category's path, for the card.
  const { data = [] } = useGetList<CategoryNode & RaRecord>('categories', {
    pagination: { page: 1, perPage: 200 },
    sort: { field: 'name', order: 'ASC' },
  })
  const pathOf = (id: string) => categoryLabel(data.find((c) => c.id === id)) || null

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm" aria-labelledby="review-category-title">
      <DialogTitle id="review-category-title">{title}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          {item.label} · {formatCents(item.amountCents, item.currency)}
        </Typography>
        <Qualification
          category={category}
          qualification={qualification}
          onChange={(next) => {
            setCategory(next.category)
            setQualification(next.qualification)
          }}
          sense={item.amountCents > 0 ? 'in' : 'out'}
        />
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Annuler</Button>
        <Button
          variant="contained"
          disabled={busy || category === null}
          onClick={() => category && onSave(category, pathOf(category), qualification)}
        >
          {submitLabel}
        </Button>
      </DialogActions>
    </Dialog>
  )
}

export interface ReviewTransactionCardProps {
  item: ReviewItem
  position: number
  total: number
  busy: boolean
  /** « Accepter »: Maggie's suggestion, then the next line. */
  onAccept: () => void
  /** « Changer » (unrecognised) or « Corriger » (recognised): a category by hand, then the next line. */
  onCategory: (category: string, categoryPath: string | null, qualification: QualificationValue) => Promise<boolean>
  /** « Créer un filtre » (a rule, as on À qualifier): the rule written qualifies the line, then the next line. */
  onOperationCreated: () => void
  onJudge: (verdict: Satisfaction) => void
}

/**
 * One line of the review's queue, alone on the screen: what it is on one side, what
 * to do with it on the other (one column under `md`). An unrecognised line gets
 * Maggie's suggestion; a recognised one shows what its operation filed it under —
 * the footer's « Valider » checks it.
 */
export const ReviewTransactionCard = ({
  item,
  position,
  total,
  busy,
  onAccept,
  onCategory,
  onOperationCreated,
  onJudge,
}: ReviewTransactionCardProps) => {
  const [choosing, setChoosing] = useState(false)
  const [creating, setCreating] = useState(false)
  const income = item.amountCents > 0

  return (
    <Card component="section" aria-label="Transaction de la revue" data-testid="review-transaction" data-id={item.id}>
      <CardContent>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={{ xs: 2, md: 4 }}>
          <Box sx={{ flex: 1, minWidth: 0 }}>
            <Typography variant="overline" color="text.secondary" data-testid="review-counter">
              {position} / {total}
            </Typography>
            <Typography variant="h5" component="h2" sx={{ wordBreak: 'break-word' }}>
              {item.label}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {formatDay(item.bookedAt)} · {item.accountName}
            </Typography>
            <Typography
              variant="h4"
              sx={{ mt: 2, fontVariantNumeric: 'tabular-nums', color: income ? 'success.main' : undefined }}
            >
              {income ? '+' : ''}
              {formatCents(item.amountCents, item.currency)}
            </Typography>
          </Box>

          <Box sx={{ flex: 1, minWidth: 0 }}>
            {item.recognised ? (
              <>
                <Typography variant="caption" color="text.secondary">
                  {item.qualifiedBy === 'series' ? 'Reconnue par une opération récurrente' : 'Reconnue par une règle'}
                </Typography>
                <Typography variant="subtitle1" sx={{ mb: 2 }}>
                  {item.categoryPath ?? '—'}
                </Typography>
                <Button variant="outlined" disabled={busy} onClick={() => setChoosing(true)}>
                  Corriger
                </Button>
              </>
            ) : (
              <>
                {item.categoryId ? (
                  <Typography variant="subtitle1" sx={{ mb: 2 }} data-testid="review-qualified">
                    Qualifiée · {item.categoryPath ?? 'catégorie choisie'}
                  </Typography>
                ) : item.suggestion ? (
                  <Box sx={{ mb: 2 }}>
                    <Typography variant="caption" color="text.secondary">
                      Maggie propose
                    </Typography>
                    <Typography variant="subtitle1">{item.suggestion.categoryPath}</Typography>
                    <Typography variant="body2" color="text.secondary">
                      {item.suggestion.reason}
                    </Typography>
                  </Box>
                ) : (
                  <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                    Aucune suggestion pour cette ligne.
                  </Typography>
                )}
                <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
                  {item.suggestion && !item.categoryId && (
                    <Button variant="contained" disabled={busy} onClick={onAccept}>
                      Accepter
                    </Button>
                  )}
                  <Button variant="outlined" disabled={busy} onClick={() => setChoosing(true)}>
                    Changer
                  </Button>
                  <Button disabled={busy} onClick={() => setCreating(true)}>
                    Créer un filtre
                  </Button>
                </Stack>
              </>
            )}

            {item.judgeable && (
              <Box sx={{ mt: 3 }}>
                <ToggleButtonGroup
                  exclusive
                  size="small"
                  disabled={busy}
                  value={item.retrospect === 'unrated' ? null : item.retrospect}
                  onChange={(_, next: Satisfaction | null) => onJudge(next ?? 'unrated')}
                  aria-label="Satisfaction"
                >
                  {SATISFACTION_CHOICES.map((choice) => (
                    <ToggleButton key={choice.value} value={choice.value}>
                      {choice.label}
                    </ToggleButton>
                  ))}
                </ToggleButtonGroup>
              </Box>
            )}
          </Box>
        </Stack>
      </CardContent>

      {choosing && (
        <CategoryDialog
          title={item.recognised ? 'Corriger' : 'Changer la catégorie'}
          submitLabel={item.recognised ? 'Enregistrer' : 'Qualifier'}
          item={item}
          busy={busy}
          onClose={() => setChoosing(false)}
          onSave={(category, path, qualification) => {
            void onCategory(category, path, qualification).then((ok) => ok && setChoosing(false))
          }}
        />
      )}

      {creating && (
        <CreateFilterDialog
          record={{
            id: item.id,
            label: item.label,
            amountCents: item.amountCents,
            category: item.suggestion ? `/api/categories/${item.suggestion.categoryId}` : null,
          }}
          applyToExistingByDefault
          onCreated={() => {
            setCreating(false)
            onOperationCreated()
          }}
          onClose={() => setCreating(false)}
        />
      )}
    </Card>
  )
}
