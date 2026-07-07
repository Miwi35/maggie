import {
  Edit,
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
import { TRANSACTION_STATUS_CHOICES } from './transactionStatuses'

export const TransactionEdit = () => (
  <Edit>
    <SimpleForm>
      <ReferenceInput source="account" reference="accounts">
        <AutocompleteInput label="Compte" optionText="name" validate={required()} />
      </ReferenceInput>
      <ReferenceInput source="category" reference="categories">
        <AutocompleteInput label="Catégorie" optionText="name" />
      </ReferenceInput>
      <TextInput source="label" label="Libellé" validate={required()} fullWidth />
      <NumberInput
        source="amountCents"
        label="Montant (€, négatif = dépense)"
        validate={required()}
        format={(v?: number) => (v == null ? 0 : v / 100)}
        parse={(v?: number) => (v == null || Number.isNaN(v) ? 0 : Math.round(v * 100))}
      />
      <TextInput source="currency" label="Devise (ISO 4217)" />
      <DateInput source="bookedAt" label="Date" validate={required()} />
      <SelectInput
        source="status"
        label="Statut"
        choices={TRANSACTION_STATUS_CHOICES}
        validate={required()}
      />
      <BooleanInput source="isExceptional" label="Dépense exceptionnelle" />
    </SimpleForm>
  </Edit>
)
