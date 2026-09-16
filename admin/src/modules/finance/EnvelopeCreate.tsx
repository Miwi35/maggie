import {
  Create,
  SimpleForm,
  TextInput,
  NumberInput,
  SelectInput,
  ReferenceInput,
  AutocompleteInput,
  FormDataConsumer,
  required,
} from 'react-admin'
import { BUDGET_MODE_CHOICES, MONTH_CHOICES, clearMonthOnAnnual } from './budgetModes'

const now = new Date()

export const EnvelopeCreate = () => (
  <Create transform={clearMonthOnAnnual}>
    <SimpleForm>
      <ReferenceInput source="category" reference="categories">
        <AutocompleteInput label="Catégorie" optionText="name" validate={required()} />
      </ReferenceInput>
      <SelectInput
        source="mode"
        label="Mode"
        choices={BUDGET_MODE_CHOICES}
        defaultValue="monthly"
        validate={required()}
      />
      <NumberInput
        source="amountCents"
        label="Budget (€)"
        validate={required()}
        format={(v?: number) => (v == null ? 0 : v / 100)}
        parse={(v?: number) => (v == null || Number.isNaN(v) ? 0 : Math.round(v * 100))}
      />
      <TextInput source="currency" label="Devise (ISO 4217)" defaultValue="EUR" />
      <NumberInput source="year" label="Année" defaultValue={now.getFullYear()} validate={required()} />
      <FormDataConsumer>
        {({ formData }) =>
          formData.mode !== 'annual' && (
            <SelectInput
              source="month"
              label="Mois"
              choices={MONTH_CHOICES}
              defaultValue={now.getMonth() + 1}
              validate={required()}
            />
          )
        }
      </FormDataConsumer>
    </SimpleForm>
  </Create>
)
