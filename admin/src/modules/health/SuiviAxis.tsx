import Box from '@mui/material/Box'

export interface SuiviAxisProps {
  from: string
  to: string
}

/** The first and the last date of the period under a chart. */
export const SuiviAxis = ({ from, to }: SuiviAxisProps) => (
  <Box aria-hidden="true" sx={(theme) => ({ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: theme.palette.veilleuse.caption })}>
    <span>{from}</span>
    <span>{to}</span>
  </Box>
)
