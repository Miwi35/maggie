import { useCallback, useMemo, useState } from 'react'
import Typography from '@mui/material/Typography'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { localToday } from '../../grocery/groceryListLive'
import { ActivityShell } from '../../../components/activity/ActivityShell'
import { useActivityOrigin } from '../../../components/activity/activityOrigin'
import {
  ConflictScreen,
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
import { COOKING_COLOR, COOKING_FALLBACK_PATH, advance, back, confirmServings, goToStep, isStepDone, launchPathOf, progressOf, skipRecipe, withGuests } from './cookingSession'
import type { CookingOrigin, CookingSession } from './cookingSession'
import { CookingGroceryButton, CookingGroceryPanel } from './CookingGroceryPanel'
import { CookingStepsColumn } from './CookingStepsColumn'
import type { ColumnRecipe } from './CookingStepsColumn'
import { MainTimer, TimerError } from './CookingTimers'
import { lineTimer, stepSeconds, stepTimer, timerLabel, timersOfRecipe } from './cookingTimers'
import type { TimerView } from './cookingTimers'
import { cookingStores, mealNeeds, openCount } from './cookingGrocery'
import { useCookingActivity } from './useCookingActivity'
import { useCookingGrocery } from './useCookingGrocery'
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
  const { load, session, conflict, recipes, refreshing, retry, update, leave, restart, replace } = useCookingActivity(origin)
  const timers = useCookingTimers()
  const { cancelAll } = timers
  const navigate = useNavigate()
  const location = useLocation()
  const grocery = useCookingGrocery()
  const [groceryOpen, setGroceryOpen] = useState(false)

  // Leaving asks nothing: the status is already on the API and the timers keep running.
  const quit = useCallback(() => {
    leave()
    goBack()
  }, [leave, goBack])

  // The timers of the activity stop when it starts again, never when it is only left.
  const startOver = useCallback(() => {
    void cancelAll()
    restart()
  }, [cancelAll, restart])

  const replaceInProgress = useCallback(() => {
    void cancelAll()
    replace()
  }, [cancelAll, replace])

  const resumeInProgress = useCallback(() => {
    if (conflict) navigate(launchPathOf(conflict.origin), { replace: true, state: location.state })
  }, [conflict, navigate, location.state])

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

  const queue = session?.queue
  const needs = useMemo(() => mealNeeds(queue ?? [], recipes), [queue, recipes])
  const today = localToday()
  const stores = useMemo(() => cookingStores(grocery.items, needs, today), [grocery.items, needs, today])
  const count = useMemo(() => openCount(grocery.items, today), [grocery.items, today])
  const groceryShown = load === 'ready' && session !== null && session.phase !== 'done'

  return (
    <ActivityShell
      color={COOKING_COLOR}
      name="Cuisine"
      onQuit={quit}
      progress={progress}
      actions={groceryShown && <CookingGroceryButton count={grocery.load === 'ready' ? count : null} open={groceryOpen} onClick={() => setGroceryOpen((open) => !open)} />}
    >
      {timers.error && <TimerError message={timers.error} onClose={timers.clearError} />}
      <Body
        timers={timers}
        load={load}
        session={session}
        conflict={conflict}
        origin={origin}
        recipe={recipe}
        recipes={recipes}
        names={names}
        steps={steps}
        refreshing={refreshing}
        retry={retry}
        update={update}
        quit={quit}
        onRestart={startOver}
        onResume={resumeInProgress}
        onReplace={replaceInProgress}
      />
      {groceryShown && <CookingGroceryPanel open={groceryOpen} onClose={() => setGroceryOpen(false)} grocery={grocery} stores={stores} needs={needs} count={count} />}
    </ActivityShell>
  )
}

type BodyProps = {
  timers: CookingTimers
  load: ReturnType<typeof useCookingActivity>['load']
  session: ReturnType<typeof useCookingActivity>['session']
  conflict: ReturnType<typeof useCookingActivity>['conflict']
  origin: CookingOrigin
  recipe: RecipeRecord | undefined
  recipes: Record<string, RecipeRecord>
  names: Record<string, string>
  steps: RecipeStep[]
  refreshing: boolean
  retry: () => void
  update: ReturnType<typeof useCookingActivity>['update']
  quit: () => void
  onRestart: () => void
  onResume: () => void
  onReplace: () => void
}

const Body = ({ timers, load, session, conflict, origin, recipe, recipes, names, steps, refreshing, retry, update, quit, onRestart, onResume, onReplace }: BodyProps) => {
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
  if (load === 'conflict' && conflict) return <ConflictScreen status={conflict} launched={origin} onResume={onResume} onReplace={onReplace} />
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
          onRestart={onRestart}
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
