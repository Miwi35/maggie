import Box from '@mui/material/Box'
import {
  SimpleForm,
  TextInput,
  NumberInput,
  DateInput,
  SelectInput,
  BooleanInput,
  ReferenceInput,
  AutocompleteInput,
  required,
} from 'react-admin'
import { FormSection } from './FormSection'
import { centsInput } from './categorizationRules'
import { TRANSACTION_STATUS_CHOICES } from './transactionStatuses'

const formSx = { maxWidth: 680 }
const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

interface TransactionFormProps {
  withDefaults?: boolean
}

export const TransactionForm = ({ withDefaults = false }: TransactionFormProps) => (
  <SimpleForm sx={formSx}>
    <FormSection
      first
      title="L'opération"
      description="Le montant porte le sens : négatif pour une dépense, positif pour une recette."
    />

    <ReferenceInput source="account" reference="accounts">
      <AutocompleteInput
        label="Compte"
        optionText="name"
        validate={required()}
        fullWidth
        helperText="Le compte sur lequel l'opération est passée."
      />
    </ReferenceInput>

    <TextInput
      source="label"
      label="Libellé"
      validate={required()}
      fullWidth
      helperText="Ce que vous lirez sur votre relevé — c'est aussi ce que lisent les règles de catégorisation."
    />

    <Box sx={rowSx}>
      <NumberInput
        source="amountCents"
        label="Montant (€)"
        validate={required()}
        {...centsInput}
        sx={halfSx}
        helperText="Négatif pour une dépense, positif pour une recette."
      />
      <DateInput
        source="bookedAt"
        label="Date"
        validate={required()}
        sx={halfSx}
        helperText="La date à laquelle l'argent bouge, pas celle de l'événement."
      />
    </Box>

    <FormSection
      title="Classement"
      description="Sans catégorie, les règles automatiques s'en chargeront — et sinon, la revue mensuelle vous la proposera."
    />

    <Box sx={rowSx}>
      <ReferenceInput source="category" reference="categories">
        <AutocompleteInput
          label="Catégorie"
          optionText="name"
          sx={halfSx}
          helperText="Facultatif."
        />
      </ReferenceInput>
      <TextInput
        source="currency"
        label="Devise"
        defaultValue={withDefaults ? 'EUR' : undefined}
        sx={halfSx}
        helperText="Code ISO 4217."
      />
    </Box>

    <FormSection
      title="État de la dépense"
      description="C'est ce qui décide du poids de l'opération sur l'enveloppe de sa catégorie."
    />

    <SelectInput
      source="status"
      label="Statut"
      choices={TRANSACTION_STATUS_CHOICES}
      defaultValue={withDefaults ? 'spent' : undefined}
      validate={required()}
      fullWidth
      helperText="Dépensée et engagée sont déduites du budget ; planifiée est mise de côté ; à arbitrer ne compte pas encore."
    />

    <BooleanInput
      source="isExceptional"
      label="Dépense exceptionnelle"
      defaultValue={withDefaults ? false : undefined}
      helperText="À cocher pour ce qui ne se reproduira pas — un déménagement, une réparation."
    />
  </SimpleForm>
)
