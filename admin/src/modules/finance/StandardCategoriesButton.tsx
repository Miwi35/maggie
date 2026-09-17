import { useState } from 'react'
import Button from '@mui/material/Button'
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome'
import { useNotify, useRefresh } from 'react-admin'

/**
 * Lays down a starting set of categories.
 *
 * A budget cannot classify anything before categories exist, and inventing a
 * taxonomy from nothing is where people give up. Running it twice is harmless:
 * a heading already there is left as the user shaped it.
 */
export const StandardCategoriesButton = ({
  variant = 'outlined',
}: {
  variant?: 'outlined' | 'text'
}) => {
  const [installing, setInstalling] = useState(false)
  const notify = useNotify()
  const refresh = useRefresh()

  const onClick = async () => {
    setInstalling(true)
    try {
      const token = localStorage.getItem('token')
      const res = await fetch('/api/finance/categories/standard', {
        method: 'POST',
        headers: {
          Accept: 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
      })

      if (!res.ok) {
        notify("Les catégories de départ n'ont pas pu être créées", {
          type: 'error',
        })

        return
      }

      const result = (await res.json()) as { created: number; kept: number }
      notify(
        result.created > 0
          ? `${result.created} catégorie(s) créée(s).`
          : 'Toutes les catégories de départ étaient déjà là.',
        { type: 'info' },
      )
      refresh()
    } catch {
      notify("Les catégories de départ n'ont pas pu être créées", {
        type: 'error',
      })
    } finally {
      setInstalling(false)
    }
  }

  return (
    <Button
      variant={variant}
      onClick={onClick}
      disabled={installing}
      startIcon={<AutoAwesomeIcon />}
    >
      {installing ? 'Création…' : 'Catégories de départ'}
    </Button>
  )
}
