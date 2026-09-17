import Box from '@mui/material/Box'
import {
  SimpleForm,
  TextInput,
  NumberInput,
  SelectInput,
  ReferenceInput,
  AutocompleteInput,
  FormDataConsumer,
  required,
} from 'react-admin'
import { BUDGET_MODE_CHOICES, MONTH_CHOICES } from './budgetModes'
import { EnvelopeSummary } from './EnvelopeSummary'
import { FormSection } from '../../components/form/FormSection'

const now = new Date()

/** Keep the form readable instead of stretching inputs across the page. */
const formSx = { maxWidth: 680 }
const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

interface EnvelopeFormProps {
  /** Create pre-fills the current period; edit keeps what is stored. */
  withDefaults?: boolean
}

export const EnvelopeForm = ({ withDefaults = false }: EnvelopeFormProps) => (
  <SimpleForm sx={formSx}>
    <FormSection
      first
      title="Ce que vous budgétez"
      description="Une enveloppe plafonne les dépenses d'une catégorie sur une période. Les transactions de cette catégorie viennent s'en déduire automatiquement."
    />

    <ReferenceInput source="category" reference="categories">
      <AutocompleteInput
        label="Catégorie"
        optionText="name"
        validate={required()}
        fullWidth
        helperText="Seules les dépenses classées dans cette catégorie sont décomptées."
      />
    </ReferenceInput>

    <Box sx={rowSx}>
      <NumberInput
        source="amountCents"
        label="Montant de l'enveloppe (€)"
        validate={required()}
        format={(v?: number) => (v == null ? null : v / 100)}
        parse={(v?: number) => (v == null || Number.isNaN(v) ? null : Math.round(v * 100))}
        sx={halfSx}
        helperText="Ce que vous vous autorisez à dépenser sur toute la période."
      />
      <TextInput
        source="currency"
        label="Devise"
        defaultValue={withDefaults ? 'EUR' : undefined}
        sx={halfSx}
        helperText="Code ISO 4217 : EUR, CHF, USD…"
      />
    </Box>

    <FormSection
      title="Période couverte"
      description="La période sur laquelle ce montant s'applique — c'est elle qui détermine quand le compteur repart de zéro."
    />

    <SelectInput
      source="mode"
      label="Rythme du budget"
      choices={BUDGET_MODE_CHOICES}
      defaultValue={withDefaults ? 'monthly' : undefined}
      validate={required()}
      fullWidth
      helperText="Mensuel : un budget qui se renouvelle chaque mois (courses, essence). Annuel : une enveloppe unique pour l'année entière (voyages, cadeaux, matériel)."
    />

    <Box sx={rowSx}>
      <NumberInput
        source="year"
        label="Année"
        defaultValue={withDefaults ? now.getFullYear() : undefined}
        validate={required()}
        sx={halfSx}
        helperText="L'année à laquelle cette enveloppe appartient."
      />
      <FormDataConsumer>
        {({ formData }) =>
          formData.mode !== 'annual' && (
            <SelectInput
              source="month"
              label="Mois budgété"
              choices={MONTH_CHOICES}
              defaultValue={withDefaults ? now.getMonth() + 1 : undefined}
              validate={required()}
              sx={halfSx}
              helperText="Le mois que ce montant couvre. Créez une enveloppe par mois à budgéter."
            />
          )
        }
      </FormDataConsumer>
    </Box>

    <FormDataConsumer>
      {({ formData }) => (
        <EnvelopeSummary
          category={formData.category as string | undefined}
          mode={formData.mode as string | undefined}
          year={formData.year as number | undefined}
          month={formData.month as number | null | undefined}
          amountCents={formData.amountCents as number | undefined}
          currency={formData.currency as string | undefined}
        />
      )}
    </FormDataConsumer>
  </SimpleForm>
)
