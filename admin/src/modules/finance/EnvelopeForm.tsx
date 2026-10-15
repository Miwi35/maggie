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
import { EnvelopeSummary } from './EnvelopeSummary'
import { FormSection } from '../../components/form/FormSection'
import { CategoryInput } from './CategoryInput'

const now = new Date()

/** Keep the form readable instead of stretching inputs across the page. */
const formSx = { maxWidth: 680 }
const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
const halfSx = { flex: '1 1 240px' }

const MODE_HELP =
  "Mensuel : un budget qui se renouvelle chaque mois (courses, essence). Annuel : une enveloppe unique pour l'année entière, à part du budget du mois (voyages, cadeaux, matériel). Ponctuel : un événement (Hellfest 2026, des vacances), sans catégorie ni période — vous y attribuez les dépenses à la main depuis la transaction."

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
      helperText={MODE_HELP}
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
    <FormSection
      title="Saisons"
      description="Un autre montant sur une partie de l'année (Cinéma 45 € de novembre à février). Les mois hors saison gardent le montant de l'enveloppe ; la reconduction crée chaque mois au montant de sa saison."
    />
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
export const AmountInput = ({ helperText }: { helperText?: string }) => (
  <NumberInput
    source="amountCents"
    label="Montant de l'enveloppe (€)"
    validate={required()}
    format={toEuros}
    parse={toCents}
    sx={halfSx}
    helperText={helperText}
  />
)

/** Alert threshold in percent, 1 to 99; empty means 80 %. */
export const AlertThresholdInput = () => (
  <NumberInput
    source="alertThresholdPercent"
    label="Seuil d'alerte (%)"
    min={1}
    max={99}
    validate={[minValue(1), maxValue(99)]}
    sx={halfSx}
    helperText="Entre 1 et 99. Vide : 80 %."
  />
)

const AmountInputs = ({ withDefaults }: EnvelopeFormProps) => (
  <Box sx={rowSx}>
    <AmountInput helperText="Ce que vous vous autorisez à dépenser sur toute la période." />
    <TextInput
      source="currency"
      label="Devise"
      defaultValue={withDefaults ? 'EUR' : undefined}
      sx={halfSx}
      helperText="Code ISO 4217 : EUR, CHF, USD…"
    />
  </Box>
)

const CategoryBudgetFields = ({ withDefaults }: EnvelopeFormProps) => (
  <>
    <FormSection
      title="Ce que vous budgétez"
      description="Une enveloppe plafonne les dépenses d'une catégorie sur une période. Les transactions de cette catégorie viennent s'en déduire automatiquement."
    />

    <CategoryInput
      source="category"
      label="Catégorie"
      validate={required()}
      fullWidth
      helperText="Seules les dépenses classées dans cette catégorie sont décomptées."
    />

    <AmountInputs withDefaults={withDefaults} />

    <FormSection
      title="Période couverte"
      description="La période sur laquelle ce montant s'applique — c'est elle qui détermine quand le compteur repart de zéro."
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

    <FormDataConsumer>{({ formData }) => formData.mode !== 'annual' && <SeasonsInput />}</FormDataConsumer>
  </>
)

const OneOffFields = ({ withDefaults }: EnvelopeFormProps) => (
  <>
    <FormSection
      title="L'événement"
      description="Une enveloppe ponctuelle n'a ni catégorie ni période. Les dépenses que vous y attribuez gardent leur catégorie mais sortent des enveloppes du mois."
    />

    <TextInput
      source="name"
      label="Nom de l'événement"
      validate={required()}
      fullWidth
      helperText="Hellfest 2026, Vacances en Bretagne…"
    />

    <AmountInputs withDefaults={withDefaults} />
  </>
)

export const EnvelopeForm = ({ withDefaults = false }: EnvelopeFormProps) => (
  <SimpleForm sx={formSx}>
    <FormSection
      first
      title="Type d'enveloppe"
      description="Le budget du mois, une enveloppe annuelle à part, ou un événement ponctuel."
    />

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

    <FormSection
      title="Alerte"
      description="Après une synchro bancaire qui fait passer l'enveloppe au-delà de ce seuil, Maggie vous prévient — une fois par période, puis une seconde fois à 100 %."
    />

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
