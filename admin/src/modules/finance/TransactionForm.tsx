import Box from '@mui/material/Box'
import ToggleButton from '@mui/material/ToggleButton'
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup'
import {
  SimpleForm,
  TextInput,
  NumberInput,
  DateInput,
  SelectInput,
  BooleanInput,
  ReferenceInput,
  AutocompleteInput,
  required,
  useGetList,
} from 'react-admin'
import { useController, useFormContext, useWatch } from 'react-hook-form'
import { FormSection } from '../../components/form/FormSection'
import { centsInput } from './categorizationRules'
import {
  EXCEPTIONAL_HELP,
  EXCEPTIONAL_LABEL,
  STATUS_CHOICES,
  STATUS_HELP,
  STATUS_SECTION_TITLE,
  natureOfAmount,
  signedAmountCents,
  statusLabel,
} from './transactionStatuses'
import type { RaRecord } from 'react-admin'
import type { TransactionNature } from './transactionStatuses'
import { categoryLabel, orderedCategories } from './categoryTree'
import type { CategoryNode } from './categoryTree'

const formSx = { maxWidth: 680 }
export const rowSx = { display: 'flex', gap: 2, flexWrap: 'wrap', width: '100%' }
export const halfSx = { flex: '1 1 240px' }

interface TransactionFormProps {
  withDefaults?: boolean
}

/**
 * `nature` lives in the form only: the API knows it through the sign of the
 * amount, which the form types without. Both are put back together here,
 * before the request leaves.
 */
export const toTransactionPayload = (data: Record<string, unknown>) => {
  const { nature, ...rest } = data
  return {
    ...rest,
    amountCents: signedAmountCents(
      rest.amountCents as number | undefined,
      (nature as TransactionNature | undefined) ?? 'expense',
    ),
  }
}

/** The amount is typed in euros and without a sign — the nature carries it. */
export const absoluteCentsInput = {
  format: (v?: number) => (v == null ? null : Math.abs(v) / 100),
  parse: (v?: number) => {
    const cents = centsInput.parse(v)
    return cents == null ? null : Math.abs(cents)
  },
}

const NATURE_HELP: Record<TransactionNature, string> = {
  expense: 'De l’argent qui sort du compte.',
  income: 'De l’argent qui entre sur le compte — un salaire, une rente, un remboursement.',
}

export const NatureInput = () => {
  const { setValue, getValues } = useFormContext()
  const { field } = useController({ name: 'nature' })
  const nature: TransactionNature = field.value ?? 'expense'

  const choose = (_: unknown, next: TransactionNature | null) => {
    // An exclusive group reports null when the selected button is pressed again.
    if (next === null || next === nature) return

    field.onChange(next)

    // "Engagée" has no meaning for a recette; the closest state that does is "Attendue".
    if (next === 'income' && getValues('status') === 'committed') {
      setValue('status', 'planned', { shouldDirty: true })
    }
  }

  return (
    <Box sx={{ width: '100%', mb: 2 }}>
      <ToggleButtonGroup
        exclusive
        fullWidth
        color="primary"
        value={nature}
        onChange={choose}
        aria-label="Nature de la transaction"
      >
        <ToggleButton value="expense">Dépense</ToggleButton>
        <ToggleButton value="income">Recette</ToggleButton>
      </ToggleButtonGroup>
      <Box sx={{ mt: 0.5, typography: 'caption', color: 'text.secondary' }}>{NATURE_HELP[nature]}</Box>
    </Box>
  )
}

/** The account, label, amount and date: what identifies a line, whatever else it is. */
export const TransactionHeaderFields = ({ withCurrency = false }: { withCurrency?: boolean }) => (
  <>
    <ReferenceInput source="account" reference="accounts">
      <AutocompleteInput
        label="Compte"
        optionText="name"
        validate={required()}
        fullWidth
        helperText="Le compte sur lequel la transaction est passée."
      />
    </ReferenceInput>

    <TextInput
      source="label"
      label="Libellé"
      validate={required()}
      fullWidth
      helperText="Ce que vous lirez sur votre relevé — c'est aussi ce que lisent les règles de catégorisation."
    />

    <Box sx={rowSx}>
      <NumberInput
        source="amountCents"
        label="Montant (€)"
        validate={[
          required(),
          (v: number | null) => (v === 0 ? 'Saisissez un montant supérieur à 0' : undefined),
        ]}
        {...absoluteCentsInput}
        sx={halfSx}
        helperText="Toujours positif : le choix Dépense / Recette donne le sens."
      />
      <DateInput
        source="bookedAt"
        label="Date"
        validate={required()}
        sx={halfSx}
        helperText="La date à laquelle l'argent bouge, pas celle de l'événement."
      />
      {withCurrency && <CurrencyInput sx={halfSx} />}
    </Box>
  </>
)

/** Every category, whatever the sign: the sense of a line comes from its amount, not from its category. */
export const CategoryChoiceInput = ({ sx }: { sx?: object }) => {
  const { data: categories = [] } = useGetList<CategoryNode & RaRecord>('categories', {
    pagination: { page: 1, perPage: 200 },
    sort: { field: 'name', order: 'ASC' },
  })

  return (
    <AutocompleteInput
      source="category"
      label="Catégorie"
      choices={orderedCategories(categories)}
      optionText={categoryLabel}
      sx={sx}
      fullWidth={sx === undefined}
      helperText="Facultatif. Toutes les catégories sont proposées, quel que soit le sens du montant."
    />
  )
}

export const CurrencyInput = ({ withDefaults, sx }: { withDefaults?: boolean; sx?: object }) => (
  <TextInput
    source="currency"
    label="Devise"
    defaultValue={withDefaults ? 'EUR' : undefined}
    sx={sx}
    helperText="Code ISO 4217."
  />
)

export const StatusInput = ({ withDefaults }: { withDefaults?: boolean }) => {
  const nature: TransactionNature = useWatch({ name: 'nature' }) ?? 'expense'
  const status = useWatch({ name: 'status' }) as string | undefined

  // A status the nature no longer offers: an income saved as "Engagée" before
  // the form knew better must not open on a blank field.
  const statusChoices = STATUS_CHOICES[nature].some((c) => c.id === status)
    ? STATUS_CHOICES[nature]
    : [...STATUS_CHOICES[nature], ...(status ? [{ id: status, name: statusLabel(status, nature === 'income' ? 1 : -1) }] : [])]

  return (
    <SelectInput
      source="status"
      label="Statut"
      choices={statusChoices}
      defaultValue={withDefaults ? 'spent' : undefined}
      validate={required()}
      fullWidth
      helperText={STATUS_HELP[nature]}
    />
  )
}

export const ExceptionalInput = ({ withDefaults }: { withDefaults?: boolean }) => {
  const nature: TransactionNature = useWatch({ name: 'nature' }) ?? 'expense'

  return (
    <BooleanInput
      source="isExceptional"
      label={EXCEPTIONAL_LABEL[nature]}
      defaultValue={withDefaults ? false : undefined}
      helperText={EXCEPTIONAL_HELP[nature]}
    />
  )
}

const TransactionFields = ({ withDefaults }: TransactionFormProps) => {
  const nature: TransactionNature = useWatch({ name: 'nature' }) ?? 'expense'

  return (
    <>
      <NatureInput />

      <TransactionHeaderFields />

      <FormSection
        title="Classement"
        description="Sans catégorie, les règles automatiques s'en chargeront — et sinon, la revue mensuelle vous la proposera."
      />

      <Box sx={rowSx}>
        <CategoryChoiceInput sx={halfSx} />
        <CurrencyInput withDefaults={withDefaults} sx={halfSx} />
      </Box>

      <FormSection
        title={STATUS_SECTION_TITLE[nature]}
        description={`C'est ce qui décide du poids de la ${nature === 'income' ? 'recette' : 'dépense'} sur l'enveloppe de sa catégorie.`}
      />

      <StatusInput withDefaults={withDefaults} />

      <ExceptionalInput withDefaults={withDefaults} />
    </>
  )
}

export const TransactionForm = ({ withDefaults = false }: TransactionFormProps) => (
  <SimpleForm
    sx={formSx}
    // A new line opens as a dépense; an existing one is what its sign says.
    defaultValues={(record?: RaRecord) => ({
      nature: natureOfAmount(record?.amountCents as number | undefined),
    })}
  >
    <FormSection
      first
      title="La transaction"
      description="Dépense ou recette d'abord : c'est ce qui décide des états proposés."
    />
    <TransactionFields withDefaults={withDefaults} />
  </SimpleForm>
)
