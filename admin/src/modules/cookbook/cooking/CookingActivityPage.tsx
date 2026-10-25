import { useCallback, useMemo } from 'react'
import Typography from '@mui/material/Typography'
import { useSearchParams } from 'react-router-dom'
import { ActivityShell } from '../../../components/activity/ActivityShell'
import { useActivityOrigin } from '../../../components/activity/activityOrigin'
import {
  DoneScreen,
  EmptyMealScreen,
  ErrorScreen,
  LoadingScreen,
  PreparationScreen,
  ServingsScreen,
  StepScreen,
} from './CookingScreens'
import { orderedSteps } from '../recipeSheet'
import type { RecipeRecord, RecipeStep } from '../recipeSheet'
import { COOKING_COLOR, COOKING_FALLBACK_PATH, advance, back, confirmServings, markReady, skipRecipe, withGuests } from './cookingSession'
import type { CookingOrigin, CookingSession } from './cookingSession'
import { pendingWaits } from './cookingSteps'
import { useCookingActivity } from './useCookingActivity'
import { useStepKeys } from './useStepKeys'

const minutesOf = (session: CookingSession): number => Math.round((session.finishedAt - session.startedAt) / 60000)

const originOf = (params: URLSearchParams): CookingOrigin | null => {
  const meal = params.get('meal')
  if (meal) return { kind: 'meal', id: meal }
  const recipe = params.get('recipe')

  return recipe ? { kind: 'recipe', id: recipe } : null
}

const Cooking = ({ origin, goBack }: { origin: CookingOrigin; goBack: () => void }) => {
  const { load, session, recipes, refreshing, retry, update, end } = useCookingActivity(origin)

  const quit = useCallback(() => {
    end()
    goBack()
  }, [end, goBack])

  const names = useMemo(() => Object.fromEntries(Object.values(recipes).map((recipe) => [String(recipe.id), recipe.name])), [recipes])

  const recipe = session ? recipes[session.queue[session.recipeIndex].recipeId] : undefined
  const steps = useMemo(() => (recipe ? orderedSteps(recipe.steps) : []), [recipe])

  const progress =
    session && session.phase === 'cooking' ? (
      <Typography variant="body2" color="text.secondary" data-testid="cooking-progress" noWrap>
        {session.queue.length > 1 ? `Recette ${session.recipeIndex + 1} sur ${session.queue.length} · ` : ''}
        {session.step === 0 ? 'Préparation' : `Étape ${session.step} sur ${steps.length}`}
      </Typography>
    ) : null

  return (
    <ActivityShell color={COOKING_COLOR} name="Cuisine" onQuit={quit} progress={progress}>
      <Body
        load={load}
        session={session}
        recipe={recipe}
        names={names}
        steps={steps}
        refreshing={refreshing}
        retry={retry}
        update={update}
        quit={quit}
      />
    </ActivityShell>
  )
}

type BodyProps = {
  load: ReturnType<typeof useCookingActivity>['load']
  session: ReturnType<typeof useCookingActivity>['session']
  recipe: RecipeRecord | undefined
  names: Record<string, string>
  steps: RecipeStep[]
  refreshing: boolean
  retry: () => void
  update: ReturnType<typeof useCookingActivity>['update']
  quit: () => void
}

const Body = ({ load, session, recipe, names, steps, refreshing, retry, update, quit }: BodyProps) => {
  const cooking = session?.phase === 'cooking' && recipe !== undefined && steps.length > 0
  const position = session?.step ?? 0
  const pending = cooking && position > 0 ? pendingWaits(steps, position, session.ready) : []
  const inStep = cooking && position > 0 && position <= steps.length

  useStepKeys({
    onNext: cooking && pending.length === 0 ? () => update((current) => advance(current, steps.length)) : undefined,
    onPrevious: inStep ? () => update(back) : undefined,
  })

  if (load === 'error') return <ErrorScreen onRetry={retry} />
  if (load === 'empty') return <EmptyMealScreen onQuit={quit} />
  if (load === 'loading' || !session) return <LoadingScreen />
  if (session.phase === 'done') return <DoneScreen minutes={minutesOf(session)} guests={session.guests} onQuit={quit} />
  if (!recipe) return <LoadingScreen />

  if (session.phase === 'servings') {
    return (
      <ServingsScreen
        recipeName={recipe.name}
        guests={session.guests}
        defaultServings={recipe.servings ?? null}
        onChange={(guests) => update((current) => withGuests(current, guests))}
        onContinue={() => update(confirmServings)}
      />
    )
  }

  const isLastRecipe = session.recipeIndex === session.queue.length - 1
  const current = session.step > 0 ? steps[session.step - 1] : undefined

  if (session.step === 0 || current === undefined) {
    return (
      <PreparationScreen
        recipe={recipe}
        queue={session.queue}
        names={names}
        recipeIndex={session.recipeIndex}
        guests={session.guests}
        refreshing={refreshing}
        onGuests={(guests) => update((current) => withGuests(current, guests))}
        onStart={() => update((from) => advance(from, steps.length))}
        onSkip={() => update(skipRecipe)}
      />
    )
  }

  return (
    <StepScreen
      step={session.step}
      steps={steps}
      lines={recipe.ingredients ?? []}
      pending={pending}
      isLastOfAll={isLastRecipe && session.step === steps.length}
      onPrevious={() => update(back)}
      onNext={() => update((from) => advance(from, steps.length))}
      onReady={() => update((from) => markReady(from, pending))}
    />
  )
}

/** The Cuisine activity (MAG-580): `/activites/cuisine?meal=<id>` or `?recipe=<id>`. */
export const CookingActivityPage = () => {
  const [params] = useSearchParams()
  const goBack = useActivityOrigin(COOKING_FALLBACK_PATH)
  const origin = originOf(params)

  if (!origin) {
    return (
      <ActivityShell color={COOKING_COLOR} name="Cuisine" onQuit={goBack}>
        <EmptyMealScreen onQuit={goBack} />
      </ActivityShell>
    )
  }

  return <Cooking key={`${origin.kind}:${origin.id}`} origin={origin} goBack={goBack} />
}
