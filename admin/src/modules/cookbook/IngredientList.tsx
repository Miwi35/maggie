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
import { energyLabel, filterIngredients, pieceLabel, valuesPills, valuesState } from './ingredientTable'
import type { IngredientRow, PillKey } from './ingredientTable'

const INGREDIENT_TOPICS = ['/api/ingredients/{id}']

const INGREDIENT_LIVE: LiveConfig = {
  topics: INGREDIENT_TOPICS,
  required: ['name', 'category'],
}

// The table filters and searches what it holds, so it holds the whole collection (the API's page maximum).
const ALL_INGREDIENTS = 100

const COLUMNS = [
  { label: 'Ingrédient', align: 'left' },
  { label: 'Valeurs', align: 'left' },
  { label: 'Énergie / 100 g', align: 'right' },
  { label: 'Poids par pièce', align: 'right' },
] as const

const numeric = { fontVariantNumeric: 'tabular-nums' } as const

const Dash = () => <Typography component="span" color="text.disabled">—</Typography>

const ValuesPill = ({ label, active, count, onClick }: { label: string; active: boolean; count: number; onClick: () => void }) => (
  <ButtonBase
    aria-pressed={active}
    onClick={onClick}
    sx={(theme) => ({
      height: 36,
      px: 1.75,
      gap: 0.75,
      borderRadius: 999,
      fontSize: 14,
      fontFamily: 'inherit',
      fontWeight: active ? 500 : 400,
      color: active ? 'module.cuisine' : 'text.secondary',
      bgcolor: active ? alpha(theme.palette.module.cuisine, 0.16) : alpha(theme.palette.text.primary, 0.05),
    })}
  >
    {label}{' '}
    <span style={{ opacity: 0.7 }}>{count}</span>
  </ButtonBase>
)

const IngredientBrowser = () => {
  const { data, total, perPage } = useListContext<IngredientRow>()
  const createPath = useCreatePath()
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [pill, setPill] = useState<PillKey>('all')

  const ingredients = useMemo(() => data ?? [], [data])
  const pills = useMemo(() => valuesPills(ingredients), [ingredients])
  const shown = useMemo(() => filterIngredients(ingredients, query, pill), [ingredients, query, pill])
  const open = (ingredient: IngredientRow) => navigate(createPath({ resource: 'ingredients', type: 'edit', id: ingredient.id }))

  return (
    <Box sx={{ p: 2 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, mb: 2 }}>
        <Typography variant="h5" component="h1" sx={{ fontWeight: 500, fontSize: 26 }}>
          Ingrédients
        </Typography>
        <Box sx={{ flex: 1 }} />
        <TextField
          type="search"
          size="small"
          placeholder="Rechercher un ingrédient"
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
            htmlInput: { 'aria-label': 'Rechercher un ingrédient' },
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
          to={createPath({ resource: 'ingredients', type: 'create' })}
          variant="contained"
          sx={{ flexShrink: 0, height: 40, borderRadius: '10px', bgcolor: 'module.cuisine', color: 'background.default', boxShadow: 'none' }}
        >
          + Ingrédient
        </Button>
      </Box>

      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mb: 2 }}>
        {pills.map((own) => (
          <ValuesPill key={own.key} label={own.label} count={own.count} active={pill === own.key} onClick={() => setPill(own.key)} />
        ))}
      </Box>

      <Paper elevation={0} sx={{ minWidth: 0, overflow: 'hidden', borderRadius: '20px', px: 1.5, py: 1, bgcolor: 'background.paper' }}>
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
            {shown.map((ingredient) => {
              const state = valuesState(ingredient)

              return (
                <TableRow
                  key={ingredient.id}
                  hover
                  tabIndex={0}
                  onClick={() => open(ingredient)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && event.target === event.currentTarget) open(ingredient)
                  }}
                  sx={{ cursor: 'pointer', height: 48 }}
                >
                  <TableCell sx={{ fontWeight: 500 }}>{ingredient.name}</TableCell>
                  <TableCell>
                    {state === 'ciqual' && (
                      <Chip
                        label="Ciqual"
                        size="small"
                        sx={(theme) => ({ height: 24, color: 'text.secondary', bgcolor: alpha(theme.palette.text.primary, 0.06) })}
                      />
                    )}
                    {state === 'none' && (
                      <Chip
                        label="Sans valeurs"
                        size="small"
                        sx={(theme) => ({ height: 24, color: 'warning.main', bgcolor: alpha(theme.palette.warning.main, 0.14) })}
                      />
                    )}
                  </TableCell>
                  <TableCell align="right" sx={numeric}>
                    {ingredient.kcalPer100g == null ? <Dash /> : energyLabel(ingredient.kcalPer100g)}
                  </TableCell>
                  <TableCell align="right" sx={numeric}>
                    {ingredient.pieceWeightGrams == null ? <Dash /> : pieceLabel(ingredient.pieceWeightGrams)}
                  </TableCell>
                </TableRow>
              )
            })}
            {shown.length === 0 && (
              <TableRow>
                <TableCell colSpan={COLUMNS.length}>
                  <Typography color="text.secondary" sx={{ py: 2, textAlign: 'center' }}>
                    Aucun ingrédient ne correspond.
                  </Typography>
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
        {(total ?? 0) > perPage && <Pagination rowsPerPageOptions={[]} />}
      </Paper>
    </Box>
  )
}

export const IngredientList = () => (
  <LiveList
    live={INGREDIENT_LIVE}
    perPage={ALL_INGREDIENTS}
    sort={{ field: 'name', order: 'ASC' }}
    actions={false}
    pagination={false}
    component="div"
    empty={
      <>
        <Typography variant="h5" component="h1" sx={{ fontWeight: 500, fontSize: 26, p: 2 }}>
          Ingrédients
        </Typography>
        <ListEmpty
          title="Aucun ingrédient pour l'instant"
          action="Ajouter un ingrédient"
        />
      </>
    }
  >
    <IngredientBrowser />
  </LiveList>
)
