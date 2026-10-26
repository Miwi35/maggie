import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import Typography from '@mui/material/Typography'
import { keyframes } from '@mui/material/styles'
import AddIcon from '@mui/icons-material/Add'
import RemoveIcon from '@mui/icons-material/Remove'
import { Link as RouterLink } from 'react-router-dom'
import { quantityLabel } from '../../grocery/packaging'
import { durationLabel, idOf } from '../recipeSheet'
import type { RecipeLine, RecipeRecord, RecipeStep } from '../recipeSheet'
import { nameOf } from '../recipeSteps'
import { fetchRecipe } from './cookingApi'
import { MAX_GUESTS, MIN_GUESTS, sessionFromState } from './cookingSession'
import type { CookingOrigin, QueuedRecipe } from './cookingSession'
import type { CookingStatus } from './cookingStatusApi'
import { stepIngredients } from './cookingSteps'

/** Every main button of the activity: readable from the counter, hit with a floury thumb. */
const BUTTON_HEIGHT = 64

const fadeIn = keyframes`
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: none; }
`

/** A screen of the activity: it fades in, and scrolls when the window is short (a phone on its side). */
export const Screen = ({ id, children }: { id: string; children: ReactNode }) => (
  <Box
    data-testid={`cooking-${id}`}
    sx={{
      flex: 1,
      minHeight: 0,
      overflowY: 'auto',
      px: { xs: 2, md: 6 },
      py: 2,
      display: 'flex',
      flexDirection: 'column',
      '@media (prefers-reduced-motion: no-preference)': { animation: `${fadeIn} 240ms ease-out` },
    }}
  >
    {children}
  </Box>
)

/** The buttons at the foot of a screen. */
export const Footer = ({ children }: { children: ReactNode }) => (
  <Box
    component="footer"
    sx={{
      display: 'flex',
      gap: 2,
      justifyContent: 'space-between',
      px: { xs: 2, md: 6 },
      py: 2,
      borderTop: 1,
      borderColor: 'divider',
      '& .MuiButton-root': { minHeight: BUTTON_HEIGHT, fontSize: '1.125rem', px: 4 },
    }}
  >
    {children}
  </Box>
)

/** − / + around a number, never below one. */
const BigStepper = ({ label, value, onChange }: { label: string; value: number; onChange: (value: number) => void }) => (
  <Box role="group" aria-label={label} sx={{ display: 'flex', alignItems: 'center', gap: 3 }}>
    <IconButton
      aria-label={`Un de moins — ${label}`}
      disabled={value <= MIN_GUESTS}
      onClick={() => onChange(value - 1)}
      sx={{ width: BUTTON_HEIGHT, height: BUTTON_HEIGHT, border: 1, borderColor: 'divider' }}
    >
      <RemoveIcon />
    </IconButton>
    <Typography
      component="span"
      aria-live="polite"
      data-testid="cooking-guests-value"
      sx={{ minWidth: '2ch', textAlign: 'center', fontSize: '3.5rem', fontWeight: 600, lineHeight: 1 }}
    >
      {value}
    </Typography>
    <IconButton
      aria-label={`Un de plus — ${label}`}
      disabled={value >= MAX_GUESTS}
      onClick={() => onChange(value + 1)}
      sx={{ width: BUTTON_HEIGHT, height: BUTTON_HEIGHT, border: 1, borderColor: 'divider' }}
    >
      <AddIcon />
    </IconButton>
  </Box>
)

const peopleLabel = (count: number): string => `${count} pers.`

/** Loading, an empty meal, a failed read: nothing here ever closes the activity by itself. */
export const LoadingScreen = () => (
  <Screen id="loading">
    <LinearProgress aria-label="Chargement" sx={{ maxWidth: 480, width: '100%', m: 'auto' }} />
  </Screen>
)

export const ErrorScreen = ({ onRetry }: { onRetry: () => void }) => (
  <Screen id="error">
    <Box sx={{ m: 'auto', maxWidth: 560 }}>
      <Alert
        severity="error"
        action={
          <Button color="inherit" onClick={onRetry} sx={{ minHeight: BUTTON_HEIGHT }}>
            Réessayer
          </Button>
        }
      >
        La recette n'a pas pu être chargée. Vérifie la connexion, puis réessaie.
      </Alert>
    </Box>
  </Screen>
)

/**
 * Another meal or recipe is being cooked: resume it, or start this one over its ashes (the timers stop).
 * Nothing is lost without the owner saying so.
 */
export const ConflictScreen = ({
  status,
  launched,
  onResume,
  onReplace,
}: {
  status: CookingStatus
  launched: CookingOrigin
  onResume: () => void
  onReplace: () => void
}) => {
  const [where, setWhere] = useState<string | null>(null)

  useEffect(() => {
    const session = sessionFromState(status.origin, status.state)
    const current = session?.queue[session.recipeIndex]
    if (!session || !current) return
    const controller = new AbortController()
    fetchRecipe(current.recipeId, null, controller.signal)
      .then((recipe) => {
        const place = session.phase === 'servings' || session.step === 0 ? 'préparation' : `étape ${session.step}/${recipe.steps?.length ?? '?'}`
        setWhere(`${recipe.name} · ${place}`)
      })
      .catch(() => undefined)

    return () => controller.abort()
  }, [status])

  return (
    <Screen id="conflict">
      <Box sx={{ m: 'auto', maxWidth: 560, textAlign: 'center' }}>
        <Typography variant="h4" component="h1" sx={{ mb: 1 }}>
          Une cuisine est déjà en cours
        </Typography>
        {where && (
          <Typography color="text.secondary" data-testid="cooking-conflict-where" sx={{ mb: 1 }}>
            {where}
          </Typography>
        )}
        <Typography color="text.secondary" sx={{ mb: 3 }}>
          {`La reprendre, ou recommencer avec ${launched.kind === 'meal' ? 'ce repas' : 'cette recette'} : ses minuteurs en cours seront arrêtés.`}
        </Typography>
        <Box sx={{ display: 'flex', gap: 2, justifyContent: 'center', flexWrap: 'wrap' }}>
          <Button variant="outlined" onClick={onReplace} data-testid="cooking-conflict-replace" sx={{ minHeight: BUTTON_HEIGHT, px: 4 }}>
            Recommencer
          </Button>
          <Button variant="contained" onClick={onResume} data-testid="cooking-conflict-resume" sx={{ minHeight: BUTTON_HEIGHT, px: 4 }}>
            Reprendre
          </Button>
        </Box>
      </Box>
    </Screen>
  )
}

export const EmptyMealScreen = ({ onQuit }: { onQuit: () => void }) => (
  <Screen id="empty">
    <Box sx={{ m: 'auto', textAlign: 'center' }}>
      <Typography variant="h4" component="h1" sx={{ mb: 1 }}>
        Aucune recette à cuisiner
      </Typography>
      <Typography color="text.secondary" sx={{ mb: 3 }}>
        Ce repas n'a pas de recette : ajoutes-en une depuis les repas.
      </Typography>
      <Button variant="contained" onClick={onQuit} sx={{ minHeight: BUTTON_HEIGHT, px: 4 }}>
        Retour
      </Button>
    </Box>
  </Screen>
)

/** « Pour combien ? »: the first screen when the activity is launched from a recipe. */
export const ServingsScreen = ({
  recipeName,
  guests,
  defaultServings,
  onChange,
  onContinue,
}: {
  recipeName: string | null
  guests: number
  defaultServings: number | null
  onChange: (guests: number) => void
  onContinue: () => void
}) => (
  <>
    <Screen id="servings">
      <Box sx={{ m: 'auto', textAlign: 'center', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 3 }}>
        {recipeName && (
          <Typography variant="h5" component="p" color="text.secondary" data-testid="cooking-recipe-name">
            {recipeName}
          </Typography>
        )}
        <Typography variant="h3" component="h1" sx={{ fontWeight: 600 }}>
          Pour combien ?
        </Typography>
        <BigStepper label="Nombre de personnes" value={guests} onChange={onChange} />
        {defaultServings !== null && (
          <Typography variant="body2" color="text.secondary">
            La recette est prévue pour {defaultServings}.
          </Typography>
        )}
      </Box>
    </Screen>
    <Footer>
      <Box />
      <Button variant="contained" onClick={onContinue} sx={{ minWidth: { xs: '100%', md: 280 } }}>
        Continuer
      </Button>
    </Footer>
  </>
)

const Section = ({ title, children, testId }: { title: string; children: ReactNode; testId: string }) => (
  <Box component="section" aria-label={title} data-testid={testId} sx={{ mb: 3 }}>
    <Typography variant="overline" color="text.secondary" component="h2" sx={{ display: 'block', mb: 1, letterSpacing: 1.2 }}>
      {title}
    </Typography>
    {children}
  </Box>
)

/** « On sort tout »: what the recipe takes, for how many, and the recipes that follow. */
export const PreparationScreen = ({
  recipe,
  queue,
  names,
  recipeIndex,
  guests,
  refreshing,
  onGuests,
  onStart,
  onSkip,
}: {
  recipe: RecipeRecord
  queue: QueuedRecipe[]
  names: Record<string, string>
  recipeIndex: number
  guests: number
  refreshing: boolean
  onGuests: (guests: number) => void
  onStart: () => void
  onSkip: () => void
}) => {
  const lines = recipe.ingredients ?? []
  const utensils = (recipe.utensils ?? []).map(nameOf)
  const hasSteps = (recipe.steps ?? []).length > 0
  const isLast = recipeIndex === queue.length - 1

  return (
    <>
      <Screen id="preparation">
        <Typography variant="h3" component="h1" sx={{ fontWeight: 600 }}>
          On sort tout
        </Typography>
        <Typography variant="h5" component="p" color="text.secondary" data-testid="cooking-recipe-name" sx={{ mb: 3 }}>
          {recipe.name}
        </Typography>

        <Box sx={{ display: 'grid', gap: { xs: 0, md: 6 }, gridTemplateColumns: { xs: '1fr', md: '1.4fr 1fr' }, alignItems: 'start' }}>
          <Box>
            <Section title="Ingrédients" testId="cooking-ingredients">
              <Box component="ul" aria-busy={refreshing} sx={{ listStyle: 'none', m: 0, p: 0, opacity: refreshing ? 0.5 : 1, transition: 'opacity 150ms' }}>
                {lines.map((line, index) => (
                  <Box
                    key={line.id ?? index}
                    component="li"
                    data-testid="cooking-ingredient"
                    sx={{ display: 'flex', gap: 2, py: 0.75, fontSize: '1.25rem', alignItems: 'baseline' }}
                  >
                    <Box component="span" sx={{ minWidth: '6ch', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
                      {quantityLabel(line.quantity, line.unit)}
                    </Box>
                    <span>{line.ingredient?.name ?? line.ingredientName}</span>
                  </Box>
                ))}
              </Box>
            </Section>
            {utensils.length > 0 && (
              <Section title="Matériel" testId="cooking-utensils">
                <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
                  {utensils.map((name) => (
                    <Chip key={name} label={name} sx={{ fontSize: '1rem', height: 40 }} />
                  ))}
                </Box>
              </Section>
            )}
          </Box>

          <Box>
            <Section title="Convives" testId="cooking-guests">
              <BigStepper label="Convives" value={guests} onChange={onGuests} />
            </Section>
            <Section title={queue.length > 1 ? 'Les recettes à la suite' : 'La recette'} testId="cooking-queue">
              <Box component="ol" sx={{ listStyle: 'none', m: 0, p: 0 }}>
                {queue.map((entry, index) => (
                  <QueueRow
                    key={`${entry.recipeId}-${index}`}
                    position={index + 1}
                    name={names[entry.recipeId] ?? '…'}
                    entry={entry}
                    current={index === recipeIndex}
                  />
                ))}
              </Box>
            </Section>
          </Box>
        </Box>
      </Screen>
      <Footer>
        {hasSteps ? (
          <>
            <Box />
            <Button variant="contained" onClick={onStart} sx={{ minWidth: { xs: '100%', md: 280 } }}>
              C'est parti
            </Button>
          </>
        ) : (
          <>
            <Alert severity="info" data-testid="cooking-no-steps" sx={{ flex: 1, alignItems: 'center' }}>
              Pas d'étapes : ajoute-les à la recette.{' '}
              <Box component={RouterLink} to={`/recipes/${idOf(recipe.id)}/show`} sx={{ color: 'inherit', fontWeight: 600 }}>
                Modifier la recette
              </Box>
            </Alert>
            <Button variant="contained" onClick={onSkip}>
              {isLast ? 'Terminer' : 'Recette suivante'}
            </Button>
          </>
        )}
      </Footer>
    </>
  )
}

const QueueRow = ({ position, name, entry, current }: { position: number; name: string; entry: QueuedRecipe; current: boolean }) => (
  <Box
    component="li"
    aria-current={current ? 'step' : undefined}
    data-testid="cooking-queue-item"
    data-current={current}
    sx={(theme) => ({
      display: 'flex',
      alignItems: 'center',
      gap: 2,
      py: 1.25,
      px: 1.5,
      mb: 0.5,
      borderRadius: 2,
      bgcolor: current ? `${theme.palette.primary.main}22` : 'transparent',
      border: 1,
      borderColor: current ? 'primary.main' : 'transparent',
    })}
  >
    <Box
      component="span"
      sx={{ width: 32, height: 32, borderRadius: '50%', display: 'grid', placeItems: 'center', bgcolor: current ? 'primary.main' : 'action.selected', color: current ? 'primary.contrastText' : 'text.primary', fontWeight: 700 }}
    >
      {position}
    </Box>
    <Typography sx={{ flex: 1, fontSize: '1.125rem', fontWeight: current ? 600 : 400 }}>{name}</Typography>
    <Typography variant="body2" color="text.secondary" data-testid="cooking-queue-servings">
      {peopleLabel(entry.servings)}
    </Typography>
  </Box>
)

const stepFade = keyframes`
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: none; }
`

/**
 * A step, one at a time: its number, its text, what it takes and what comes next, with the column of
 * the meal's steps at its left and its own timer at its right. « Suivant » ticks the step and never
 * waits; a step in the background starts its timer and goes on.
 */
export const StepScreen = ({
  step,
  steps,
  lines,
  isLastOfAll,
  launches,
  busy,
  column,
  timers,
  onPrevious,
  onNext,
}: {
  step: number
  steps: RecipeStep[]
  lines: RecipeLine[]
  isLastOfAll: boolean
  /** The main button is « Lancer et continuer »: the step's timer starts, then the next step. */
  launches: boolean
  /** A timer is being started: « Suivant » waits for it without being disabled. */
  busy: boolean
  /** The meal's steps, at the left. */
  column: ReactNode
  /** The step's own timer, at the right. */
  timers: ReactNode
  onPrevious: () => void
  onNext: () => void
}) => {
  const current = steps[step - 1]
  const following = steps[step]
  const ingredients = stepIngredients(current, lines)
  const utensils = current.utensils.map(nameOf)

  return (
    <>
      <Box sx={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: { xs: 'column', md: 'row' } }}>
        {column}
        <Screen id="step">
        <Box
          sx={{
            flex: 1,
            display: 'grid',
            gridTemplateColumns: { xs: 'minmax(0, 1fr)', lg: 'minmax(0, 1.25fr) minmax(0, 1fr)' },
            gap: { xs: 3, lg: 8 },
            alignItems: 'center',
          }}
        >
          <Box
            key={step}
            data-testid="cooking-step-content"
            sx={{
              display: 'flex',
              flexDirection: 'column',
              gap: 2,
              justifyContent: 'center',
              minWidth: 0,
              py: 2,
              '@media (prefers-reduced-motion: no-preference)': { animation: `${stepFade} 560ms ease-out` },
            }}
          >
            <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2 }}>
              <Typography
                component="span"
                data-testid="cooking-step-number"
                aria-hidden
                sx={{ fontSize: { xs: 72, md: 104, lg: 120 }, lineHeight: 1, fontWeight: 700, color: 'primary.main', fontVariantNumeric: 'tabular-nums' }}
              >
                {step}
              </Typography>
              <Typography variant="overline" color="text.secondary" data-testid="cooking-step-count">
                Étape {step} sur {steps.length}
              </Typography>
            </Box>
            <Typography
              data-testid="cooking-step-text"
              sx={{ fontSize: { xs: 28, md: 34, lg: 42 }, lineHeight: 1.25, fontWeight: 500, maxWidth: '36ch' }}
            >
              {current.text}
            </Typography>

            {(ingredients.length > 0 || utensils.length > 0) && (
              <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mt: 1 }} data-testid="cooking-step-needs">
                {ingredients.map(({ key, name, line }) => (
                  <Chip
                    key={key}
                    data-testid="cooking-step-ingredient"
                    label={line ? `${quantityLabel(line.quantity, line.unit)} ${name}`.trim() : name}
                    sx={{ fontSize: '1.125rem', height: 44 }}
                  />
                ))}
                {utensils.map((name) => (
                  <Chip key={name} data-testid="cooking-step-utensil" variant="outlined" label={name} sx={{ fontSize: '1.125rem', height: 44 }} />
                ))}
              </Box>
            )}
          </Box>
          <Box sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 4, py: 2 }}>{timers}</Box>
        </Box>

        {following && (
          <Typography data-testid="cooking-upnext" color="text.secondary" noWrap sx={{ fontSize: '1.125rem', pb: 1 }}>
            Ensuite · {following.text}
          </Typography>
        )}
        </Screen>
      </Box>
      <Footer>
        <Button onClick={onPrevious}>← Précédent</Button>
        <Button
          variant="contained"
          onClick={onNext}
          aria-busy={busy}
          sx={{ minWidth: { xs: 0, md: 280 }, flex: { xs: 1, md: 'none' } }}
        >
          {launches ? 'Lancer et continuer' : isLastOfAll ? 'Repas servi' : 'Suivant →'}
        </Button>
      </Footer>
    </>
  )
}

/** « Bon appétit »: how long it took and for how many. */
export const DoneScreen = ({ minutes, guests, onQuit }: { minutes: number; guests: number; onQuit: () => void }) => (
  <Screen id="done">
    <Box sx={{ m: 'auto', textAlign: 'center' }}>
      <Typography variant="h2" component="h1" sx={{ fontWeight: 600, mb: 2 }}>
        Bon appétit
      </Typography>
      <Typography color="text.secondary" data-testid="cooking-done-summary" sx={{ fontSize: '1.5rem', mb: 4 }}>
        {durationLabel(Math.max(1, minutes))} en cuisine · {guests} {guests > 1 ? 'convives' : 'convive'}
      </Typography>
      <Button variant="contained" onClick={onQuit} sx={{ minHeight: BUTTON_HEIGHT, minWidth: 280 }}>
        Terminer
      </Button>
    </Box>
  </Screen>
)
