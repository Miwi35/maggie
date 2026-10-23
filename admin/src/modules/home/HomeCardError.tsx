import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'

/** A card whose read failed: said in words, never the error's own, with the way to try again. */
export const HomeCardError = ({ onRetry }: { onRetry: () => void }) => (
  <Stack role="alert" direction="row" spacing={1} alignItems="center">
    <Typography variant="body2" color="text.secondary">
      Impossible de charger cette carte.
    </Typography>
    <Button size="small" onClick={onRetry}>
      Réessayer
    </Button>
  </Stack>
)
