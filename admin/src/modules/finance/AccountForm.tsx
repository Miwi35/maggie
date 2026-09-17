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
    <FormSection
      first
      title="Le compte"
      description="Le nom est celui que vous utiliserez partout ailleurs : dans les opérations, le matelas, la vue d'ensemble."
    />

    <Box sx={rowSx}>
      <TextInput
        source="name"
        label="Nom"
        validate={required()}
        sx={halfSx}
        helperText="Compte courant, Livret A, Revolut…"
      />
      <TextInput
        source="bank"
        label="Banque"
        sx={halfSx}
        helperText="Facultatif — utile quand deux comptes portent le même nom."
      />
    </Box>

    <SelectInput
      source="type"
      label="Type de compte"
      choices={ACCOUNT_TYPE_CHOICES}
      defaultValue={withDefaults ? 'checking' : undefined}
      validate={required()}
      fullWidth
      helperText="Sert à regrouper vos comptes dans la vue d'ensemble."
    />

    <FormSection
      title="Solde"
      description="Le solde est saisi à la main : les opérations que vous ajoutez ne le modifient pas automatiquement."
    />

    <Box sx={rowSx}>
      <NumberInput
        source="balanceCents"
        label="Solde actuel (€)"
        defaultValue={withDefaults ? 0 : undefined}
        {...centsInput}
        sx={halfSx}
        helperText="Négatif pour un découvert."
      />
      <TextInput
        source="currency"
        label="Devise"
        defaultValue={withDefaults ? 'EUR' : undefined}
        sx={halfSx}
        helperText="Code ISO 4217 : EUR, CHF, USD…"
      />
    </Box>

    <BooleanInput
      source="isCushion"
      label="Ce compte fait partie du matelas de sécurité"
      defaultValue={withDefaults ? false : undefined}
      helperText="Son solde sera compté dans votre filet de sécurité."
    />
  </SimpleForm>
)
