import { Create, SimpleForm, TextInput, NumberInput, required } from 'react-admin'

export const StoreCreate = () => (
  <Create>
    <SimpleForm>
      <TextInput source="name" label="Nom" validate={required()} fullWidth />
      <TextInput source="description" label="Description" multiline rows={3} fullWidth />
      <NumberInput source="visitOrder" label="Ordre de visite" defaultValue={0} />
    </SimpleForm>
  </Create>
)
