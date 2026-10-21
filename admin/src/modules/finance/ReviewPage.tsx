import { useEffect, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
import Card from '@mui/material/Card'
import Chip from '@mui/material/Chip'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import Typography from '@mui/material/Typography'
import CheckIcon from '@mui/icons-material/Check'
import ChevronLeftIcon from '@mui/icons-material/ChevronLeft'
import ChevronRightIcon from '@mui/icons-material/ChevronRight'
import { Title, useNotify } from 'react-admin'
import { useSearchParams } from 'react-router-dom'
import { Placeholder } from '../../components/list/ListEmpty'
import { useMercure } from '../../hooks/useMercure'
import { formatCents } from './accountTypes'
import type { Contact } from './contactsApi'
import { currentMonthKey } from './reviewActivity'
import { filterFromParams, matchesSearch, monthLabel, paramsFromFilter, shiftMonth } from './reviewFilter'
import type { ReviewFilter } from './reviewFilter'
import { ruleNotice } from './reviewLabels'
import { ReviewSheet, ReviewStatePill } from './ReviewSheet'
import { ReviewSheetNav, useArrowNavigation, useWideReview } from './ReviewSheetNav'
import { useSequenceCursor } from './reviewSequence'
import { AVOIDABLE_LABEL, KEEP_LABEL } from './satisfaction'
import { formatDay } from './recurringOperations'
import { byDate, useReviewQueue } from './useReviewQueue'
import type { ReviewItem, ReviewPeriod, ReviewState } from './useReviewQueue'

const REVIEW_TOPICS = ['/api/transactions/{id}']

/** One read holds the whole month; the API caps a page at this many lines. */
const QUEUE_LIMIT = 1000

const SINCE_REVIEW_LABEL = 'Depuis la dernière revue'
const ALL_DATES_LABEL = 'Toutes les dates'

/** The group a line was listed in when the list was read: it keeps it while the owner works on it. */
const listedRecognised = (item: ReviewItem): boolean => item.listedRecognised ?? item.recognised

const SATISFACTION_CHIP: Record<string, string> = { keep: KEEP_LABEL, avoidable: AVOIDABLE_LABEL }

const ReviewRow = ({
  item,
  selected,
  recognised,
  controlled,
  focusOnShow,
  onSelect,
}: {
  item: ReviewItem
  selected: boolean
  recognised: boolean
  controlled: boolean
  /** Back from the fiche on a narrow window: the line left is the one under the keyboard. */
  focusOnShow: boolean
  onSelect: () => void
}) => {
  const income = item.amountCents > 0
  const verdict = SATISFACTION_CHIP[item.retrospect]

  return (
    <Box component="li" sx={{ display: 'block' }}>
      <ButtonBase
        aria-label={item.label}
        autoFocus={focusOnShow}
        aria-current={selected ? 'true' : undefined}
        data-testid="review-row"
        data-state={recognised ? 'recognised' : 'unrecognised'}
        onClick={onSelect}
        sx={{
          display: 'flex',
          width: '100%',
          justifyContent: 'flex-start',
          textAlign: 'left',
          gap: 1,
          px: 2,
          py: 1,
          borderRadius: 0,
          bgcolor: selected ? 'action.selected' : undefined,
          '&:hover': { bgcolor: 'action.hover' },
        }}
      >
        <Box sx={{ minWidth: 0, flexGrow: 1 }}>
          <Typography variant="body2" noWrap>
            {item.label}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {formatDay(item.bookedAt)}
          </Typography>
        </Box>
        {verdict && <Chip size="small" variant="outlined" label={verdict} />}
        {controlled && <CheckIcon fontSize="small" color="success" titleAccess="Contrôlée" data-testid="review-controlled" />}
        <ReviewStatePill recognised={recognised} />
        <Typography
          variant="body2"
          sx={{ fontVariantNumeric: 'tabular-nums', minWidth: 84, textAlign: 'right', color: income ? 'success.main' : undefined }}
        >
          {income ? '+' : ''}
          {formatCents(item.amountCents, item.currency)}
        </Typography>
      </ButtonBase>
    </Box>
  )
}

const Group = ({
  title,
  items,
  resolved,
  controlled,
  currentId,
  focusCurrent,
  onSelect,
}: {
  title: string
  items: ReviewItem[]
  resolved: Record<string, unknown>
  controlled: ReadonlySet<string>
  currentId: string | null
  focusCurrent: boolean
  onSelect: (id: string) => void
}) =>
  items.length === 0 ? null : (
    <Box component="section" aria-label={title} sx={{ mb: 2 }}>
      <Typography variant="overline" color="text.secondary" component="h3" sx={{ px: 2 }}>
        {title}
      </Typography>
      <Box component="ul" sx={{ listStyle: 'none', m: 0, p: 0 }}>
        {items.map((item) => (
          <ReviewRow
            key={item.id}
            item={item}
            selected={item.id === currentId}
            recognised={item.recognised || item.id in resolved}
            controlled={controlled.has(item.id)}
            focusOnShow={focusCurrent && item.id === currentId}
            onSelect={() => onSelect(item.id)}
          />
        ))}
      </Box>
    </Box>
  )

/**
 * Finance › Revue (MAG-539): one list of the transactions — by state, by period, searchable — and
 * the fiche of the line opened at its right. The filters live in the URL, so a reload or a link
 * (the dashboard's « à qualifier » pill) lands on the same list. A gesture changes the line's state
 * in place and the fiche stays on it: the line leaves the list at the next reload or filter change.
 */
export const ReviewPage = () => {
  const [params, setParams] = useSearchParams()
  const filter = useMemo(() => filterFromParams(params), [params])
  const notify = useNotify()
  const wide = useWideReview()
  const {
    queue,
    loading,
    error,
    gestureError,
    busy,
    resolved,
    controlled,
    lastResult,
    retry,
    refresh,
    accept,
    sort,
    markInternalTransfer,
    control,
    correct,
    judge,
    linkContact,
    answered,
  } = useReviewQueue({
    period: filter.period,
    month: filter.period === 'month' ? filter.month : undefined,
    limit: QUEUE_LIMIT,
  })
  const [resultFor, setResultFor] = useState<string | null>(null)

  useMercure(REVIEW_TOPICS, () => {
    void refresh()
  })

  const change = (next: Partial<ReviewFilter>) => setParams(paramsFromFilter({ ...filter, ...next }), { replace: true })

  const items = queue?.items ?? []
  const shown = (item: ReviewItem) => item.recognised || item.id in resolved
  const counts = {
    all: items.length,
    unrecognised: items.filter((item) => !shown(item)).length,
    recognised: items.filter(shown).length,
  }

  // The state filter reads the group a line was listed in, so a line the owner just handled stays.
  const visible = [...items].sort(byDate).filter(
    (item) =>
      (filter.state === 'all' || (filter.state === 'recognised') === listedRecognised(item)) &&
      matchesSearch(item, filter.q),
  )
  const unrecognisedLines = visible.filter((item) => !listedRecognised(item))
  const recognisedLines = visible.filter(listedRecognised)
  const ordered = [...unrecognisedLines, ...recognisedLines]

  const cursor = useSequenceCursor(ordered.map((item) => item.id))
  useArrowNavigation(cursor)
  const { open, isOpen, cursorId } = cursor
  const firstId = ordered[0]?.id ?? null
  useEffect(() => {
    // Side by side, the fiche is always on screen: it opens on the first line of the list.
    if (wide && !isOpen && firstId !== null) open(cursorId ?? firstId)
  }, [wide, isOpen, firstId, cursorId, open])

  if (loading && !queue) {
    return (
      <>
        <Title title="Revue" />
        <LinearProgress aria-label="Chargement" />
      </>
    )
  }

  if (!queue) {
    return (
      <>
        <Title title="Revue" />
        <Alert severity="error" action={<Button onClick={retry}>Réessayer</Button>}>
          {error}
        </Alert>
      </>
    )
  }

  const current = ordered.find((item) => item.id === cursorId)
  const showList = wide || !isOpen
  const showSheet = (wide || isOpen) && current !== undefined
  const thisMonth = currentMonthKey()

  const link = async (item: ReviewItem, contact: Contact): Promise<boolean> => {
    try {
      await linkContact(item, contact)
      notify(`« ${item.counterpartyLabel ?? item.label} » est lié à ${contact.name}.`, { type: 'success' })

      return true
    } catch (e) {
      notify(e instanceof Error ? e.message : 'Impossible de lier ce libellé.', { type: 'error' })

      return false
    }
  }

  return (
    <>
      <Title title="Revue" />

      <Stack spacing={2} sx={{ mb: 2 }}>
        <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', rowGap: 1 }} alignItems="center">
          <ToggleButtonGroup
            exclusive
            size="small"
            aria-label="État"
            value={filter.state}
            onChange={(_, next: ReviewState | null) => next && change({ state: next })}
          >
            <ToggleButton value="all">Toutes · {counts.all}</ToggleButton>
            <ToggleButton value="unrecognised">À qualifier · {counts.unrecognised}</ToggleButton>
            <ToggleButton value="recognised">Reconnues · {counts.recognised}</ToggleButton>
          </ToggleButtonGroup>

          <TextField
            size="small"
            type="search"
            placeholder="Rechercher"
            value={filter.q}
            onChange={(e) => change({ q: e.target.value })}
            slotProps={{ htmlInput: { 'aria-label': 'Rechercher une transaction' } }}
          />
        </Stack>

        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', rowGap: 1 }} alignItems="center">
          {filter.period === 'month' && (
            <IconButton
              size="small"
              aria-label="Mois précédent"
              onClick={() => change({ month: shiftMonth(filter.month, -1) })}
            >
              <ChevronLeftIcon />
            </IconButton>
          )}
          <ToggleButtonGroup
            exclusive
            size="small"
            aria-label="Période"
            value={filter.period}
            onChange={(_, next: ReviewPeriod | null) => next && change({ period: next })}
          >
            <ToggleButton value="month">{monthLabel(filter.month)}</ToggleButton>
            <ToggleButton value="since_review">{SINCE_REVIEW_LABEL}</ToggleButton>
            <ToggleButton value="all">{ALL_DATES_LABEL}</ToggleButton>
          </ToggleButtonGroup>
          {filter.period === 'month' && (
            <IconButton
              size="small"
              aria-label="Mois suivant"
              disabled={filter.month >= thisMonth}
              onClick={() => change({ month: shiftMonth(filter.month, 1) })}
            >
              <ChevronRightIcon />
            </IconButton>
          )}
          {filter.period === 'since_review' && queue.sinceReviewDate && (
            <Typography variant="caption" color="text.secondary">
              depuis le {formatDay(queue.sinceReviewDate)}
            </Typography>
          )}
        </Stack>
      </Stack>

      {gestureError && (
        <Alert severity="error" sx={{ mb: 2 }}>
          {gestureError}
        </Alert>
      )}

      {error && (
        <Alert severity="warning" sx={{ mb: 2 }} action={<Button onClick={retry}>Réessayer</Button>}>
          {error}
        </Alert>
      )}

      {ordered.length === 0 ? (
        <Card>
          <Placeholder
            title="Aucune transaction"
            description={
              items.length === 0
                ? 'Rien à revoir sur cette période.'
                : 'Aucune transaction ne correspond à ces filtres.'
            }
          />
        </Card>
      ) : (
        <Stack direction="row" spacing={2} alignItems="flex-start">
          {showList && (
            <Card sx={{ width: wide ? 520 : '100%', flexShrink: 0, py: 1 }}>
              <Group
                title="Non reconnues"
                items={unrecognisedLines}
                resolved={resolved}
                controlled={controlled}
                currentId={cursorId}
                focusCurrent={!wide}
                onSelect={open}
              />
              <Group
                title="Reconnues"
                items={recognisedLines}
                resolved={resolved}
                controlled={controlled}
                currentId={cursorId}
                focusCurrent={!wide}
                onSelect={open}
              />
            </Card>
          )}
          {showSheet && current && (
            <Box sx={{ flexGrow: 1, minWidth: 0 }}>
              <ReviewSheetNav cursor={cursor} showBack={!wide} />
              <ReviewSheet
                key={current.id}
                item={current}
                resolved={resolved[current.id]}
                controlled={controlled.has(current.id)}
                busy={busy}
                notice={resultFor === current.id ? ruleNotice(lastResult) : null}
                onAccept={(always) => {
                  setResultFor(current.id)
                  void accept(current, { alwaysForMerchant: always })
                }}
                onSort={(category, path, qualification, always) => {
                  setResultFor(current.id)

                  return sort(current, category, path, qualification, { alwaysForMerchant: always })
                }}
                onInternalTransfer={() => void markInternalTransfer(current)}
                onControl={() => void control(current)}
                onCorrect={(category, path, qualification) => correct(current, category, qualification, path)}
                onJudge={(verdict) => void judge(current, verdict)}
                onLinkContact={(contact) => link(current, contact)}
                onOperationCreated={() => {
                  answered(current, 'operation')
                  void refresh()
                }}
                onPeerAnswered={() => answered(current, 'peer')}
              />
            </Box>
          )}
        </Stack>
      )}
    </>
  )
}
