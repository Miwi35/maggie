import { useCallback, useMemo, useState } from 'react'
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
import { COOKING_COLOR, COOKING_FALLBACK_PATH, advance, back, confirmServings, goToStep, isStepDone, progressOf, skipRecipe, withGuests } from './cookingSession'
import type { CookingOrigin, CookingSession } from './cookingSession'
import { CookingStepsColumn } from './CookingStepsColumn'
import type { ColumnRecipe } from './CookingStepsColumn'
import { MainTimer, TimerError } from './CookingTimers'
import { lineTimer, quitDetail, stepSeconds, stepTimer, timerLabel, timersOfRecipe } from './cookingTimers'
import type { TimerView } from './cookingTimers'
import { useCookingActivity } from './useCookingActivity'
import { useCookingTimers } from './useCookingTimers'
import type { CookingTimers } from './useCookingTimers'
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
  const timers = useCookingTimers()
  const { cancelAll } = timers

  // Leaving stops every timer of the activity: they are sent, the activity does not wait for the answers.
  const quit = useCallback(() => {
    void cancelAll()
    end()
    goBack()
  }, [cancelAll, end, goBack])

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

  const detail = quitDetail(timers.timers)

  return (
    <ActivityShell color={COOKING_COLOR} name="Cuisine" confirmQuit={detail !== undefined} confirmDetail={detail} onQuit={quit} progress={progress}>
      {timers.error && <TimerError message={timers.error} onClose={timers.clearError} />}
      <Body
        timers={timers}
        load={load}
        session={session}
        recipe={recipe}
        recipes={recipes}
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
  timers: CookingTimers
  load: ReturnType<typeof useCookingActivity>['load']
  session: ReturnType<typeof useCookingActivity>['session']
  recipe: RecipeRecord | undefined
  recipes: Record<string, RecipeRecord>
  names: Record<string, string>
  steps: RecipeStep[]
  refreshing: boolean
  retry: () => void
  update: ReturnType<typeof useCookingActivity>['update']
  quit: () => void
}

const Body = ({ timers, load, session, recipe, recipes, names, steps, refreshing, retry, update, quit }: BodyProps) => {
  const [starting, setStarting] = useState(false)
  const cooking = load === 'ready' && session?.phase === 'cooking' && recipe !== undefined && steps.length > 0
  const position = session?.step ?? 0
  const recipeId = session ? session.queue[session.recipeIndex].recipeId : ''
  const mine = useMemo(() => timersOfRecipe(timers.timers, recipeId), [timers.timers, recipeId])
  const inStep = cooking && position > 0 && position <= steps.length
  const current = inStep ? steps[position - 1] : undefined
  const seconds = stepSeconds(current)
  const timer = seconds > 0 ? stepTimer(mine, position) : undefined
  const launches = seconds > 0 && current?.background === true && timer === undefined

  const { start } = timers
  const startStep = useCallback(async (): Promise<boolean> => {
    if (!current || seconds === 0) return false
    setStarting(true)
    const started = await start({ label: timerLabel(current.text, position), recipe: `/api/recipes/${recipeId}`, stepPosition: position, durationSeconds: seconds })
    setStarting(false)

    return started
  }, [current, position, recipeId, seconds, start])

  // « Suivant » ticks the step; « Lancer et continuer » starts the timer first (refused, the step stays and says why) and ticks nothing.
  const next = useCallback(async () => {
    if (starting) return
    if (launches && !(await startStep())) return
    update((from) => advance(from, steps.length, Date.now(), !launches))
  }, [launches, startStep, starting, steps.length, update])

  const { change } = timers
  const select = useCallback(
    (recipeIndex: number, step: number, timer: TimerView | undefined) => {
      update((from) => goToStep(from, recipeIndex, step))
      if (timer?.status === 'done') void change(timer.id, 'dismiss')
    },
    [change, update],
  )

  const columnRecipes = useMemo<ColumnRecipe[]>(
    () =>
      (session?.queue ?? []).map((entry) => {
        const queued = recipes[entry.recipeId]

        return {
          recipeId: entry.recipeId,
          name: queued?.name ?? '…',
          steps: orderedSteps(queued?.steps).map((step, index) => ({ position: index + 1, text: step.text })),
        }
      }),
    [session?.queue, recipes],
  )
  const stepCounts = useMemo(() => Object.fromEntries(columnRecipes.map((entry) => [entry.recipeId, entry.steps.length])), [columnRecipes])

  useStepKeys({
    onNext: cooking && !starting ? () => void next() : undefined,
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
      isLastOfAll={isLastRecipe && session.step === steps.length}
      launches={launches}
      busy={starting}
      column={
        <CookingStepsColumn
          recipes={columnRecipes}
          current={{ recipeIndex: session.recipeIndex, step: session.step }}
          isDone={(id, position) => isStepDone(session, id, position)}
          timerOf={(id, position) => lineTimer(timersOfRecipe(timers.timers, id), position)}
          {...progressOf(session, stepCounts)}
          onSelect={select}
        />
      }
      timers={
        seconds > 0 && (
          <MainTimer
            label={timer?.label ?? timerLabel(current.text, position)}
            seconds={seconds}
            timer={timer}
            busy={starting}
            onStart={() => void startStep()}
            onToggle={() => timer && void timers.change(timer.id, timer.status === 'running' ? 'pause' : 'resume')}
            onAdd={(delta) => timer && void timers.change(timer.id, 'add', delta)}
          />
        )
      }
      onPrevious={() => update(back)}
      onNext={() => void next()}
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
