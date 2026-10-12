import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import CardContent from '@mui/material/CardContent'
import List from '@mui/material/List'
import ListItem from '@mui/material/ListItem'
import ListItemText from '@mui/material/ListItemText'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Amount } from './AmountField'
import { claimsOf, describeReason, describeSchedule, formatDay, formatShortDay } from './recurringOperations'
import type { ProposalClaim, RecurringProposal, RecurringSuggestion } from './recurringOperations'

interface ProposalsPanelProps {
  proposals: RecurringProposal[]
  currencyOf: (seriesId: string | null) => string
  busy: boolean
  onAttach: (proposal: RecurringProposal, claim: ProposalClaim) => void
  onIgnore: (proposal: RecurringProposal) => void
}

/** The lines that broke one tolerance: the owner says yes (Rattacher) or no (Ignorer). */
export const ProposalsPanel = ({ proposals, currencyOf, busy, onAttach, onIgnore }: ProposalsPanelProps) => (
  <Card component="section" aria-labelledby="recurring-proposals-title" sx={{ mb: 3 }}>
    <CardContent>
      <Typography id="recurring-proposals-title" variant="subtitle1" sx={{ fontWeight: 600 }}>
        Rattachements proposés
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        Ces transactions ressemblent à une série mais sortent de ses tolérances : rien n’est rattaché sans votre accord.
      </Typography>
      <List disablePadding>
        {proposals.map((proposal) => {
          const claims = claimsOf(proposal)
          const currency = currencyOf(proposal.recurringOperationId)

          return (
            <ListItem
              key={proposal.transactionId}
              divider
              disableGutters
              sx={{ alignItems: 'flex-start', flexWrap: 'wrap', gap: 1 }}
            >
              <ListItemText
                sx={{ flex: '1 1 320px' }}
                primary={`${formatDay(proposal.bookedAt)} · ${proposal.label}`}
                secondary={
                  proposal.reason === 'ambiguous'
                    ? `${describeReason(proposal, currency)} : ${claims.map((c) => c.recurringOperationLabel).join(', ')}`
                    : `${proposal.recurringOperationLabel}, échéance du ${formatShortDay(proposal.occurrenceOn ?? proposal.bookedAt)} — ${describeReason(proposal, currency)}`
                }
              />
              <Amount cents={proposal.amountCents} currency={currency} signed />
              <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                {claims.map((claim) => (
                  <Button
                    key={claim.recurringOperationId}
                    size="small"
                    variant="contained"
                    disabled={busy}
                    onClick={() => onAttach(proposal, claim)}
                    aria-label={
                      claims.length > 1
                        ? `Rattacher ${proposal.label} à ${claim.recurringOperationLabel}`
                        : `Rattacher ${proposal.label}`
                    }
                  >
                    {claims.length > 1 ? `Rattacher à « ${claim.recurringOperationLabel} »` : 'Rattacher'}
                  </Button>
                ))}
                <Button size="small" disabled={busy} onClick={() => onIgnore(proposal)} aria-label={`Ignorer ${proposal.label}`}>
                  Ignorer
                </Button>
              </Stack>
            </ListItem>
          )
        })}
      </List>
    </CardContent>
  </Card>
)

/** Why the history suggests the series: how often, how steady. */
export const suggestionReason = (s: RecurringSuggestion): string =>
  `${s.occurrences} transactions ${describeSchedule(s.period, s.dayRule, s.anchorOn)}, montant stable à ${s.amountSpreadPercent} % près`

interface SuggestionsPanelProps {
  suggestions: RecurringSuggestion[]
  busy: boolean
  onAccept: (suggestion: RecurringSuggestion) => void
}

/** The series the bank history implies. None exists until « Créer la série ». */
export const SuggestionsPanel = ({ suggestions, busy, onAccept }: SuggestionsPanelProps) => (
  <Card component="section" aria-labelledby="recurring-suggestions-title" sx={{ mb: 3 }}>
    <CardContent>
      <Typography id="recurring-suggestions-title" variant="subtitle1" sx={{ fontWeight: 600 }}>
        Suggestions
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
        Des paiements qui reviennent dans vos relevés sans qu’aucune série ne les couvre.
      </Typography>
      <List disablePadding>
        {suggestions.map((s) => (
          <ListItem
            key={`${s.accountId}/${s.counterpartyKey ?? s.labelPattern}`}
            divider
            disableGutters
            sx={{ alignItems: 'flex-start', flexWrap: 'wrap', gap: 1 }}
          >
            <ListItemText
              sx={{ flex: '1 1 320px' }}
              primary={`${s.label} · ${s.accountName}`}
              secondary={
                <>
                  {suggestionReason(s)}
                  <br />
                  Par exemple : {s.samples.join(' · ')}
                </>
              }
            />
            <Amount cents={s.referenceAmountCents} signed />
            <Button
              size="small"
              variant="contained"
              disabled={busy}
              onClick={() => onAccept(s)}
              aria-label={`Créer la série ${s.label}`}
            >
              Créer la série
            </Button>
          </ListItem>
        ))}
      </List>
    </CardContent>
  </Card>
)
