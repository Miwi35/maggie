import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { ArrayInput, BooleanInput, DateInput, NumberInput, SelectInput, SimpleFormIterator, TextInput } from 'react-admin'
import { useWatch } from 'react-hook-form'
import { FormSection } from '../../components/form/FormSection'
import {
  LOAN_TYPES,
  PENALTY_MODES,
  basisPointsInput,
  centsInput,
  formatMonth,
  needsDeferral,
  needsSteps,
  needsVariableTerms,
} from './loans'

const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

const EFFECTS = [
  { id: 'shorten', name: 'Raccourcir la durée' },
  { id: 'lower_payment', name: 'Baisser la mensualité' },
]

interface LoanTermsFieldsProps {
  /** Where the conditions sit in the form record: '' for a loan, 'loan.' for a loan nested in another record. */
  prefix?: string
  /** `YYYY-MM` of the last payment, deduced by the API: shown, never typed. */
  endsOn?: string | null
  withDefaults?: boolean
  /** The amount borrowed is a field of the loan's sheet; a project's source sets it with its own amount. */
  withAmount?: boolean
}

/**
 * The conditions of a loan by type: choosing the type shows only the fields
 * that apply to it. A form inside a react-admin form; the loan's own sheet and
 * a project's borrowing source share it.
 */
export const LoanTermsFields = ({ prefix = '', endsOn, withDefaults = false, withAmount = true }: LoanTermsFieldsProps) => {
  const type = useWatch({ name: `${prefix}type` }) as string | undefined
  const penaltyMode = useWatch({ name: `${prefix}prepaymentPenaltyMode` }) as string | undefined
  const field = (name: string) => `${prefix}${name}`

  return (
    <>
      <FormSection title="Conditions" />

      <Box sx={rowSx}>
        <SelectInput
          source={field('type')}
          label="Type de prêt"
          choices={LOAN_TYPES}
          defaultValue={withDefaults ? 'amortizing_fixed' : undefined}
          sx={halfSx}
        />
        {withAmount && (
          <NumberInput source={field('initialAmountCents')} label="Montant emprunté (€)" {...centsInput} sx={halfSx} />
        )}
      </Box>

      <Box sx={rowSx}>
        <NumberInput source={field('durationMonths')} label="Durée (mois)" sx={halfSx} />
        <DateInput source={field('firstPaymentOn')} label="Première échéance" parse={(value: string) => value || null} sx={halfSx} />
      </Box>

      <Box sx={rowSx}>
        <NumberInput source={field('aprBasisPoints')} label="TAEG (%)" {...basisPointsInput} sx={halfSx} />
        <Typography
          data-testid="loan-end-date"
          variant="body2"
          sx={{ flex: '1 1 240px', alignSelf: 'center' }}
        >
          Fin : <strong>{endsOn ? formatMonth(endsOn) : '—'}</strong>
        </Typography>
      </Box>

      {needsVariableTerms(type) && (
        <Box sx={rowSx}>
          <TextInput source={field('referenceIndex')} label="Indice" sx={halfSx} />
          <NumberInput source={field('marginBasisPoints')} label="Marge (%)" {...basisPointsInput} sx={halfSx} />
          <NumberInput source={field('rateCapBasisPoints')} label="Plafond du taux (%)" {...basisPointsInput} sx={halfSx} />
          <NumberInput source={field('rateFloorBasisPoints')} label="Plancher du taux (%)" {...basisPointsInput} sx={halfSx} />
        </Box>
      )}

      {needsSteps(type) && (
        <ArrayInput source={field('steps')} label="Paliers">
          <SimpleFormIterator inline disableReordering>
            <NumberInput source="fromMonth" label="À partir du mois" />
            <NumberInput source="monthlyPaymentCents" label="Mensualité (€)" {...centsInput} />
          </SimpleFormIterator>
        </ArrayInput>
      )}

      {needsDeferral(type) && (
        <NumberInput source={field('deferralMonths')} label="Durée du différé" sx={halfSx} />
      )}

      <FormSection title="Assurance et frais" />

      <Box sx={rowSx}>
        <NumberInput source={field('insuranceMonthlyCents')} label="Assurance par mois (€)" {...centsInput} sx={halfSx} />
        <NumberInput source={field('insuranceRateBasisPoints')} label="Assurance, taux annuel (%)" {...basisPointsInput} sx={halfSx} />
        <NumberInput source={field('feesCents')} label="Frais de dossier et de garantie (€)" {...centsInput} sx={halfSx} />
      </Box>

      <FormSection title="Remboursement anticipé" />

      <BooleanInput
        source={field('earlyRepaymentAllowed')}
        label="Remboursement anticipé autorisé"
        defaultValue={withDefaults ? true : undefined}
      />

      <Box sx={rowSx}>
        <SelectInput
          source={field('prepaymentPenaltyMode')}
          label="IRA"
          choices={PENALTY_MODES}
          defaultValue={withDefaults ? 'legal_cap' : undefined}
          sx={halfSx}
        />
        {penaltyMode === 'fixed' && (
          <NumberInput source={field('prepaymentPenaltyFixedCents')} label="Montant de l'IRA (€)" {...centsInput} sx={halfSx} />
        )}
      </Box>

      <Box sx={rowSx}>
        <NumberInput source={field('minPartialRepaymentCents')} label="Remboursement partiel minimum (€)" {...centsInput} sx={halfSx} />
        <SelectInput
          source={field('partialRepaymentEffect')}
          label="Après un remboursement partiel"
          choices={EFFECTS}
          emptyText="—"
          parse={(value: string) => value || null}
          sx={halfSx}
        />
        <NumberInput source={field('noticeDays')} label="Préavis (jours)" sx={halfSx} />
      </Box>
    </>
  )
}
