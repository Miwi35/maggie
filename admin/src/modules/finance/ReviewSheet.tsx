import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import Chip from '@mui/material/Chip'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import { alpha, useTheme } from '@mui/material/styles'
import CheckIcon from '@mui/icons-material/Check'
import PersonOutlineIcon from '@mui/icons-material/PersonOutline'
import { Link, useCreatePath, useGetList } from 'react-admin'
import type { RaRecord } from 'react-admin'
import { TOKENS } from '../../design/tokens'
import { formatCents } from './accountTypes'
import { categoryLabel } from './categoryTree'
import type { CategoryNode } from './categoryTree'
import { ContactPicker } from './ContactPicker'
import type { Contact } from './contactsApi'
import { CreateFilterDialog } from './CreateFilterDialog'
import { PeerTransferQualifier } from './PeerTransferQualifier'
import { Qualification } from './Qualification'
import { NO_QUALIFICATION } from './qualificationKinds'
import type { QualificationValue } from './qualificationKinds'
import { formatDay } from './recurringOperations'
import { RECURRING_OPERATIONS_PATH } from './referenceSalary'
import { SATISFACTION_CHOICES } from './satisfaction'
import type { Satisfaction } from './satisfaction'
import { PartageSection } from './TransactionSheet'
import { CONTROLLED_LABEL, RECOGNISED_LABEL, TO_QUALIFY_LABEL } from './reviewLabels'
import type { ResolvedKind, ReviewItem } from './useReviewQueue'

/** « À qualifier » in the orange of the warnings, « Reconnue » in the mint of the Comptes hue. */
export const ReviewStatePill = ({ recognised }: { recognised: boolean }) => {
  const mode = useTheme().palette.mode
  if (!recognised) return <Chip size="small" variant="outlined" color="warning" label={TO_QUALIFY_LABEL} />
  const mint = TOKENS.module.comptes[mode]

  return (
    <Chip
      size="small"
      variant="outlined"
      label={RECOGNISED_LABEL}
      sx={{ color: mint, borderColor: mint, bgcolor: alpha(mint, 0.16) }}
    />
  )
}

/** Who the transfer is with (MAG-450): their name, or the question when nobody claims it yet. */
const ContactLine = ({
  item,
  disabled,
  onLink,
}: {
  item: ReviewItem
  disabled: boolean
  onLink: (contact: Contact) => Promise<boolean>
}) => {
  const [picking, setPicking] = useState(false)

  if (item.contact) {
    return (
      <Stack direction="row" spacing={0.5} alignItems="center" sx={{ mt: 1 }}>
        <PersonOutlineIcon fontSize="small" color="action" />
        <Typography variant="body2">{item.contact.name}</Typography>
      </Stack>
    )
  }
  if (!item.asksForContact) return null

  return (
    <>
      <Button
        size="small"
        startIcon={<PersonOutlineIcon />}
        disabled={disabled}
        onClick={() => setPicking(true)}
        sx={{ mt: 1, ml: -0.5 }}
      >
        Associer à un contact ?
      </Button>
      <ContactPicker
        open={picking}
        onClose={() => setPicking(false)}
        onPick={(contact) => {
          void onLink(contact).then((linked) => linked && setPicking(false))
        }}
      />
    </>
  )
}

const RESOLVED_TEXT: Record<Exclude<ResolvedKind, 'category'>, string> = {
  transfer: 'Virement interne',
  peer: 'Virement précisé',
  operation: 'Opération créée',
}

const ORIGIN_TEXT = {
  rule: 'Reconnue par une règle',
  series: 'Reconnue par une opération récurrente',
  none: 'Qualifiée à la main',
} as const

export interface ReviewSheetProps {
  item: ReviewItem
  /** What the owner did to the line during this visit, when it was an unrecognised one. */
  resolved: ResolvedKind | undefined
  controlled: boolean
  busy: boolean
  notice: string | null
  /** « Valider »: Maggie's suggestion, with a rule when « Toujours pour ce marchand » is on. */
  onAccept: (alwaysForMerchant: boolean) => void
  /** « Autre catégorie »: a category chosen by hand. */
  onSort: (
    category: string,
    categoryPath: string | null,
    qualification: QualificationValue,
    alwaysForMerchant: boolean,
  ) => Promise<boolean>
  onInternalTransfer: () => void
  /** « Correct »: the operation got it right. */
  onControl: () => void
  /** « Corriger »: a category by hand on a line already recognised. */
  onCorrect: (category: string, categoryPath: string | null, qualification: QualificationValue) => Promise<boolean>
  onJudge: (verdict: Satisfaction) => void
  onLinkContact: (contact: Contact) => Promise<boolean>
  onOperationCreated: () => void
  onPeerAnswered: () => void
}

/**
 * The fiche of one line of the review (MAG-539), at the right of the list: what it is, what Maggie
 * proposes or what recognised it, and the gestures on that one line. The line stays here when a
 * gesture changes its state; « Suivant » is the owner's to press.
 */
export const ReviewSheet = ({
  item,
  resolved,
  controlled,
  busy,
  notice,
  onAccept,
  onSort,
  onInternalTransfer,
  onControl,
  onCorrect,
  onJudge,
  onLinkContact,
  onOperationCreated,
  onPeerAnswered,
}: ReviewSheetProps) => {
  const createPath = useCreatePath()
  const [always, setAlways] = useState(false)
  const [choosing, setChoosing] = useState<'sort' | 'correct' | null>(null)
  const [category, setCategory] = useState<string | null>(null)
  const [qualification, setQualification] = useState<QualificationValue>(NO_QUALIFICATION)
  const [creating, setCreating] = useState(false)
  const [sharing, setSharing] = useState(false)
  // The same list the picker reads (react-query shares it): the chosen category's path, for the line.
  const { data = [] } = useGetList<CategoryNode & RaRecord>('categories', {
    pagination: { page: 1, perPage: 200 },
    sort: { field: 'name', order: 'ASC' },
  })
  const pathOf = (id: string) => categoryLabel(data.find((c) => c.id === id)) || null

  const { suggestion } = item
  const income = item.amountCents > 0
  // A filed transfer only waits for « Ce virement est »: no category to ask, and having one it can
  // no longer be an internal transfer (the API refuses it).
  const recognised = item.recognised
  const handled = resolved !== undefined
  const waiting = !recognised && !handled
  const asksCategory = waiting && item.reason !== 'peer_transfer'
  const operationId = item.qualifiedBy === 'series' ? item.qualifiedBySeriesId : item.qualifiedByRuleId
  const operationLink =
    recognised && item.qualifiedBy && operationId
      ? item.qualifiedBy === 'rule'
        ? createPath({ resource: 'categorization_rules', type: 'edit', id: `/api/categorization_rules/${operationId}` })
        : RECURRING_OPERATIONS_PATH
      : null
  const origin = item.qualifiedBy ?? 'none'

  const closePanel = () => {
    setChoosing(null)
    setCategory(null)
    setQualification(NO_QUALIFICATION)
  }

  const panel = choosing && (
    <Stack spacing={1} sx={{ mb: 2 }}>
      <Qualification
        category={category}
        qualification={qualification}
        onChange={(next) => {
          setCategory(next.category)
          setQualification(next.qualification)
        }}
        sense={income ? 'in' : 'out'}
      />
      <Box>
        <Button
          variant="contained"
          disabled={busy || category === null}
          onClick={() => {
            if (category === null) return
            const done =
              choosing === 'sort'
                ? onSort(category, pathOf(category), qualification, always)
                : onCorrect(category, pathOf(category), qualification)
            void done.then((ok) => ok && closePanel())
          }}
        >
          {choosing === 'sort' ? 'Qualifier' : 'Enregistrer'}
        </Button>
      </Box>
    </Stack>
  )

  return (
    <Card component="section" aria-label="Fiche de la transaction" data-testid="review-sheet" data-id={item.id}>
      <CardContent>
        <Stack direction="row" justifyContent="space-between" alignItems="flex-start" spacing={2}>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="h6" component="h2" sx={{ wordBreak: 'break-word' }}>
              {item.label}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {formatDay(item.bookedAt)} · {item.accountName}
            </Typography>
            <ContactLine item={item} disabled={busy} onLink={onLinkContact} />
          </Box>
          <Box sx={{ textAlign: 'right', flexShrink: 0 }}>
            <Typography
              variant="h5"
              sx={{ fontVariantNumeric: 'tabular-nums', color: income ? 'success.main' : undefined }}
            >
              {income ? '+' : ''}
              {formatCents(item.amountCents, item.currency)}
            </Typography>
            <Box sx={{ mt: 0.5 }} data-testid="sheet-state">
              <ReviewStatePill recognised={recognised || handled} />
            </Box>
          </Box>
        </Stack>

        {notice && (
          <Alert severity="success" sx={{ mt: 2 }}>
            {notice}
          </Alert>
        )}

        {waiting && item.asksForPeerKind && (
          <Box sx={{ my: 2 }}>
            <PeerTransferQualifier transactionId={item.id} label={item.label} onSaved={onPeerAnswered} />
          </Box>
        )}

        {asksCategory && (
          <>
            <Box sx={{ my: 2 }}>
              {suggestion ? (
                <>
                  <Typography variant="caption" color="text.secondary">
                    Maggie propose
                  </Typography>
                  <Typography variant="subtitle1">→ {suggestion.categoryPath}</Typography>
                  <Typography variant="body2" color="text.secondary">
                    {suggestion.reason}
                  </Typography>
                </>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  Aucune suggestion pour cette ligne.
                </Typography>
              )}
            </Box>

            <FormControlLabel
              sx={{ mb: 1, display: 'flex' }}
              control={<Switch checked={always} disabled={busy} onChange={(e) => setAlways(e.target.checked)} />}
              label="Toujours pour ce marchand"
            />

            {choosing === 'sort' && panel}

            <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }}>
              {suggestion && (
                <Button variant="contained" disabled={busy} onClick={() => onAccept(always)}>
                  Valider
                </Button>
              )}
              <Button
                variant={suggestion ? 'outlined' : 'contained'}
                disabled={busy}
                onClick={() => (choosing === 'sort' ? closePanel() : setChoosing('sort'))}
              >
                Autre catégorie
              </Button>
              <Button disabled={busy} onClick={() => setCreating(true)}>
                Créer une opération
              </Button>
              <Button color="inherit" disabled={busy} onClick={onInternalTransfer}>
                Virement interne
              </Button>
            </Stack>
          </>
        )}

        {!recognised && handled && (
          <Box sx={{ my: 2 }} data-testid="review-handled">
            <Typography variant="subtitle1">
              {resolved === 'category' ? `Qualifiée · ${item.categoryPath ?? 'catégorie choisie'}` : RESOLVED_TEXT[resolved]}
            </Typography>
          </Box>
        )}

        {recognised && (
          <Box sx={{ my: 2 }}>
            <Typography variant="caption" color="text.secondary" component="div">
              Catégorie
            </Typography>
            <Typography variant="body2" sx={{ mb: 1 }}>
              {item.categoryPath ?? '—'}
            </Typography>
            <Typography variant="caption" color="text.secondary" component="div">
              {ORIGIN_TEXT[origin]}
            </Typography>
            <Typography variant="body2" data-testid="sheet-operation">
              {item.operationLabel ?? '—'}
            </Typography>
          </Box>
        )}

        {choosing === 'correct' && panel}

        {recognised && (
          <Stack direction="row" spacing={1} alignItems="center" sx={{ flexWrap: 'wrap', rowGap: 1 }}>
            {controlled ? (
              <Chip icon={<CheckIcon />} color="success" variant="outlined" label={CONTROLLED_LABEL} />
            ) : (
              <Button variant="contained" disabled={busy} onClick={onControl}>
                Correct
              </Button>
            )}
            <Button
              variant="outlined"
              disabled={busy}
              onClick={() => (choosing === 'correct' ? closePanel() : setChoosing('correct'))}
            >
              Corriger
            </Button>
            {operationLink && (
              <Button color="inherit" component={Link} to={operationLink}>
                Voir l&apos;opération
              </Button>
            )}
          </Stack>
        )}

        {!recognised && resolved === 'category' && (
          <Stack direction="row" spacing={1}>
            <Button
              variant="outlined"
              disabled={busy}
              onClick={() => (choosing === 'correct' ? closePanel() : setChoosing('correct'))}
            >
              Corriger
            </Button>
          </Stack>
        )}

        {item.judgeable && (
          <Box sx={{ mt: 2 }}>
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

        {resolved !== 'transfer' && !item.asksForPeerKind && (
          <Box sx={{ mt: 2 }}>
            <Button color="inherit" size="small" aria-expanded={sharing} onClick={() => setSharing((open) => !open)}>
              {income ? 'Créance / dette' : 'Partagée'}
            </Button>
            {sharing && (
              <Box sx={{ mt: 1 }}>
                <PartageSection transactionId={item.id} label={item.label} amountCents={item.amountCents} shared={false} />
              </Box>
            )}
          </Box>
        )}

        <Box sx={{ mt: 2 }}>
          <Button
            color="inherit"
            size="small"
            component={Link}
            to={createPath({ resource: 'transactions', type: 'edit', id: `/api/transactions/${item.id}` })}
          >
            Voir la fiche complète
          </Button>
        </Box>

        {creating && (
          <CreateFilterDialog
            title="Créer une opération"
            record={{
              id: item.id,
              label: item.label,
              amountCents: item.amountCents,
              category: suggestion ? `/api/categories/${suggestion.categoryId}` : null,
            }}
            applyToExistingByDefault
            onCreated={() => {
              setCreating(false)
              onOperationCreated()
            }}
            onClose={() => setCreating(false)}
          />
        )}
      </CardContent>
    </Card>
  )
}
