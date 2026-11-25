import { useMemo, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import ButtonBase from '@mui/material/ButtonBase'
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
import { alpha } from '@mui/material/styles'
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
  { label: 'Portions', align: 'left' },
  { label: 'Tags', align: 'left' },
  { label: 'Durée', align: 'left' },
  { label: 'kcal / pers.', align: 'left' },
  { label: 'Dernier repas', align: 'left' },
] as const

const numeric = { fontVariantNumeric: 'tabular-nums' } as const

const TagPill = ({ label, active, onClick }: { label: string; active: boolean; onClick: () => void }) => (
  <ButtonBase
    aria-pressed={active}
    onClick={onClick}
    sx={(theme) => ({
      height: 36,
      px: 1.75,
      borderRadius: 999,
      fontSize: 14,
      fontFamily: 'inherit',
      fontWeight: active ? 500 : 400,
      color: active ? 'module.cuisine' : 'text.secondary',
      bgcolor: active ? alpha(theme.palette.module.cuisine, 0.16) : alpha(theme.palette.text.primary, 0.05),
    })}
  >
    {label}
  </ButtonBase>
)

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
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2 }}>
        <Typography variant="h5" component="h1" sx={{ fontWeight: 500, fontSize: 26 }}>
          Recettes
        </Typography>
        <Box sx={{ flex: 1 }} />
        <TextField
          type="search"
          size="small"
          placeholder="Rechercher une recette"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          fullWidth={false}
          sx={{
            width: 280,
            flexShrink: 0,
            '& .MuiInputBase-root': { height: 40, borderRadius: '10px', bgcolor: 'background.paper' },
            '& fieldset': { border: 0 },
          }}
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
        <Button
          component={RouterLink}
          to={createPath({ resource: 'recipes', type: 'create' })}
          variant="contained"
          sx={{ flexShrink: 0, height: 40, borderRadius: '10px', bgcolor: 'module.cuisine', color: 'background.default', boxShadow: 'none' }}
        >
          + Recette
        </Button>
      </Box>

      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mb: 2 }}>
        <TagPill label="Toutes" active={activeTag === null} onClick={() => setTag(null)} />
        {pills.map((pill) => (
          <TagPill key={pill.key} label={pill.label} active={activeTag === pill.key} onClick={() => setTag(pill.key)} />
        ))}
      </Box>

      <Box sx={{ display: 'flex', alignItems: 'flex-start', gap: 2 }}>
        <Paper elevation={0} sx={{ flexGrow: 1, minWidth: 0, overflow: 'hidden', borderRadius: '20px', px: 1.5, py: 1, bgcolor: 'background.paper' }}>
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
                    sx={{ cursor: 'pointer', height: 48 }}
                  >
                    <TableCell sx={{ fontWeight: 500 }}>{recipe.name}</TableCell>
                    <TableCell sx={numeric}>
                      {recipe.servings}
                    </TableCell>
                    <TableCell>
                      <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                        {(recipe.tags ?? []).map((own) => (
                          <Chip
                            key={own}
                            label={own}
                            size="small"
                            sx={(theme) => ({ height: 24, color: 'text.secondary', bgcolor: alpha(theme.palette.text.primary, 0.06) })}
                          />
                        ))}
                      </Box>
                    </TableCell>
                    <TableCell sx={numeric}>
                      {minutes !== null ? durationLabel(minutes) : '—'}
                    </TableCell>
                    <TableCell sx={numeric}>
                      {own?.kcalPerServing != null ? own.kcalPerServing : '—'}
                    </TableCell>
                    <TableCell sx={numeric}>
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
