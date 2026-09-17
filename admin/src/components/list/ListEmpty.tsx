import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import { CreateButton, useListContext } from 'react-admin'
import type { ReactNode } from 'react'

interface ListEmptyProps {
  /** What is missing, as the reader would say it: "Aucun compte pour l'instant". */
  title: string
  /** Why it is worth creating one — one sentence, no filler. */
  description: ReactNode
  /** Label of the create button; keep the verb the rest of the screen uses. */
  action?: string
}

/**
 * An empty list is an invitation to act, not a dead end: it says what the
 * screen is for and offers the one thing to do next.
 */
export const ListEmpty = ({ title, description, action = 'Créer' }: ListEmptyProps) => {
  const { filterValues } = useListContext()
  const isFiltered = filterValues != null && Object.keys(filterValues).length > 0

  return (
    <Box sx={{ textAlign: 'center', px: 3, py: 8, maxWidth: 520, mx: 'auto' }}>
      <Typography variant="h6" sx={{ mb: 1 }}>
        {isFiltered ? 'Aucun résultat pour ce filtre' : title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 3 }}>
        {isFiltered ? 'Élargissez ou retirez le filtre pour voir le reste.' : description}
      </Typography>
      {!isFiltered && <CreateButton variant="contained" label={action} icon={<AddIcon />} />}
    </Box>
  )
}

/** Same intent, for panels that are not a react-admin list. */
export const Placeholder = ({
  title,
  description,
  action,
}: {
  title: string
  description: ReactNode
  action?: ReactNode
}) => (
  <Box sx={{ textAlign: 'center', px: 3, py: 5 }}>
    <Typography variant="subtitle1" sx={{ mb: 0.5 }}>
      {title}
    </Typography>
    <Typography variant="body2" color="text.secondary" sx={{ mb: action ? 2 : 0 }}>
      {description}
    </Typography>
    {action}
  </Box>
)
