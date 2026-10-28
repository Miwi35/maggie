import { useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import InputAdornment from '@mui/material/InputAdornment'
import Paper from '@mui/material/Paper'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import SearchIcon from '@mui/icons-material/Search'
import { Pagination, useCreatePath, useListContext } from 'react-admin'
import { Link as RouterLink, useNavigate } from 'react-router-dom'
import { ListEmpty } from '../../components/list/ListEmpty'
import { LiveList } from '../../components/list/LiveList'
import type { LiveConfig } from '../../hooks/liveList'
import { RecipePreview } from './RecipePreview'
import { canonicalRecordId, durationLabel, idOf } from './recipeSheet'
import type { RecipeRecord } from './recipeSheet'
import { filterRecipes, lastMealLabel, stepsMinutes, tagPills } from './recipeTable'
import { useRecipeFacts } from './useRecipeFacts'

const RECIPE_TOPICS = ['/api/recipes/{id}']

const RECIPE_LIVE: LiveConfig = {
  topics: RECIPE_TOPICS,
  required: ['name', 'servings'],
  fallback: () => ({ createdAt: new Date().toISOString() }),
}

// The table filters and searches what it holds, so it holds the whole collection (the API's page maximum).
const ALL_RECIPES = 100

const COLUMNS = [
  { label: 'Recette', align: 'left' },
  { label: 'Portions', align: 'right' },
  { label: 'Tags', align: 'left' },
  { label: 'Durée', align: 'right' },
  { label: 'kcal / pers.', align: 'right' },
  { label: 'Dernier repas', align: 'right' },
] as const

const numeric = { fontVariantNumeric: 'tabular-nums' } as const

const RecipeBrowser = () => {
  const { data, total, perPage } = useListContext<RecipeRecord>()
  const facts = useRecipeFacts(data)
  const createPath = useCreatePath()
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [tag, setTag] = useState<string | null>(null)
  const [pointed, setPointed] = useState<string | null>(null)

  const recipes = useMemo(() => data ?? [], [data])
  const pills = useMemo(() => tagPills(recipes), [recipes])
  // A tag whose last recipe is gone has no pastille left: showing nothing under it would be a trap.
  const activeTag = tag !== null && pills.some((pill) => pill.key === tag) ? tag : null
  const shown = useMemo(() => filterRecipes(recipes, query, activeTag), [recipes, query, activeTag])
  const previewed = shown.find((recipe) => recipe.id === pointed) ?? null
  const factsOf = (recipe: RecipeRecord) => facts[idOf(canonicalRecordId(String(recipe.id)))]
  const open = (recipe: RecipeRecord) => navigate(createPath({ resource: 'recipes', type: 'show', id: recipe.id }))

  return (
    <Box sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2, flexWrap: 'wrap', mb: 2 }}>
        <TextField
          type="search"
          size="small"
          placeholder="Rechercher une recette"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          sx={{ flexGrow: 1, maxWidth: 420 }}
          slotProps={{
            htmlInput: { 'aria-label': 'Rechercher une recette' },
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchIcon fontSize="small" />
                </InputAdornment>
              ),
            },
          }}
        />
        <Button component={RouterLink} to={createPath({ resource: 'recipes', type: 'create' })} variant="contained">
          + Recette
        </Button>
      </Box>

      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mb: 2 }}>
        <Chip
          component="button"
          label="Toutes"
          aria-pressed={activeTag === null}
          color={activeTag === null ? 'primary' : 'default'}
          variant={activeTag === null ? 'filled' : 'outlined'}
          onClick={() => setTag(null)}
        />
        {pills.map((pill) => (
          <Chip
            key={pill.key}
            component="button"
            label={pill.label}
            aria-pressed={activeTag === pill.key}
            color={activeTag === pill.key ? 'primary' : 'default'}
            variant={activeTag === pill.key ? 'filled' : 'outlined'}
            onClick={() => setTag(pill.key)}
          />
        ))}
      </Box>

      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 2 }}>
        <Paper variant="outlined" sx={{ flexGrow: 1, minWidth: 0, overflow: 'hidden' }}>
          <Table size="small">
            <TableHead>
              <TableRow>
                {COLUMNS.map((column) => (
                  <TableCell key={column.label} align={column.align}>
                    {column.label}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {shown.map((recipe) => {
                const minutes = stepsMinutes(recipe.steps)
                const own = factsOf(recipe)

                return (
                  <TableRow
                    key={recipe.id}
                    hover
                    selected={recipe.id === previewed?.id}
                    tabIndex={0}
                    onMouseEnter={() => setPointed(recipe.id)}
                    onFocus={() => setPointed(recipe.id)}
                    onClick={() => open(recipe)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter' && event.target === event.currentTarget) open(recipe)
                    }}
                    sx={{ cursor: 'pointer' }}
                  >
                    <TableCell sx={{ fontWeight: 500 }}>{recipe.name}</TableCell>
                    <TableCell align="right" sx={numeric}>
                      {recipe.servings}
                    </TableCell>
                    <TableCell>
                      <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                        {(recipe.tags ?? []).map((own) => (
                          <Chip key={own} label={own} size="small" />
                        ))}
                      </Box>
                    </TableCell>
                    <TableCell align="right" sx={numeric}>
                      {minutes !== null ? durationLabel(minutes) : '—'}
                    </TableCell>
                    <TableCell align="right" sx={numeric}>
                      {own?.kcalPerServing != null ? own.kcalPerServing : '—'}
                    </TableCell>
                    <TableCell align="right" sx={numeric}>
                      {lastMealLabel(own?.lastMealDate)}
                    </TableCell>
                  </TableRow>
                )
              })}
              {shown.length === 0 && (
                <TableRow>
                  <TableCell colSpan={COLUMNS.length}>
                    <Typography color="text.secondary" sx={{ py: 2, textAlign: 'center' }}>
                      Aucune recette ne correspond.
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
            </TableBody>
          </Table>
          {(total ?? 0) > perPage && <Pagination rowsPerPageOptions={[]} />}
        </Paper>
        <RecipePreview recipe={previewed} facts={previewed ? factsOf(previewed) : undefined} />
      </Box>
    </Box>
  )
}

export const RecipeList = () => (
  <LiveList
    live={RECIPE_LIVE}
    perPage={ALL_RECIPES}
    sort={{ field: 'name', order: 'ASC' }}
    actions={false}
    pagination={false}
    component="div"
    empty={
      <ListEmpty
        title="Aucune recette pour l'instant"
        action="Ajouter une recette"
      />
    }
  >
    <RecipeBrowser />
  </LiveList>
)
