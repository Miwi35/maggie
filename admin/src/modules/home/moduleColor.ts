import type { Theme } from '@mui/material/styles'
import type { ModuleName } from '../../theme'

/** The Accueil's modules read their own hue; those without one (leisure, health) share their family's. */
const HUES: Record<string, ModuleName> = {
  tasks: 'travail',
  agenda: 'agenda',
  grocery: 'courses',
  cuisine: 'cuisine',
  health: 'sport',
  finance: 'comptes',
  leisure: 'sport',
}

export const moduleColor = (theme: Theme, module: string): string =>
  theme.palette.module[HUES[module] ?? 'travail']
