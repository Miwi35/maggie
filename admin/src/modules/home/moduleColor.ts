import type { Theme } from '@mui/material/styles'
import type { ModuleName } from '../../theme'

/** The theme only has four module hues: the Accueil's seven modules share them by family. */
const HUES: Record<string, ModuleName> = {
  tasks: 'travail',
  agenda: 'travail',
  grocery: 'cuisine',
  nutrition: 'cuisine',
  health: 'sport',
  finance: 'comptes',
  leisure: 'sport',
}

export const moduleColor = (theme: Theme, module: string): string =>
  theme.palette.module[HUES[module] ?? 'travail']
