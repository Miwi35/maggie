import { NumberInput, SelectInput } from 'react-admin'
import { useWatch } from 'react-hook-form'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { FormSection } from '../../components/form/FormSection'
import { UNIT_CHOICES } from './productChoices'
import { packagingLabel } from './packaging'

const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const thirdSx = { flex: '1 1 160px' }

type PackagingValues = { packagingUnit?: string | null; packagingSize?: number | null; packagingSizeUnit?: string | null }

// The API refuses a size without its unit (and the reverse): say it on the field.
const sizeGoesWithItsUnit = (_value: unknown, values: PackagingValues) => {
  const hasSize = values.packagingSize != null
  if (hasSize !== Boolean(values.packagingSizeUnit)) return 'La quantité et son unité vont ensemble.'
  if (hasSize && !values.packagingUnit) return 'Indiquez d\'abord en quoi on l\'achète.'
  return undefined
}

const positive = (value: unknown) =>
  value != null && Number(value) <= 0 ? 'La quantité doit être supérieure à 0.' : undefined

const PackagingPreview = () => {
  const [unit, size, sizeUnit] = useWatch({ name: ['packagingUnit', 'packagingSize', 'packagingSizeUnit'] })
  const label = packagingLabel(unit, size, sizeUnit)

  return (
    <Typography variant="body2" color="text.secondary" data-testid="packaging-preview">
      {label ? `On l'achète en : ${label}` : 'Pas de conditionnement : la liste garde l\'unité de la recette.'}
    </Typography>
  )
}

/** The « Comment on l'achète » section of a product or ingredient form. */
export const PackagingInputs = () => (
  <>
    <FormSection
      title="Comment on l'achète"
      description="Un ingrédient de recette part sur la liste en conditionnements, arrondi au-dessus : 300 g de riz, c'est 1 paquet de 500 g."
    />
    <Box sx={rowSx}>
      <SelectInput
        source="packagingUnit"
        label="Conditionnement"
        choices={UNIT_CHOICES}
        sx={thirdSx}
        helperText="Paquet, bocal, bouteille…"
      />
      <NumberInput
        source="packagingSize"
        label="Contenu"
        validate={[positive, sizeGoesWithItsUnit]}
        sx={thirdSx}
        helperText="Facultatif — 500 pour 500 g."
      />
      <SelectInput
        source="packagingSizeUnit"
        label="Unité du contenu"
        choices={UNIT_CHOICES}
        validate={sizeGoesWithItsUnit}
        sx={thirdSx}
      />
    </Box>
    <PackagingPreview />
  </>
)
