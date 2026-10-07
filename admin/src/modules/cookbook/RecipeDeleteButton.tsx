import { useState, type MouseEvent } from 'react'
import Button from '@mui/material/Button'
import DeleteIcon from '@mui/icons-material/Delete'
import { Confirm, useDelete, useNotify, useRecordContext, useRedirect, useRefresh } from 'react-admin'

type PlannedMeal = { date: string; slot: 'lunch' | 'dinner' }
type Impact = { mealCount: number; meals: PlannedMeal[] }

/** `undefined` while the impact is being fetched, `null` when it could not be. */
type ImpactState = Impact | null | undefined

const LISTED_MEALS = 5

const mealLabel = ({ date, slot }: PlannedMeal) =>
  `${new Date(`${date}T12:00:00`).toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' })}, ${slot === 'lunch' ? 'midi' : 'soir'}`

const recipeIri = (id: string) => (id.startsWith('/') ? id : `/api/recipes/${id}`)

const confirmTitle = (name: string, mealCount: number | null | undefined) => {
  if (!mealCount) return `Supprimer « ${name} » ?`

  return mealCount === 1
    ? `Supprimer « ${name} » et son repas planifié ?`
    : `Supprimer « ${name} » et ses ${mealCount} repas planifiés ?`
}

const ConfirmContent = ({ impact }: { impact: ImpactState }) => {
  if (impact === null) return <>Les repas planifiés qui n’ont que cette recette seront supprimés avec elle.</>
  if (!impact || impact.mealCount === 0) {
    return <>Les repas qui n’ont que cette recette disparaissent de l’agenda et de la liste de courses. Cette action est définitive.</>
  }

  const listed = impact.meals.slice(0, LISTED_MEALS)
  const rest = impact.mealCount - listed.length

  return (
    <>
      <p>Vous aviez prévu de cuisiner cette recette :</p>
      <ul>
        {listed.map((meal) => (
          <li key={`${meal.date}-${meal.slot}`}>{mealLabel(meal)}</li>
        ))}
        {rest > 0 && <li>{rest === 1 ? 'et 1 autre' : `et ${rest} autres`}</li>}
      </ul>
      <p>
        Ces repas seront supprimés avec elle, de l’agenda comme de la liste de courses. Cette action est définitive.
      </p>
    </>
  )
}

/**
 * Deleting a recipe also deletes the meals it was the only recipe of (MAG-289),
 * so the confirmation says how many, and on which days, before the owner agrees.
 */
export const RecipeDeleteButton = () => {
  const record = useRecordContext<{ id: string; name: string }>()
  const notify = useNotify()
  const refresh = useRefresh()
  const redirect = useRedirect()
  const [deleteOne, { isPending }] = useDelete()
  const [open, setOpen] = useState(false)
  const [impact, setImpact] = useState<ImpactState>(undefined)

  if (!record) return null

  const onOpen = (event: MouseEvent) => {
    event.stopPropagation()
    setImpact(undefined)
    setOpen(true)

    const token = localStorage.getItem('token')
    fetch(`${recipeIri(String(record.id))}/deletion-impact`, {
      headers: {
        Accept: 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
    })
      .then((res) => (res.ok ? (res.json() as Promise<Impact>) : Promise.reject(new Error(res.statusText))))
      .then((data) => setImpact({ mealCount: data.mealCount, meals: data.meals ?? [] }))
      .catch(() => setImpact(null))
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
        loading={isPending || impact === undefined}
        title={confirmTitle(record.name, impact?.mealCount)}
        content={<ConfirmContent impact={impact} />}
        onConfirm={onConfirm}
        onClose={() => setOpen(false)}
      />
    </span>
  )
}
