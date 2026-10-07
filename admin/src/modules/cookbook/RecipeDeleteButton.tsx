import { useState, type MouseEvent } from 'react'
import Button from '@mui/material/Button'
import DeleteIcon from '@mui/icons-material/Delete'
import { Confirm, useDelete, useNotify, useRecordContext, useRedirect, useRefresh } from 'react-admin'

/** `undefined` while the count is being fetched, `null` when it could not be. */
type MealCount = number | null | undefined

const recipeIri = (id: string) => (id.startsWith('/') ? id : `/api/recipes/${id}`)

const confirmTitle = (name: string, mealCount: MealCount) => {
  if (!mealCount) return `Supprimer « ${name} » ?`

  return mealCount === 1
    ? `Supprimer « ${name} » et son repas planifié ?`
    : `Supprimer « ${name} » et ses ${mealCount} repas planifiés ?`
}

/**
 * Deleting a recipe also deletes the meals it was the only recipe of (MAG-289),
 * so the confirmation says how many before the owner agrees.
 */
export const RecipeDeleteButton = () => {
  const record = useRecordContext<{ id: string; name: string }>()
  const notify = useNotify()
  const refresh = useRefresh()
  const redirect = useRedirect()
  const [deleteOne, { isPending }] = useDelete()
  const [open, setOpen] = useState(false)
  const [mealCount, setMealCount] = useState<MealCount>(undefined)

  if (!record) return null

  const onOpen = (event: MouseEvent) => {
    event.stopPropagation()
    setMealCount(undefined)
    setOpen(true)

    const token = localStorage.getItem('token')
    fetch(`${recipeIri(String(record.id))}/deletion-impact`, {
      headers: {
        Accept: 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    })
      .then((res) => (res.ok ? (res.json() as Promise<{ mealCount: number }>) : Promise.reject(new Error(res.statusText))))
      .then((data) => setMealCount(data.mealCount))
      .catch(() => setMealCount(null))
  }

  const onConfirm = () => {
    deleteOne(
      'recipes',
      { id: record.id, previousData: record },
      {
        mutationMode: 'pessimistic',
        onSuccess: () => {
          setOpen(false)
          notify('Recette supprimée', { type: 'success' })
          redirect('list', 'recipes')
          refresh()
        },
        onError: (error) => {
          setOpen(false)
          notify(`Erreur : ${(error as Error).message}`, { type: 'error' })
        },
      },
    )
  }

  return (
    // The dialog is a portal, but its clicks still bubble to the datagrid row.
    <span onClick={(event) => event.stopPropagation()}>
      <Button color="error" size="small" startIcon={<DeleteIcon />} onClick={onOpen}>
        Supprimer
      </Button>
      <Confirm
        isOpen={open}
        loading={isPending || mealCount === undefined}
        title={confirmTitle(record.name, mealCount)}
        content={
          mealCount === null
            ? 'Les repas planifiés qui n’ont que cette recette seront supprimés avec elle.'
            : 'Les repas qui n’ont que cette recette disparaissent de l’agenda et de la liste de courses. Cette action est définitive.'
        }
        onConfirm={onConfirm}
        onClose={() => setOpen(false)}
      />
    </span>
  )
}
