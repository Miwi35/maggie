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
import { COOKING_COLOR, COOKING_FALLBACK_PATH, advance, back, confirmServings, skipRecipe, stepTexts, withGuests } from './cookingSession'
import type { CookingOrigin } from './cookingSession'
import { useCookingActivity } from './useCookingActivity'

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
  const texts = recipe ? stepTexts(recipe) : []

  const progress =
    session && session.phase === 'cooking' ? (
      <Typography variant="body2" color="text.secondary" data-testid="cooking-progress" noWrap>
        {session.queue.length > 1 ? `Recette ${session.recipeIndex + 1} sur ${session.queue.length} · ` : ''}
        {session.step === 0 ? 'Préparation' : `Étape ${session.step} sur ${texts.length}`}
      </Typography>
    ) : null

  return (
    <ActivityShell color={COOKING_COLOR} name="Cuisine" onQuit={quit} progress={progress}>
      <Body
        load={load}
        session={session}
        recipe={recipe}
        names={names}
        texts={texts}
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
  recipe: ReturnType<typeof useCookingActivity>['recipes'][string] | undefined
  names: Record<string, string>
  texts: string[]
  refreshing: boolean
  retry: () => void
  update: ReturnType<typeof useCookingActivity>['update']
  quit: () => void
}

const Body = ({ load, session, recipe, names, texts, refreshing, retry, update, quit }: BodyProps) => {
  if (load === 'error') return <ErrorScreen onRetry={retry} />
  if (load === 'empty') return <EmptyMealScreen onQuit={quit} />
  if (load === 'loading' || !session) return <LoadingScreen />
  if (session.phase === 'done') return <DoneScreen onQuit={quit} />
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
  const text = session.step > 0 ? texts[session.step - 1] : undefined

  if (session.step === 0 || text === undefined) {
    return (
      <PreparationScreen
        recipe={recipe}
        queue={session.queue}
        names={names}
        recipeIndex={session.recipeIndex}
        guests={session.guests}
        refreshing={refreshing}
        onGuests={(guests) => update((current) => withGuests(current, guests))}
        onStart={() => update((current) => advance(current, texts.length))}
        onSkip={() => update(skipRecipe)}
      />
    )
  }

  return (
    <StepScreen
      step={session.step}
      stepCount={texts.length}
      text={text}
      isLastOfAll={isLastRecipe && session.step === texts.length}
      onPrevious={() => update(back)}
      onNext={() => update((current) => advance(current, texts.length))}
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
