import {
  Edit,
  SimpleForm,
  TextInput,
  NumberInput,
  SelectInput,
  ReferenceInput,
  AutocompleteInput,
  required,
} from 'react-admin'

const categoryChoices = [
  { id: 'produce', name: 'Fruits & Légumes' },
  { id: 'dairy', name: 'Produits laitiers' },
  { id: 'meat', name: 'Viandes' },
  { id: 'fish', name: 'Poissons' },
  { id: 'grain', name: 'Céréales' },
  { id: 'spice', name: 'Épices' },
  { id: 'condiment', name: 'Condiments' },
  { id: 'frozen', name: 'Surgelés' },
  { id: 'beverage', name: 'Boissons' },
  { id: 'household', name: 'Maison' },
  { id: 'hygiene', name: 'Hygiène' },
  { id: 'cleaning', name: 'Entretien' },
  { id: 'other', name: 'Autre' },
]

const unitChoices = [
  { id: 'g', name: 'g' },
  { id: 'kg', name: 'kg' },
  { id: 'ml', name: 'ml' },
  { id: 'l', name: 'l' },
  { id: 'cl', name: 'cl' },
  { id: 'piece', name: 'pièce' },
  { id: 'bunch', name: 'botte' },
  { id: 'can', name: 'boîte' },
  { id: 'bottle', name: 'bouteille' },
  { id: 'pack', name: 'paquet' },
  { id: 'sachet', name: 'sachet' },
]

export const ProductEdit = () => (
  <Edit>
    <SimpleForm>
      <TextInput source="name" label="Nom" validate={required()} fullWidth />
      <SelectInput source="category" label="Catégorie" choices={categoryChoices} validate={required()} />
      <SelectInput source="defaultUnit" label="Unité par défaut" choices={unitChoices} />
      <ReferenceInput source="preferredStore" reference="stores">
        <AutocompleteInput label="Magasin préféré" optionText="name" />
      </ReferenceInput>
      <ReferenceInput source="fallbackStore" reference="stores">
        <AutocompleteInput label="Magasin de repli" optionText="name" />
      </ReferenceInput>
      <NumberInput source="shelfLifeDays" label="Conservation (jours)" />
    </SimpleForm>
  </Edit>
)
