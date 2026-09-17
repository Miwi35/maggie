import { SimpleForm, TextInput, NumberInput, required } from 'react-admin'
import { FormSection } from '../../components/form/FormSection'

const formSx = { maxWidth: 680 }

export const StoreForm = () => (
  <SimpleForm sx={formSx}>
    <FormSection
      first
      title="Le magasin"
      description="Les magasins découpent la liste de courses : chaque article part dans celui où vous l'achetez."
    />

    <TextInput
      source="name"
      label="Nom"
      validate={required()}
      fullWidth
      helperText="Lidl, marché du samedi, boulangerie…"
    />
    <TextInput
      source="description"
      label="Description"
      multiline
      rows={3}
      fullWidth
      helperText="Facultatif — horaires, adresse, ce dont vous voulez vous souvenir."
    />
    <NumberInput
      source="visitOrder"
      label="Ordre de visite"
      defaultValue={0}
      helperText="Les magasins s'affichent dans cet ordre, celui de votre tournée."
    />
  </SimpleForm>
)
