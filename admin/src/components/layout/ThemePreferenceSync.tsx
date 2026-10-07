import { useEffect } from 'react'
import { useStore, useTheme } from 'react-admin'
import { useUserPreferences } from '../../hooks/useUserPreferences'

export type ThemeChoice = 'system' | 'light' | 'dark'

// Not `theme`: that key belongs to react-admin and holds the mode it paints with.
export const THEME_CHOICE_KEY = 'maggie.themeChoice'

const isThemeChoice = (value: unknown): value is ThemeChoice =>
  value === 'system' || value === 'light' || value === 'dark'

/**
 * Paints the interface with the theme the user picked in « Apparence »: the
 * choice is kept in the store (instant, survives a reload), restored from the
 * server preference on arrival, and « Système » follows the OS as it changes.
 */
export const ThemePreferenceSync = () => {
  const { preferences } = useUserPreferences()
  const [choice, setChoice] = useStore<ThemeChoice>(THEME_CHOICE_KEY, 'system')
  const [, setMode] = useTheme()

  const saved = preferences?.theme
  useEffect(() => {
    if (isThemeChoice(saved)) setChoice(saved)
  }, [saved, setChoice])

  useEffect(() => {
    if (choice !== 'system') {
      setMode(choice)
      return
    }
    const os = window.matchMedia('(prefers-color-scheme: dark)')
    const follow = () => setMode(os.matches ? 'dark' : 'light')
    follow()
    os.addEventListener('change', follow)
    return () => os.removeEventListener('change', follow)
  }, [choice, setMode])

  return null
}
