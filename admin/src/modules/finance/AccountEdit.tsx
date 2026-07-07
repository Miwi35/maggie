import {
  Edit,
  SimpleForm,
  TextInput,
  NumberInput,
  SelectInput,
  BooleanInput,
  required,
} from 'react-admin'
import { ACCOUNT_TYPE_CHOICES } from './accountTypes'

export const AccountEdit = () => (
  <Edit>
    <SimpleForm>
      <TextInput source="name" label="Nom" validate={required()} fullWidth />
      <TextInput source="bank" label="Banque" fullWidth />
      <SelectInput
        source="type"
        label="Type"
        choices={ACCOUNT_TYPE_CHOICES}
        validate={required()}
      />
      <TextInput source="currency" label="Devise (ISO 4217)" />
      <NumberInput
        source="balanceCents"
        label="Solde (€)"
        format={(v?: number) => (v == null ? 0 : v / 100)}
        parse={(v?: number) => (v == null || Number.isNaN(v) ? 0 : Math.round(v * 100))}
      />
      <BooleanInput source="isCushion" label="Compte matelas de sécurité" />
    </SimpleForm>
  </Edit>
)
