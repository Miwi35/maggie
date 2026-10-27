import type { ReactElement } from 'react'
import { MenuItemLink, useSidebarState } from 'react-admin'
import { useLocation } from 'react-router-dom'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { alpha } from '@mui/material/styles'
import CalendarMonthIcon from '@mui/icons-material/CalendarMonth'
import HomeIcon from '@mui/icons-material/Home'
import RestaurantMenuIcon from '@mui/icons-material/RestaurantMenu'
import SettingsIcon from '@mui/icons-material/Settings'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import TaskAltIcon from '@mui/icons-material/TaskAlt'
import AccountBalanceWalletIcon from '@mui/icons-material/AccountBalanceWallet'
import { ModuleIcon } from './ModuleIcon'
import { MaggieAvatar } from '../maggie/MaggieAvatar'
import type { ModuleName } from '../../theme'
import { useAgentName } from '../../hooks/useAgentName'
import {
  AGENT_SETTINGS_PATH,
  HOME_LABEL,
  SETTINGS_LABEL,
  SIDEBAR,
  sidebarEntryOf,
  sidebarModuleOf,
} from './navigation'
import type { SidebarModule } from './navigation'
import type { Theme } from '@mui/material/styles'

/** Each module's glyph and, when the design tokens have one, its hue; without a hue the accent is worn. */
const MODULE_LOOKS: Record<string, { icon: ReactElement; hue?: ModuleName }> = {
  [HOME_LABEL]: { icon: <HomeIcon /> },
  Agenda: { icon: <CalendarMonthIcon /> },
  Tâches: { icon: <TaskAltIcon />, hue: 'taches' },
  Courses: { icon: <ShoppingCartIcon />, hue: 'cuisine' },
  Cuisine: { icon: <RestaurantMenuIcon />, hue: 'cuisine' },
  Finance: { icon: <AccountBalanceWalletIcon />, hue: 'comptes' },
  [SETTINGS_LABEL]: { icon: <SettingsIcon /> },
}

const hueOf = (theme: Theme, hue?: ModuleName): string =>
  hue ? theme.palette.module[hue] : theme.palette.primary.main

/** The current entry wears its module's hue; radiant's accent pill is kept off every other one. */
const entryStyle = (theme: Theme, hue?: ModuleName) => ({
  '&.RaMenuItemLink-active:not(.Mui-selected)': {
    backgroundColor: 'transparent',
    color: 'inherit',
  },
  '&.Mui-selected, &.Mui-selected.RaMenuItemLink-active': {
    backgroundColor: alpha(hueOf(theme, hue), 0.16),
    color: hueOf(theme, hue),
    fontWeight: 600,
    '& .MuiListItemIcon-root > .MuiSvgIcon-root': { fill: hueOf(theme, hue) },
  },
})

const Header = () => {
  const agentName = useAgentName()

  return (
    <Box data-testid="menu-header" sx={{ display: 'flex', alignItems: 'center', gap: 1.5, px: 2, py: 1.5 }}>
      <MaggieAvatar size={32} />
      <Typography component="span" sx={{ fontWeight: 600, fontSize: 18 }}>
        {agentName}
      </Typography>
    </Box>
  )
}

interface ModuleEntryProps {
  module: SidebarModule
  open: boolean
  selected: boolean
  currentEntry?: string
}

const ModuleEntry = ({ module, open, selected, currentEntry }: ModuleEntryProps) => {
  const agentName = useAgentName()
  const look = MODULE_LOOKS[module.label]
  const icon = look.hue ? <ModuleIcon module={look.hue}>{look.icon}</ModuleIcon> : look.icon

  return (
    <>
      <MenuItemLink
        to={module.path}
        primaryText={module.label}
        leftIcon={icon}
        selected={selected}
        sx={(theme: Theme) => entryStyle(theme, look.hue)}
      />
      {open && selected
        ? module.entries.map((entry) => (
            <MenuItemLink
              key={entry.key}
              to={entry.path}
              primaryText={entry.path === AGENT_SETTINGS_PATH ? agentName : entry.label}
              selected={entry.path === currentEntry}
              sx={(theme: Theme) => ({ pl: 7, minHeight: 36, ...entryStyle(theme, look.hue) })}
            />
          ))
        : null}
    </>
  )
}

/**
 * The sidebar of the mockup (`charte/Admin-Cible`, MAG-620): the modules in one
 * flat column — Accueil, Agenda, Tâches, Courses, Cuisine, Finance — and
 * Réglages pinned at the bottom. Only the module the page belongs to opens its
 * entries, bare of icons, the current one in the module's hue.
 */
export const CustomMenu = () => {
  const [open] = useSidebarState()
  const { pathname } = useLocation()
  const currentModule = sidebarModuleOf(pathname)
  const currentEntry = sidebarEntryOf(pathname)
  const modules = SIDEBAR.filter((module) => module.label !== SETTINGS_LABEL)
  const settings = SIDEBAR.find((module) => module.label === SETTINGS_LABEL) as SidebarModule

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100%', mt: 1 }}>
      {open && <Header />}
      {modules.map((module) => (
        <ModuleEntry
          key={module.label}
          module={module}
          open={open}
          selected={currentModule === module.label}
          currentEntry={currentEntry}
        />
      ))}
      <Box sx={{ mt: 'auto', pb: 1 }}>
        <ModuleEntry
          module={settings}
          open={open}
          selected={currentModule === settings.label}
          currentEntry={currentEntry}
        />
      </Box>
    </Box>
  )
}
