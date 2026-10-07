import Box from '@mui/material/Box'
import { alpha } from '@mui/material/styles'
import type { ReactNode } from 'react'
import type { ModuleName } from '../../theme'

interface ModuleIconProps {
  module: ModuleName
  children: ReactNode
}

/** A menu icon on its module's hue at 16 % — the same 24px box as a bare icon, so the menu keeps its density. */
export const ModuleIcon = ({ module, children }: ModuleIconProps) => (
  <Box
    data-module={module}
    sx={(theme) => ({
      width: 24,
      height: 24,
      borderRadius: `${theme.shape.borderRadius}px`,
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      color: theme.palette.module[module],
      backgroundColor: alpha(theme.palette.module[module], 0.16),
      '& .MuiSvgIcon-root': { fontSize: 16, fill: 'currentColor' },
    })}
  >
    {children}
  </Box>
)
