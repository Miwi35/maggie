import {
  Edit,
  SimpleForm,
  TextInput,
  NumberInput,
  SelectInput,
  BooleanInput,
  ReferenceInput,
  AutocompleteInput,
  required,
} from 'react-admin'
import { MATCH_TYPE_CHOICES, DIRECTION_CHOICES, centsInput } from './categorizationRules'

export const CategorizationRuleEdit = () => (
  <Edit>
    <SimpleForm>
      <TextInput
        source="labelPattern"
        label="Le libellé contient"
        validate={required()}
        fullWidth
      />
      <SelectInput
        source="matchType"
        label="Correspondance"
        choices={MATCH_TYPE_CHOICES}
        validate={required()}
      />
      <ReferenceInput source="category" reference="categories">
        <AutocompleteInput label="Catégorie" optionText="name" validate={required()} />
      </ReferenceInput>
      <SelectInput
        source="direction"
        label="Sens"
        choices={DIRECTION_CHOICES}
        validate={required()}
      />
      <NumberInput source="minAmountCents" label="Montant min (€)" {...centsInput} />
      <NumberInput source="maxAmountCents" label="Montant max (€)" {...centsInput} />
      <NumberInput source="priority" label="Priorité" />
      <BooleanInput source="isActive" label="Active" />
    </SimpleForm>
  </Edit>
)
