import Autocomplete from '@mui/material/Autocomplete'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import FormControlLabel from '@mui/material/FormControlLabel'
import MenuItem from '@mui/material/MenuItem'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { useGetList, useRecordContext } from 'react-admin'
import { useController, useWatch } from 'react-hook-form'
import type { RaRecord } from 'react-admin'
import { categoryLabel, orderedCategories } from './categoryTree'
import type { CategoryNode } from './categoryTree'
import { KIND_CHOICES, NO_QUALIFICATION, kindHelp } from './qualificationKinds'
import type { QualificationKind, QualificationValue, Sense } from './qualificationKinds'

export interface QualificationChange {
  category: string | null
  qualification: QualificationValue
}

interface QualificationProps {
  category: string | null
  qualification: QualificationValue
  onChange: (next: QualificationChange) => void
  /** The sign of the amount, which decides what « remboursement » means. */
  sense?: Sense
  required?: boolean
  error?: string | null
  categoryHelperText?: string
}

/**
 * The one place a category and its qualification are chosen — the transaction
 * sheet, a series and a rule all use it. Every category is offered, whatever the
 * sign of the amount: the sense never comes from the category.
 */
export const Qualification = ({
  category,
  qualification,
  onChange,
  sense = 'unknown',
  required = false,
  error = null,
  categoryHelperText,
}: QualificationProps) => {
  const { data = [] } = useGetList<CategoryNode & RaRecord>('categories', {
    pagination: { page: 1, perPage: 200 },
    sort: { field: 'name', order: 'ASC' },
  })
  const options = orderedCategories(data)
  const selected = options.find((c) => c.id === category) ?? null

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, width: '100%' }} data-testid="qualification">
      <Autocomplete
        options={options}
        value={selected}
        getOptionLabel={(option) => categoryLabel(option)}
        isOptionEqualToValue={(option, value) => option.id === value.id}
        onChange={(_, next) => onChange({ category: next ? String(next.id) : null, qualification })}
        renderInput={(params) => (
          <TextField
            {...params}
            label="Catégorie"
            required={required}
            error={Boolean(error)}
            helperText={error ?? categoryHelperText ?? 'Toutes les catégories sont proposées, quel que soit le sens du montant.'}
          />
        )}
        fullWidth
      />
      <FormControlLabel
        control={
          <Switch
            checked={qualification.mandatory}
            onChange={(event) => onChange({ category, qualification: { ...qualification, mandatory: event.target.checked } })}
          />
        }
        label="Obligatoire"
      />
      <TextField
        select
        label="Type"
        value={qualification.kind}
        onChange={(event) =>
          onChange({ category, qualification: { ...qualification, kind: event.target.value as QualificationKind } })
        }
        helperText={kindHelp(qualification.kind, sense)}
        fullWidth
      >
        {KIND_CHOICES.map((choice) => (
          <MenuItem key={choice.id} value={choice.id}>
            {choice.name}
          </MenuItem>
        ))}
      </TextField>
    </Box>
  )
}

/** The same component, bound to the `category` and `qualification` fields of a react-admin form. */
export const QualificationInput = ({
  sense,
  required,
  categoryHelperText,
}: Pick<QualificationProps, 'sense' | 'required' | 'categoryHelperText'>) => {
  const { field: categoryField, fieldState } = useController({
    name: 'category',
    rules: required ? { required: 'Choisissez une catégorie' } : undefined,
  })
  const { field: qualificationField } = useController({ name: 'qualification', defaultValue: NO_QUALIFICATION })

  return (
    <Qualification
      category={(categoryField.value as string | null | undefined) || null}
      qualification={(qualificationField.value as QualificationValue | null | undefined) ?? NO_QUALIFICATION}
      onChange={(next) => {
        categoryField.onChange(next.category)
        qualificationField.onChange(next.qualification)
      }}
      sense={sense}
      required={required}
      error={fieldState.error?.message ?? null}
      categoryHelperText={categoryHelperText}
    />
  )
}

/** « posée par la série Crédit auto » / « à la main »: the line says who qualified it. */
export const QualificationProvenance = ({ sentence }: { sentence: string | null }) =>
  sentence ? (
    <Typography variant="body2" color="text.secondary" data-testid="qualification-provenance">
      Qualification : {sentence}
    </Typography>
  ) : null

/** What saving an operation will do to the lines it qualified: those qualified by hand never move. */
export const RequalifiedNotice = ({ count }: { count: number | null }) =>
  count === null || count === 0 ? null : (
    <Alert severity="info">
      {count === 1 ? '1 transaction rattachée sera requalifiée' : `${count} transactions rattachées seront requalifiées`}{' '}
      (celles que vous avez qualifiées à la main ne bougent pas).
    </Alert>
  )

/** The notice of a react-admin form: counts the lines `filterKey` points at once the category or qualification differs from the record's. */
export const RequalifiedNoticeInput = ({ filterKey }: { filterKey: 'qualifiedBySeries' | 'qualifiedByRule' }) => {
  const record = useRecordContext()
  const editing = record?.id !== undefined
  const category = useWatch({ name: 'category' }) as string | null | undefined
  const qualification = useWatch({ name: 'qualification' }) as QualificationValue | null | undefined
  const scope = filterKey === 'qualifiedBySeries' ? { qualifiedBySeries: record?.id } : { qualifiedByRule: record?.id }
  const { total } = useGetList(
    'transactions',
    { pagination: { page: 1, perPage: 1 }, filter: scope },
    { enabled: editing },
  )
  const before = (record?.qualification as QualificationValue | null | undefined) ?? NO_QUALIFICATION
  const after = qualification ?? NO_QUALIFICATION
  const changed =
    (category || null) !== ((record?.category as string | null | undefined) || null) ||
    after.mandatory !== before.mandatory ||
    after.kind !== before.kind

  return <RequalifiedNotice count={editing && changed ? (total ?? 0) : null} />
}
