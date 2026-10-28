import Box from '@mui/material/Box'
import { SimpleForm, TextInput, NumberInput, required } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'
import { LoanTermsFields } from './LoanTermsFields'
import { basisPointsInput, centsInput } from './loans'

const formSx = { maxWidth: 680 }
const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

interface LoanFormProps {
  withDefaults?: boolean
  /** `YYYY-MM` of the last payment, once the API has computed it. */
  endsOn?: string | null
}

export const LoanForm = ({ withDefaults = false, endsOn }: LoanFormProps) => (
  <SimpleForm sx={formSx}>
    <FormSection first title="Le prêt" />

    <Box sx={rowSx}>
      <TextInput source="name" label="Nom" validate={required()} sx={halfSx} />
      <TextInput
        source="lender"
        label="Organisme prêteur"
        sx={halfSx}
      />
    </Box>

    <Box sx={rowSx}>
      <NumberInput
        source="principalRemainingCents"
        label="Capital restant dû (€)"
        validate={required()}
        {...centsInput}
        sx={halfSx}
      />
      <NumberInput
        source="monthlyPaymentCents"
        label="Mensualité (€)"
        validate={required()}
        {...centsInput}
        sx={halfSx}
      />
    </Box>

    <Box sx={rowSx}>
      <NumberInput
        source="annualRateBasisPoints"
        label="Taux annuel (%)"
        defaultValue={withDefaults ? 0 : undefined}
        {...basisPointsInput}
        sx={halfSx}
      />
      <TextInput
        source="currency"
        label="Devise"
        defaultValue={withDefaults ? 'EUR' : undefined}
        sx={halfSx}
      />
    </Box>

    <LoanTermsFields endsOn={endsOn} withDefaults={withDefaults} />

    <FormSection title="Ordre de libération" />

    <NumberInput
      source="priority"
      label="Priorité"
      defaultValue={withDefaults ? 0 : undefined}
      sx={halfSx}
    />
  </SimpleForm>
)
