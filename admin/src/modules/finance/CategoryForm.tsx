import {
  SimpleForm,
  TextInput,
  SelectInput,
  ReferenceInput,
  AutocompleteInput,
  required,
} from 'react-admin'
import Box from '@mui/material/Box'
import { FormSection } from '../../components/form/FormSection'
import { OBLIGATION_CHOICES } from './obligationFlags'

const formSx = { maxWidth: 680 }
const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

interface CategoryFormProps {
  withDefaults?: boolean
}

export const CategoryForm = ({ withDefaults = false }: CategoryFormProps) => (
  <SimpleForm sx={formSx}>
    <FormSection
      first
      title="La catégorie"
      description="Les catégories classent vos opérations : elles portent les budgets, les règles automatiques et la revue mensuelle."
    />

    <TextInput
      source="name"
      label="Nom"
      validate={required()}
      fullWidth
      helperText="Alimentation, Loisirs, Transport…"
    />

    <ReferenceInput source="parent" reference="categories">
      <AutocompleteInput
        label="Rattacher à une catégorie"
        optionText="name"
        fullWidth
        helperText="Laissez vide pour une catégorie principale. Deux niveaux au maximum."
      />
    </ReferenceInput>

    <FormSection
      title="Nature de la dépense"
      description="C'est ce qui distingue un dépassement sur l'essentiel d'un dépassement sur le reste, dans le score comme dans la revue mensuelle."
    />

    <SelectInput
      source="obligation"
      label="Obligation"
      choices={OBLIGATION_CHOICES}
      defaultValue={withDefaults ? 'optional' : undefined}
      validate={required()}
      fullWidth
      helperText="Obligatoire : loyer, courses. Non-obligatoire : loisirs. Épargne et investissement ne sont pas des dépenses."
    />

    <FormSection title="Repères visuels" description="Facultatif, pour repérer la catégorie d'un coup d'œil." />

    <Box sx={rowSx}>
      <TextInput source="color" label="Couleur" sx={halfSx} helperText="Code hexadécimal, par exemple #4CAF50." />
      <TextInput source="icon" label="Icône" sx={halfSx} helperText="Nom d'icône, par exemple shopping-cart." />
    </Box>
  </SimpleForm>
)
