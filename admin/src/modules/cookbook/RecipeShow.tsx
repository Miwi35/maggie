import { useCallback, useMemo, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Typography from '@mui/material/Typography'
import CheckIcon from '@mui/icons-material/Check'
import EditOutlinedIcon from '@mui/icons-material/EditOutlined'
import RestaurantIcon from '@mui/icons-material/Restaurant'
import { Confirm, Show, useCreate, useCreatePath, useGetMany, useNotify, useRecordContext, useRedirect, useUpdate } from 'react-admin'
import { Link as RouterLink, Navigate, useLocation, useParams } from 'react-router-dom'
import { ReadingPage } from '../../components/layout/ReadingPage'
import { useMercure } from '../../hooks/useMercure'
import { recipeLaunchPath } from './cooking/cookingSession'
import { GuestsStepper } from './GuestsStepper'
import { ActionsMenu, EditButton, IngredientsBlock, NotesBlock, NutritionBlock, StepsBlock, UtensilsBlock } from './RecipeBlocks'
import { RecipeDeleteConfirm } from './RecipeDeleteButton'
import { HeaderDialog, NotesDialog, PlanMealDialog, UtensilsDialog } from './RecipeDialogs'
import { RecipeIngredientDialog } from './RecipeIngredientDialog'
import type { ReplaceBody, ReplaceOutcome } from './RecipeIngredientDialog'
import { RecipeStepDialog } from './RecipeStepDialog'
import { canonicalRecordId, idOf, linesPayload, nutritionPerServing, orderedSteps, productIdOf, stepLabel, stepsUsing, utensilNames, withoutProduct } from './recipeSheet'
import type { ProductInfo, RecipeLine, RecipeRecord } from './recipeSheet'
import { moveStep, refusalOf, removeStep, stepsPayload } from './recipeSteps'
import { useScaledQuantities } from './useScaledQuantities'

const RECIPE_TOPICS = ['/api/recipes/{id}']

// The parts of the sheet the API publishes when they change.
const LIVE_FIELDS = ['name', 'servings', 'tags', 'notes', 'ingredients', 'utensils', 'steps'] as const

const liveOf = (source: Record<string, unknown> | undefined): Partial<RecipeRecord> => {
  const live: Record<string, unknown> = {}
  for (const field of LIVE_FIELDS) if (source && field in source) live[field] = source[field]

  return live as Partial<RecipeRecord>
}

type Dialog =
  | { kind: 'header' }
  | { kind: 'notes' }
  | { kind: 'utensils' }
  | { kind: 'plan' }
  | { kind: 'delete' }
  | { kind: 'ingredient'; line: RecipeLine | null }
  | { kind: 'step'; index: number }

const errorMessage = (failure: unknown) => refusalOf(failure) ?? (failure as Error | undefined)?.message ?? 'Enregistrement impossible'

/**
 * The recipe, read in two columns (MAG-618): ingredients, utensils and notes on the left, the steps
 * on the right, what one serving brings at the end. It opens to read; « Modifier » turns on the
 * edit tools (MAG-601): each block edits in a popin, so nothing is a form to fill whole. Every
 * change goes to the API as it is made; the sheet shows what the API kept, and what other clients or
 * Maggie change reaches it live. The mode is not remembered: the next sheet opens to read.
 */
const Sheet = ({ record, creating = false }: { record: RecipeRecord; creating?: boolean }) => {
  const notify = useNotify()
  const redirect = useRedirect()
  const location = useLocation()
  const [update] = useUpdate()
  const [create] = useCreate()
  const [dialog, setDialog] = useState<Dialog | null>(creating ? { kind: 'header' } : null)
  const [removing, setRemoving] = useState<RecipeLine | null>(null)
  const [guests, setGuests] = useState<number | null>(null)
  const [editing, setEditing] = useState(creating)
  // What the API answered to our own changes and what the hub published since the record was read:
  // newer than the read, which an index not yet refreshed could still serve.
  const [live, setLive] = useState<Partial<RecipeRecord>>({})
  const recipe = useMemo<RecipeRecord>(() => ({ ...record, ...live }), [record, live])

  const lines = useMemo(() => recipe.ingredients ?? [], [recipe.ingredients])
  const steps = useMemo(() => orderedSteps(recipe.steps), [recipe.steps])
  const servings = recipe.servings ?? 1
  const shownGuests = guests ?? servings
  const recipeId = creating ? null : idOf(record.id)
  const scaled = useScaledQuantities(recipeId, servings, shownGuests)
  const simulating = shownGuests !== servings
  const scaledLines = useMemo(
    () => new Map(scaled.state === 'ready' ? scaled.lines.flatMap((line) => (line.id ? [[line.id, line] as const] : [])) : []),
    [scaled],
  )

  const productRefs = [...new Set(lines.map((line) => line.ingredient?.['@id'] ?? line.ingredient?.id).filter((ref): ref is string => Boolean(ref)))]
  const { data: productData } = useGetMany<ProductInfo>('ingredients', { ids: productRefs }, { enabled: productRefs.length > 0 })
  const products = useMemo(() => new Map((productData ?? []).map((product) => [idOf(product.id), product])), [productData])
  const nutrition = useMemo(() => nutritionPerServing(lines, servings, products), [lines, servings, products])

  const onMercure = useCallback(
    (data?: string) => {
      if (!data || creating) return
      try {
        const payload = JSON.parse(data) as Record<string, unknown>
        if (idOf(payload['@id']) !== idOf(record.id) || payload.deleted) return
        setLive((previous) => ({ ...previous, ...liveOf(payload) }))
      } catch {
        // Not a recipe update.
      }
    },
    [creating, record.id],
  )
  useMercure(RECIPE_TOPICS, onMercure)

  /** Sends part of the recipe; null when the API kept it, its refusal otherwise. */
  const save = useCallback(
    (data: Record<string, unknown>) =>
      new Promise<string | null>((resolve) => {
        update(
          'recipes',
          { id: record.id, data, previousData: record },
          {
            mutationMode: 'pessimistic',
            onSuccess: (response) => {
              const kept = liveOf(response as Record<string, unknown>)
              // The API leaves a null field out of its answer: a sent field it does not return was cleared.
              const cleared = Object.fromEntries(Object.keys(liveOf(data)).filter((field) => !(field in kept)).map((field) => [field, null]))
              setLive((previous) => ({ ...previous, ...cleared, ...kept }))
              resolve(null)
            },
            onError: (failure) => resolve(errorMessage(failure)),
          },
        )
      }),
    [update, record],
  )

  const saveOrNotify = async (data: Record<string, unknown>) => {
    const refused = await save(data)
    if (refused) notify(refused, { type: 'error' })
  }

  const createRecipe = (data: Record<string, unknown>) =>
    new Promise<string | false>((resolve) => {
      create(
        'recipes',
        { data: { ingredients: [], utensils: [], steps: [], ...data } },
        {
          onSuccess: (created) => {
            notify('Recette créée', { type: 'success' })
            redirect('show', 'recipes', (created as { id: string }).id)
            // The popin goes with the page: closing it would send the owner back to the list.
            resolve(false)
          },
          onError: (failure) => resolve(errorMessage(failure)),
        },
      )
    })

  const replaceIngredient = async (body: ReplaceBody): Promise<ReplaceOutcome> => {
    const token = localStorage.getItem('token')
    try {
      const response = await fetch(`/api/recipes/${recipeId}/replace-ingredient`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
        body: JSON.stringify(body),
      })
      const payload = (await response.json().catch(() => ({}))) as { error?: string; recipe?: Record<string, unknown>; textSuggestions?: [] }
      if (!response.ok || !payload.recipe) return { error: payload.error ?? 'Le remplacement a échoué.' }
      setLive((previous) => ({ ...previous, ...liveOf(payload.recipe) }))

      return { result: { recipe: payload.recipe as unknown as RecipeRecord, textSuggestions: payload.textSuggestions ?? [] } }
    } catch {
      return { error: 'Le remplacement a échoué : le serveur ne répond pas.' }
    }
  }

  const duplicate = () =>
    create(
      'recipes',
      {
        data: {
          name: `${recipe.name} (copie)`,
          servings: recipe.servings,
          tags: recipe.tags ?? [],
          notes: recipe.notes ?? undefined,
          ingredients: linesPayload(lines),
          utensils: utensilNames(recipe.utensils),
          steps: stepsPayload(steps),
        },
      },
      {
        onSuccess: (created) => {
          notify('Recette dupliquée', { type: 'success' })
          redirect('show', 'recipes', (created as { id: string }).id)
        },
        onError: (failure) => notify(errorMessage(failure), { type: 'error' }),
      },
    )

  const removeLine = async (line: RecipeLine) => {
    const product = productIdOf(line)
    const rest = lines.filter((other) => other !== line)
    const linked = product !== null && stepsUsing(steps, product).length > 0
    const refused = await save({
      ingredients: linesPayload(rest),
      ...(linked ? { steps: stepsPayload(withoutProduct(steps, product)) } : {}),
    })
    setRemoving(null)
    if (refused) notify(refused, { type: 'error' })
  }

  const askRemoveLine = (line: RecipeLine) => {
    const product = productIdOf(line)
    if (product && stepsUsing(steps, product).length > 0) setRemoving(line)
    else removeLine(line)
  }

  const close = () => setDialog(null)
  const closeHeader = () => (creating ? redirect('list', 'recipes') : close())
  const removingSteps = removing ? stepsUsing(steps, productIdOf(removing) ?? '') : []

  return (
    <ReadingPage>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
        <Typography variant="h5" component="h1" sx={{ flexGrow: 1 }}>
          {creating ? 'Nouvelle recette' : recipe.name}
        </Typography>
        {!creating && (
          <>
            <Button
              component={RouterLink}
              to={recipeLaunchPath(idOf(recipe.id))}
              state={{ from: `${location.pathname}${location.search}` }}
              variant="contained"
              startIcon={<RestaurantIcon />}
              data-testid="recipe-cook"
              sx={{ minHeight: 48 }}
            >
              Cuisiner
            </Button>
            <Button
              variant="outlined"
              startIcon={editing ? <CheckIcon /> : <EditOutlinedIcon />}
              onClick={() => setEditing((current) => !current)}
              data-testid="recipe-edit-toggle"
              sx={{ minHeight: 48 }}
            >
              {editing ? 'Terminé' : 'Modifier'}
            </Button>
            {editing && <EditButton label="Modifier le nom, les portions et les tags" onClick={() => setDialog({ kind: 'header' })} />}
            <ActionsMenu
              label="Actions de la recette"
              actions={[
                { label: 'Dupliquer', onClick: duplicate },
                { label: 'Planifier un repas', onClick: () => setDialog({ kind: 'plan' }) },
                { label: 'Supprimer', onClick: () => setDialog({ kind: 'delete' }), danger: true },
              ]}
            />
          </>
        )}
      </Box>
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, alignItems: 'center', mb: 2 }}>
        {!creating && <GuestsStepper label="Portions" value={shownGuests} onChange={setGuests} />}
        {(recipe.tags ?? []).map((tag) => (
          <Chip key={tag} label={tag} size="small" />
        ))}
      </Box>
      {simulating && (
        <Alert
          severity={scaled.state === 'failed' ? 'error' : 'info'}
          sx={{ mb: 2 }}
          action={
            <Button color="inherit" size="small" onClick={() => setGuests(null)}>
              Revenir à {servings}
            </Button>
          }
        >
          {scaled.state === 'failed'
            ? `Impossible de calculer les quantités pour ${shownGuests} personnes : celles de la recette restent valables.`
            : `Quantités pour ${shownGuests} personne${shownGuests > 1 ? 's' : ''} : une simulation, rien n’est enregistré.`}
        </Alert>
      )}

      <Box sx={{ display: 'grid', gridTemplateColumns: { xs: 'minmax(0, 1fr)', lg: '520px minmax(0, 1fr)' }, columnGap: 4, alignItems: 'start' }}>
        <Box>
          <IngredientsBlock
            recipe={recipe}
            scaled={scaledLines}
            products={products}
            simulating={simulating}
            creating={creating}
            editing={editing}
            servings={shownGuests}
            onAdd={() => setDialog({ kind: 'ingredient', line: null })}
            onEdit={(line) => setDialog({ kind: 'ingredient', line })}
            onRemove={askRemoveLine}
            onQuantity={(line, quantity) => save({ ingredients: linesPayload(lines.map((other) => (other === line ? { ...other, quantity } : other))) })}
          />
          <UtensilsBlock recipe={recipe} creating={creating} editing={editing} onEdit={() => setDialog({ kind: 'utensils' })} />
          <NotesBlock recipe={recipe} creating={creating} editing={editing} onEdit={() => setDialog({ kind: 'notes' })} />
        </Box>
        <StepsBlock
          steps={steps}
          creating={creating}
          editing={editing}
          onAdd={() => setDialog({ kind: 'step', index: steps.length })}
          onEdit={(index) => setDialog({ kind: 'step', index })}
          onMove={(from, to) => saveOrNotify({ steps: stepsPayload(moveStep(steps, from, to)) })}
          onRemove={(index) => saveOrNotify({ steps: stepsPayload(removeStep(steps, index)) })}
        />
      </Box>
      <NutritionBlock nutrition={nutrition} />

      {dialog?.kind === 'header' && (
        <HeaderDialog recipe={recipe} creating={creating} onSave={creating ? createRecipe : save} onClose={closeHeader} />
      )}
      {dialog?.kind === 'notes' && <NotesDialog notes={recipe.notes} onSave={save} onClose={close} />}
      {dialog?.kind === 'utensils' && <UtensilsDialog utensils={recipe.utensils} onSave={save} onClose={close} />}
      {dialog?.kind === 'ingredient' && (
        <RecipeIngredientDialog recipe={recipe} line={dialog.line} onSave={save} onReplace={replaceIngredient} onClose={close} />
      )}
      {dialog?.kind === 'step' && <RecipeStepDialog recipe={recipe} steps={steps} index={dialog.index} onSave={save} onClose={close} />}
      {dialog?.kind === 'plan' && <PlanMealDialog recipe={recipe} onClose={close} />}
      {dialog?.kind === 'delete' && <RecipeDeleteConfirm open onClose={close} />}
      <Confirm
        isOpen={removing !== null}
        title={`Retirer « ${removing?.ingredientName ?? ''} » ?`}
        content={`${removingSteps.length === 1 ? "L'" : 'Les '}${stepLabel(removingSteps)} ${removingSteps.length === 1 ? 'utilise' : 'utilisent'} cet ingrédient : le lien sera retiré de ${removingSteps.length === 1 ? 'cette étape' : 'ces étapes'}, leur texte ne change pas.`}
        confirm="Retirer"
        onConfirm={() => removing && removeLine(removing)}
        onClose={() => setRemoving(null)}
      />
    </ReadingPage>
  )
}

const RecipeSheet = () => {
  const record = useRecordContext<RecipeRecord>()
  if (!record) return null

  return <Sheet key={String(record.id)} record={record} />
}

export const RecipeShow = () => {
  const { id } = useParams()
  const createPath = useCreatePath()
  const canonicalId = id === undefined ? undefined : canonicalRecordId(id)

  // An address that spells the ULID another way (UUID notation, lower case) opens the same
  // recipe on its canonical address; left as it is, the sheet would throw on the id the API answers with.
  if (id !== undefined && canonicalId !== id) {
    return <Navigate to={createPath({ resource: 'recipes', type: 'show', id: canonicalId })} replace />
  }

  return (
    <Show title="Recette" actions={false}>
      <RecipeSheet />
    </Show>
  )
}

const EMPTY: RecipeRecord = { id: '', name: '', servings: 4, tags: [], notes: null, ingredients: [], utensils: [], steps: [] }

/** The same page, empty: the first popin names the recipe, the blocks are filled from the sheet it then opens. */
export const NewRecipeSheet = () => <Sheet record={EMPTY} creating />
