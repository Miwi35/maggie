import Button from '@mui/material/Button'
import Card from '@mui/material/Card'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import { Link as RouterLink, useLocation } from 'react-router-dom'
import { REVIEW_ACTIVITY_PATH, reviewTitle } from './reviewActivity'
import type { ReviewLaunchState } from './reviewActivity'

/** The review waiting for tonight, as an action card: its title, its time, one « Lancer »; quitting the activity comes back here. */
export const ReviewActionCard = ({ now = new Date() }: { now?: Date }) => {
  const location = useLocation()
  const state: ReviewLaunchState = { from: location.pathname }

  return (
    <Card data-testid="review-action-card" sx={{ mb: 2 }}>
      <Stack direction="row" alignItems="center" spacing={2} sx={{ px: 2, py: 1.5 }}>
        <Stack sx={{ flexGrow: 1 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            {reviewTitle(now)}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            ce soir · 20 h
          </Typography>
        </Stack>
        <Button component={RouterLink} to={REVIEW_ACTIVITY_PATH} state={state} variant="contained" size="small">
          Lancer
        </Button>
      </Stack>
    </Card>
  )
}
