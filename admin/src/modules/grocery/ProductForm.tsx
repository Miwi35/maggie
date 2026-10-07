import {
  SimpleForm,
  TextInput,
  NumberInput,
  SelectInput,
  ReferenceInput,
  AutocompleteInput,
  required,
} from 'react-admin'
import Box from '@mui/material/Box'
import { FormSection } from '../../components/form/FormSection'
import { CATEGORY_CHOICES, UNIT_CHOICES } from './productChoices'
import { PackagingInputs } from './PackagingInputs'

const formSx = { maxWidth: 680 }
const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

export const ProductForm = () => (
  <SimpleForm sx={formSx}>
    <FormSection
      first
      title="Le produit"
      description="Un produit est ce que vous ajoutez à votre liste de courses : le nom est celui que vous taperez."
    />

    <TextInput
      source="name"
      label="Nom"
      validate={required()}
      fullWidth
      helperText="Lait demi-écrémé, pain de mie, lessive…"
    />

    <Box sx={rowSx}>
      <SelectInput
        source="category"
        label="Rayon"
        choices={CATEGORY_CHOICES}
        validate={required()}
        sx={halfSx}
        helperText="Sert à regrouper la liste par rayon pendant les courses."
      />
      <SelectInput
        source="defaultUnit"
        label="Unité par défaut"
        choices={UNIT_CHOICES}
        sx={halfSx}
        helperText="Proposée à la saisie ; modifiable à chaque ajout."
      />
    </Box>

    <PackagingInputs />

    <FormSection
      title="Où l'acheter"
      description="Quand un magasin est indiqué, l'article part directement dans la bonne liste."
    />

    <Box sx={rowSx}>
      <ReferenceInput source="preferredStore" reference="stores">
        <AutocompleteInput
          label="Magasin habituel"
          optionText="name"
          sx={halfSx}
          helperText="Facultatif."
        />
      </ReferenceInput>
      <ReferenceInput source="fallbackStore" reference="stores">
        <AutocompleteInput
          label="À défaut"
          optionText="name"
          sx={halfSx}
          helperText="Le magasin de repli quand le premier ne l'a pas."
        />
      </ReferenceInput>
    </Box>

    <NumberInput
      source="shelfLifeDays"
      label="Se garde (jours)"
      sx={halfSx}
      helperText="Facultatif — sert à proposer le réachat au bon moment."
    />
  </SimpleForm>
)
