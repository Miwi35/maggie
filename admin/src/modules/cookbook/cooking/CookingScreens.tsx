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
import { idOf } from '../recipeSheet'
import type { RecipeRecord } from '../recipeSheet'
import { nameOf } from '../recipeSteps'
import { MAX_GUESTS, MIN_GUESTS } from './cookingSession'
import type { QueuedRecipe } from './cookingSession'

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

/** A step: the text for now (the detailed course is MAG-581), with « Précédent » / « Suivant ». */
export const StepScreen = ({
  step,
  stepCount,
  text,
  isLastOfAll,
  onPrevious,
  onNext,
}: {
  step: number
  stepCount: number
  text: string
  isLastOfAll: boolean
  onPrevious: () => void
  onNext: () => void
}) => (
  <>
    <Screen id="step">
      <Typography variant="overline" color="text.secondary" data-testid="cooking-step-count">
        Étape {step} sur {stepCount}
      </Typography>
      <Typography
        data-testid="cooking-step-text"
        sx={{ m: 'auto 0', fontSize: 'clamp(1.75rem, 1rem + 2.6vw, 3rem)', lineHeight: 1.3, fontWeight: 500, maxWidth: '36ch' }}
      >
        {text}
      </Typography>
    </Screen>
    <Footer>
      <Button onClick={onPrevious}>← Précédent</Button>
      <Button variant="contained" onClick={onNext} sx={{ minWidth: { xs: 0, md: 280 }, flex: { xs: 1, md: 'none' } }}>
        {isLastOfAll ? 'Terminer' : 'Suivant →'}
      </Button>
    </Footer>
  </>
)

export const DoneScreen = ({ onQuit }: { onQuit: () => void }) => (
  <Screen id="done">
    <Box sx={{ m: 'auto', textAlign: 'center' }}>
      <Typography variant="h2" component="h1" sx={{ fontWeight: 600, mb: 4 }}>
        Bon appétit
      </Typography>
      <Button variant="contained" onClick={onQuit} sx={{ minHeight: BUTTON_HEIGHT, minWidth: 280 }}>
        Terminer
      </Button>
    </Box>
  </Screen>
)
