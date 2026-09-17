import {
  Create,
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

export const CategorizationRuleCreate = () => (
  <Create>
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
        defaultValue="contains"
        validate={required()}
      />
      <ReferenceInput source="category" reference="categories">
        <AutocompleteInput label="Catégorie" optionText="name" validate={required()} />
      </ReferenceInput>
      <SelectInput
        source="direction"
        label="Sens"
        choices={DIRECTION_CHOICES}
        defaultValue="any"
        validate={required()}
      />
      <NumberInput source="minAmountCents" label="Montant min (€)" {...centsInput} />
      <NumberInput source="maxAmountCents" label="Montant max (€)" {...centsInput} />
      <NumberInput source="priority" label="Priorité" defaultValue={0} />
      <BooleanInput source="isActive" label="Active" defaultValue={true} />
    </SimpleForm>
  </Create>
)
