import Box from '@mui/material/Box'
import { SimpleForm, TextInput, NumberInput, required } from 'react-admin'
import { FormSection } from './FormSection'
import { basisPointsInput, centsInput } from './loans'

const formSx = { maxWidth: 680 }
const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

interface LoanFormProps {
  withDefaults?: boolean
}

export const LoanForm = ({ withDefaults = false }: LoanFormProps) => (
  <SimpleForm sx={formSx}>
    <FormSection
      first
      title="Le prêt"
      description="Les trois chiffres ci-dessous suffisent : la date de fin se déduit de l'amortissement, elle n'est pas à saisir."
    />

    <Box sx={rowSx}>
      <TextInput source="name" label="Nom" validate={required()} sx={halfSx} />
      <TextInput
        source="lender"
        label="Organisme prêteur"
        sx={halfSx}
        helperText="Facultatif — pour vous y retrouver."
      />
    </Box>

    <Box sx={rowSx}>
      <NumberInput
        source="principalRemainingCents"
        label="Capital restant dû (€)"
        validate={required()}
        {...centsInput}
        sx={halfSx}
        helperText="Ce qu'il reste à rembourser aujourd'hui."
      />
      <NumberInput
        source="monthlyPaymentCents"
        label="Mensualité (€)"
        validate={required()}
        {...centsInput}
        sx={halfSx}
        helperText="Doit couvrir les intérêts du mois, sinon le prêt ne s'éteint jamais."
      />
    </Box>

    <Box sx={rowSx}>
      <NumberInput
        source="annualRateBasisPoints"
        label="Taux annuel (%)"
        defaultValue={withDefaults ? 0 : undefined}
        {...basisPointsInput}
        sx={halfSx}
        helperText="0 pour un prêt sans intérêt."
      />
      <TextInput
        source="currency"
        label="Devise"
        defaultValue={withDefaults ? 'EUR' : undefined}
        sx={halfSx}
        helperText="Code ISO 4217."
      />
    </Box>

    <FormSection
      title="Ordre de libération"
      description="Sert à trier vos prêts quand vous arbitrez lequel solder en premier."
    />

    <NumberInput
      source="priority"
      label="Priorité"
      defaultValue={withDefaults ? 0 : undefined}
      sx={halfSx}
      helperText="Plus le nombre est grand, plus le prêt remonte dans la liste."
    />
  </SimpleForm>
)
