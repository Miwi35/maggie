import {
  SimpleForm,
  TextInput,
  NumberInput,
  BooleanInput,
  SelectInput,
  required,
} from 'react-admin'
import type { ReactElement } from 'react'
import Box from '@mui/material/Box'
import { FormSection } from '../../components/form/FormSection'
import { CATEGORY_CHOICES, STOCK_STATE_CHOICES, UNIT_CHOICES } from './productChoices'
import { PackagingInputs } from './PackagingInputs'
import { ProductRecurrence } from './ProductRecurrence'
import { ProductStoreInputs } from './ProductStoreInputs'
import { SeasonMonthsInput } from './SeasonMonthsInput'

const formSx = { maxWidth: 680 }
const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

export const ProductForm = ({ toolbar }: { toolbar?: ReactElement }) => (
  <SimpleForm sx={formSx} toolbar={toolbar}>
    <FormSection first title="Le produit" />

    <TextInput
      source="name"
      label="Nom"
      validate={required()}
      fullWidth
    />

    <Box sx={rowSx}>
      <SelectInput
        source="category"
        label="Catégorie"
        choices={CATEGORY_CHOICES}
        validate={required()}
        sx={halfSx}
      />
      <SelectInput
        source="defaultUnit"
        label="Unité par défaut"
        choices={UNIT_CHOICES}
        sx={halfSx}
      />
    </Box>

    <PackagingInputs />

    <FormSection title="Saison" />

    <SeasonMonthsInput />

    <ProductStoreInputs />

    <NumberInput
      source="shelfLifeDays"
      label="Se garde (jours)"
      sx={halfSx}
    />

    <FormSection title="Ce qu'il m'en reste" />

    <Box sx={rowSx}>
      <SelectInput
        source="stockState"
        label="État du stock"
        choices={STOCK_STATE_CHOICES}
        defaultValue="in_stock"
        validate={required()}
        sx={halfSx}
      />
      <NumberInput
        source="restockQuantity"
        label="Quantité de réapprovisionnement"
        min={0}
        step={1}
        sx={halfSx}
      />
    </Box>

    <BooleanInput
      source="autoRestock"
      label="Réapprovisionnement automatique"
      defaultValue={false}
    />

    <ProductRecurrence />
  </SimpleForm>
)
