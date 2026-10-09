import Box from '@mui/material/Box'
import Checkbox from '@mui/material/Checkbox'
import FormControlLabel from '@mui/material/FormControlLabel'
import { DeleteButton, SaveButton, Toolbar, useInput, useRecordContext } from 'react-admin'
import { useRulePreviewContext } from './rulePreviewContext'

/** Sent with the save, never read back from the record: it is an order, not a property. */
const ApplyToExistingCheckbox = () => {
  const { changeCount } = useRulePreviewContext()
  const { field } = useInput({ source: 'applyToExisting', defaultValue: false })
  const label =
    changeCount === null
      ? 'Appliquer aux transactions existantes'
      : `Appliquer aux transactions existantes (${changeCount})`

  return (
    <FormControlLabel
      label={label}
      control={
        <Checkbox
          name={field.name}
          inputRef={field.ref}
          checked={field.value === true}
          onChange={(e) => field.onChange(e.target.checked)}
          onBlur={field.onBlur}
        />
      }
    />
  )
}

export const CategorizationRuleToolbar = () => {
  const record = useRecordContext()

  return (
    <Toolbar sx={{ justifyContent: 'flex-start', gap: 2, flexWrap: 'wrap' }}>
      <SaveButton />
      <ApplyToExistingCheckbox />
      {record && typeof record.id !== 'undefined' && (
        <Box sx={{ ml: 'auto' }}>
          <DeleteButton />
        </Box>
      )}
    </Toolbar>
  )
}
