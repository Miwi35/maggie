import type { ReactNode } from 'react'
import Box from '@mui/material/Box'

/** The widest a sheet is read: past it, a name and its quantity are too far apart to be linked by eye. */
export const READING_WIDTH = 1100

/**
 * The frame of a reading page (a recipe sheet): capped at the reading width and centred. Lists,
 * tables and the Gantt do not use it and keep the whole width.
 */
export const ReadingPage = ({ children }: { children: ReactNode }) => (
  <Box data-testid="reading-page" sx={{ p: 2, width: '100%', maxWidth: READING_WIDTH, mx: 'auto' }}>
    {children}
  </Box>
)
