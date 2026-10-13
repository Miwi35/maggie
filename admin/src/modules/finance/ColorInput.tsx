import { useInput } from 'react-admin'
import Box from '@mui/material/Box'
import ButtonBase from '@mui/material/ButtonBase'
import FormHelperText from '@mui/material/FormHelperText'
import FormLabel from '@mui/material/FormLabel'
import CheckIcon from '@mui/icons-material/Check'
import CloseIcon from '@mui/icons-material/Close'

export const COLOR_PALETTE = [
  '#F44336', '#E91E63', '#9C27B0', '#673AB7', '#3F51B5', '#2196F3',
  '#03A9F4', '#00BCD4', '#009688', '#4CAF50', '#8BC34A', '#CDDC39',
  '#FFC107', '#FF9800', '#FF5722', '#795548', '#9E9E9E', '#607D8B',
]

const HEX = /^#[0-9a-fA-F]{6}$/

interface ColorInputProps {
  source: string
  label: string
  helperText?: string
}

const swatchSx = (selected: boolean, fill?: string) => ({
  width: 32,
  height: 32,
  borderRadius: '50%',
  bgcolor: fill,
  border: selected ? '3px solid' : '1px solid',
  borderColor: selected ? 'primary.main' : 'divider',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
})

/**
 * A colour picked, not typed: a palette to click, the browser's own colour picker for any
 * other, and a way to take it away. The value is a `#RRGGBB` code, or null for none.
 */
export const ColorInput = ({ source, label, helperText }: ColorInputProps) => {
  const { field } = useInput({ source })
  const value: string | null = typeof field.value === 'string' && HEX.test(field.value) ? field.value : null
  const same = (a: string) => value !== null && a.toLowerCase() === value.toLowerCase()
  const choices = value !== null && !COLOR_PALETTE.some(same) ? [value, ...COLOR_PALETTE] : COLOR_PALETTE

  return (
    <Box sx={{ width: '100%', mb: 2 }}>
      <FormLabel component="div" sx={{ mb: 1 }}>{label}</FormLabel>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1.5, alignItems: 'center' }}>
        <ButtonBase
          aria-label="Aucune couleur"
          aria-pressed={value === null}
          onClick={() => field.onChange(null)}
          sx={swatchSx(value === null)}
        >
          <CloseIcon fontSize="small" color="action" />
        </ButtonBase>
        {choices.map((hex) => (
          <ButtonBase
            key={hex}
            aria-label={`Couleur ${hex}`}
            aria-pressed={same(hex)}
            onClick={() => field.onChange(hex)}
            sx={swatchSx(same(hex), hex)}
          >
            {same(hex) && <CheckIcon fontSize="small" sx={{ color: 'common.white' }} />}
          </ButtonBase>
        ))}
        <input
          type="color"
          aria-label="Autre couleur"
          value={value ?? '#000000'}
          onChange={(e) => field.onChange(e.target.value.toUpperCase())}
          style={{ width: 36, height: 32, border: 'none', cursor: 'pointer', background: 'transparent', padding: 0 }}
        />
      </Box>
      {helperText && <FormHelperText>{helperText}</FormHelperText>}
    </Box>
  )
}
