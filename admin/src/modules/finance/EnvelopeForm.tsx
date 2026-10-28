import Box from '@mui/material/Box'
import {
  SimpleForm,
  TextInput,
  NumberInput,
  SelectInput,
  FormDataConsumer,
  ArrayInput,
  SimpleFormIterator,
  AddItemButton,
  RemoveItemButton,
  required,
  useRecordContext,
  minValue,
  maxValue,
} from 'react-admin'
import { BUDGET_MODE_CHOICES, MONTH_CHOICES, MONTH_LABELS, ONE_OFF, overlappingMonth } from './budgetModes'
import type { Season } from './budgetModes'
import { EnvelopeSplitSection } from './EnvelopeSplitSection'
import { EnvelopeSummary } from './EnvelopeSummary'
import { FormSection } from '../../components/form/FormSection'
import { CategoryInput } from './CategoryInput'

const now = new Date()

/** Keep the form readable instead of stretching inputs across the page. */
const formSx = { maxWidth: 680 }
const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

interface EnvelopeFormProps {
  /** Create pre-fills the current period; edit keeps what is stored. */
  withDefaults?: boolean
}

/**
 * A budget of a category and an event are not the same thing: once created,
 * an envelope switches between monthly and annual, never to or from one-off.
 */
const ModeInput = ({ withDefaults }: EnvelopeFormProps) => {
  const record = useRecordContext()
  const storedOneOff = record?.mode === ONE_OFF
  const choices = withDefaults || !record
    ? BUDGET_MODE_CHOICES
    : BUDGET_MODE_CHOICES.filter((c) => (c.id === ONE_OFF) === storedOneOff)

  return (
    <SelectInput
      source="mode"
      label="Type d'enveloppe"
      choices={choices}
      defaultValue={withDefaults ? 'monthly' : undefined}
      validate={required()}
      disabled={!withDefaults && storedOneOff}
      fullWidth
    />
  )
}

export const toCents = (v?: number) => (v == null || Number.isNaN(v) ? null : Math.round(v * 100))
export const toEuros = (v?: number) => (v == null ? null : v / 100)

/** The API refuses two seasons sharing a month (400): say which before sending. */
export const validateSeasons = (seasons?: Array<Partial<Season> | null>) => {
  const month = overlappingMonth(seasons ?? [])
  return month == null
    ? undefined
    : `Deux saisons couvrent ${MONTH_LABELS[month]?.toLowerCase() ?? month} : une saison ne peut pas en chevaucher une autre.`
}

/**
 * A monthly envelope budgeted differently over part of the year (MAG-415):
 * Cinéma 45 € from November to February, the envelope's amount otherwise.
 */
export const SeasonsInput = () => (
  <>
    <FormSection title="Saisons" />
    <ArrayInput source="seasonalAmounts" label={false} defaultValue={[]} validate={validateSeasons}>
      <SimpleFormIterator
        inline
        disableReordering
        disableClear
        addButton={<AddItemButton label="Ajouter une saison" />}
        removeButton={<RemoveItemButton label="Retirer la saison" />}
      >
        <SelectInput source="fromMonth" label="De" choices={MONTH_CHOICES} validate={required()} sx={{ minWidth: 140 }} />
        <SelectInput source="toMonth" label="À" choices={MONTH_CHOICES} validate={required()} sx={{ minWidth: 140 }} />
        <NumberInput
          source="amountCents"
          label="Montant (€)"
          validate={required()}
          format={toEuros}
          parse={toCents}
          sx={{ maxWidth: 160 }}
        />
      </SimpleFormIterator>
    </ArrayInput>
  </>
)

/** The envelope's amount, in euros on screen and cents on the wire. */
export const AmountInput = () => (
  <NumberInput
    source="amountCents"
    label="Montant de l'enveloppe (€)"
    validate={required()}
    format={toEuros}
    parse={toCents}
    sx={halfSx}
  />
)

/** Alert threshold in percent, 1 to 99; empty means 80 %. */
export const AlertThresholdInput = () => (
  <NumberInput
    source="alertThresholdPercent"
    label="Seuil d'alerte (%)"
    placeholder="80"
    min={1}
    max={99}
    validate={[minValue(1), maxValue(99)]}
    sx={halfSx}
  />
)

const AmountInputs = ({ withDefaults }: EnvelopeFormProps) => (
  <Box sx={rowSx}>
    <AmountInput />
    <TextInput
      source="currency"
      label="Devise"
      defaultValue={withDefaults ? 'EUR' : undefined}
      sx={halfSx}
    />
  </Box>
)

const CategoryBudgetFields = ({ withDefaults }: EnvelopeFormProps) => (
  <>
    <FormSection title="Ce que vous budgétez" />

    <CategoryInput
      source="category"
      label="Catégorie"
      validate={required()}
      fullWidth
    />

    <AmountInputs withDefaults={withDefaults} />

    <FormSection title="Période couverte" />

    <Box sx={rowSx}>
      <NumberInput
        source="year"
        label="Année"
        defaultValue={withDefaults ? now.getFullYear() : undefined}
        validate={required()}
        sx={halfSx}
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
            />
          )
        }
      </FormDataConsumer>
    </Box>

    <FormDataConsumer>{({ formData }) => formData.mode !== 'annual' && <SeasonsInput />}</FormDataConsumer>
  </>
)

const OneOffFields = ({ withDefaults }: EnvelopeFormProps) => (
  <>
    <FormSection title="L'événement" />

    <TextInput
      source="name"
      label="Nom de l'événement"
      validate={required()}
      fullWidth
    />

    <AmountInputs withDefaults={withDefaults} />
  </>
)

export const EnvelopeForm = ({ withDefaults = false }: EnvelopeFormProps) => (
  <SimpleForm sx={formSx}>
    <FormSection first title="Type d'enveloppe" />

    <ModeInput withDefaults={withDefaults} />

    <FormDataConsumer>
      {({ formData }) =>
        formData.mode === ONE_OFF ? (
          <OneOffFields withDefaults={withDefaults} />
        ) : (
          <CategoryBudgetFields withDefaults={withDefaults} />
        )
      }
    </FormDataConsumer>

    <EnvelopeSplitSection />

    <FormSection title="Alerte" />

    <AlertThresholdInput />

    <FormDataConsumer>
      {({ formData }) => (
        <EnvelopeSummary
          category={formData.category as string | undefined}
          mode={formData.mode as string | undefined}
          name={formData.name as string | undefined}
          year={formData.year as number | undefined}
          month={formData.month as number | null | undefined}
          amountCents={formData.amountCents as number | undefined}
          currency={formData.currency as string | undefined}
        />
      )}
    </FormDataConsumer>
  </SimpleForm>
)
