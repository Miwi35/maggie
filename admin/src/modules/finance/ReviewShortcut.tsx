import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import { Link as RouterLink, useLocation } from 'react-router-dom'
import { REVIEW_ACTIVITY_PATH } from './reviewActivity'
import type { ReviewLaunchState } from './reviewActivity'

/** « Revue ce soir »: launches the review activity (MAG-456); quitting it comes back here. */
export const ReviewShortcut = () => {
  const location = useLocation()
  const state: ReviewLaunchState = { from: location.pathname }

  return (
    <Box sx={{ display: 'flex', mb: 2 }}>
      <Button
        component={RouterLink}
        to={REVIEW_ACTIVITY_PATH}
        state={state}
        variant="contained"
        size="small"
        data-testid="review-shortcut"
      >
        Revue ce soir
      </Button>
    </Box>
  )
}
