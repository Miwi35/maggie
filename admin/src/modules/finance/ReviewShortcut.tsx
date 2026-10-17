import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import { Link as RouterLink } from 'react-router-dom'

/** « Revue ce soir »: the review of the month just ended, one tap away while it is not done. */
export const ReviewShortcut = () => (
  <Box sx={{ display: 'flex', mb: 2 }}>
    <Button
      component={RouterLink}
      to="/finance/monthly-review"
      variant="contained"
      size="small"
      data-testid="review-shortcut"
    >
      Revue ce soir
    </Button>
  </Box>
)
