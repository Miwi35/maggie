import type { ReactNode } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'

/** A card of the centre column: a small label, an aside on the right, then its lines. */
export const BilanCard = ({ label, aside, children, testId }: { label: string; aside?: ReactNode; children: ReactNode; testId?: string }) => (
  <Box component="section" aria-label={label} data-testid={testId} sx={{ borderRadius: 4, bgcolor: 'background.paper', px: 2.5, py: 2, display: 'flex', flexDirection: 'column', gap: 1, minWidth: 0 }}>
    <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 2 }}>
      <Typography component="h3" sx={{ fontSize: 12, fontWeight: 600, letterSpacing: 0.6, textTransform: 'uppercase', color: 'text.secondary' }}>
        {label}
      </Typography>
      {aside !== undefined && (
        <Typography component="span" sx={{ fontSize: 12, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}>
          {aside}
        </Typography>
      )}
    </Box>
    {children}
  </Box>
)

export const BilanError = ({ message, onRetry }: { message: string; onRetry?: () => void }) => (
  <BilanCard label="Erreur">
    <Typography sx={{ fontSize: 15 }}>{message}</Typography>
    {onRetry && (
      <Box component="button" type="button" onClick={onRetry} sx={{ alignSelf: 'flex-start', appearance: 'none', border: 0, bgcolor: 'transparent', color: 'primary.main', font: 'inherit', cursor: 'pointer', p: 0 }}>
        Réessayer
      </Box>
    )}
  </BilanCard>
)
