import Box from '@mui/material/Box'
import {
  SimpleForm,
  TextInput,
  NumberInput,
  SelectInput,
  BooleanInput,
  required,
} from 'react-admin'
import { ACCOUNT_TYPE_CHOICES } from './accountTypes'
import { FormSection } from '../../components/form/FormSection'
import { centsInput } from './categorizationRules'

const formSx = { maxWidth: 680 }
const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

interface AccountFormProps {
  withDefaults?: boolean
}

export const AccountForm = ({ withDefaults = false }: AccountFormProps) => (
  <SimpleForm sx={formSx}>
    <FormSection first title="Le compte" />

    <Box sx={rowSx}>
      <TextInput
        source="name"
        label="Nom"
        validate={required()}
        sx={halfSx}
      />
      <TextInput
        source="bank"
        label="Banque"
        sx={halfSx}
      />
    </Box>

    <SelectInput
      source="type"
      label="Type de compte"
      choices={ACCOUNT_TYPE_CHOICES}
      defaultValue={withDefaults ? 'checking' : undefined}
      validate={required()}
      fullWidth
    />

    <FormSection title="Solde" />

    <Box sx={rowSx}>
      <NumberInput
        source="balanceCents"
        label="Solde actuel (€)"
        defaultValue={withDefaults ? 0 : undefined}
        {...centsInput}
        sx={halfSx}
      />
      <TextInput
        source="currency"
        label="Devise"
        defaultValue={withDefaults ? 'EUR' : undefined}
        sx={halfSx}
      />
    </Box>

    <BooleanInput
      source="isCushion"
      label="Ce compte fait partie du matelas de sécurité"
      defaultValue={withDefaults ? false : undefined}
    />
  </SimpleForm>
)
