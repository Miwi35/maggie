import Box from '@mui/material/Box'
import Divider from '@mui/material/Divider'
import Typography from '@mui/material/Typography'
import type { ReactNode } from 'react'

interface FormSectionProps {
  title: string
  description?: ReactNode
  /** First section of a form: no top divider, tighter top margin. */
  first?: boolean
}

/** A titled step inside a form, with a line explaining what it is for. */
export const FormSection = ({ title, description, first = false }: FormSectionProps) => (
  <Box sx={{ width: '100%', mt: first ? 0 : 3, mb: 1 }}>
    {!first && <Divider sx={{ mb: 3 }} />}
    <Typography variant="subtitle2" sx={{ fontWeight: 600 }}>
      {title}
    </Typography>
    {description && (
      <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
        {description}
      </Typography>
    )}
  </Box>
)
