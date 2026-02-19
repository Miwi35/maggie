import { useEffect } from 'react'
import { useSearchParams } from 'react-router-dom'
import Box from '@mui/material/Box'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import InputAdornment from '@mui/material/InputAdornment'
import Pagination from '@mui/material/Pagination'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import SearchIcon from '@mui/icons-material/Search'
import { useSearch, SEARCH_INDEX_CONFIG } from './searchConfig'
import { SearchResultCard } from './SearchResultCard'

export function SearchPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const urlQuery = searchParams.get('q') ?? ''
  const urlType = searchParams.get('type') ?? ''

  const { query, setQuery, types, setTypes, page, setPage, data, loading } = useSearch(300)

  // Sync URL → state on mount
  useEffect(() => {
    if (urlQuery) setQuery(urlQuery)
    if (urlType) setTypes([urlType])
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Sync state → URL
  useEffect(() => {
    const params: Record<string, string> = {}
    if (query) params.q = query
    if (types && types.length === 1) params.type = types[0]
    setSearchParams(params, { replace: true })
  }, [query, types, setSearchParams])

  const handleTypeClick = (indexName: string | null) => {
    setTypes(indexName ? [indexName] : null)
    setPage(1)
  }

  const totalPages = data ? Math.ceil(data.total / data.limit) : 0

  return (
    <Box sx={{ p: 3, maxWidth: 800, mx: 'auto' }}>
      <Typography variant="h5" sx={{ mb: 3 }}>
        Recherche
      </Typography>

      <TextField
        autoFocus
        fullWidth
        placeholder="Rechercher…"
        value={query}
        onChange={(e) => {
          setQuery(e.target.value)
          setPage(1)
        }}
        slotProps={{
          input: {
            startAdornment: (
              <InputAdornment position="start">
                <SearchIcon />
              </InputAdornment>
            ),
          },
        }}
        sx={{ mb: 2 }}
      />

      {/* Type filter chips */}
      <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mb: 3 }}>
        <Chip
          label="Tous"
          variant={!types ? 'filled' : 'outlined'}
          color={!types ? 'primary' : 'default'}
          onClick={() => handleTypeClick(null)}
        />
        {Object.entries(SEARCH_INDEX_CONFIG).map(([key, config]) => (
          <Chip
            key={key}
            label={config.label}
            variant={types?.includes(key) ? 'filled' : 'outlined'}
            color={types?.includes(key) ? 'primary' : 'default'}
            onClick={() => handleTypeClick(key)}
          />
        ))}
      </Box>

      {/* Loading */}
      {loading && (
        <Box sx={{ display: 'flex', justifyContent: 'center', py: 4 }}>
          <CircularProgress />
        </Box>
      )}

      {/* Results */}
      {!loading && data && data.results.length > 0 && (
        <Stack spacing={1.5}>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
            {data.total} résultat{data.total > 1 ? 's' : ''}
          </Typography>
          {data.results.map((result) => (
            <SearchResultCard key={`${result.index}-${result.id}`} result={result} />
          ))}
        </Stack>
      )}

      {/* Empty state */}
      {!loading && data && data.results.length === 0 && query.trim() && (
        <Typography sx={{ textAlign: 'center', py: 4, color: 'text.secondary' }}>
          Aucun résultat pour « {query} »
        </Typography>
      )}

      {/* Pagination */}
      {totalPages > 1 && (
        <Box sx={{ display: 'flex', justifyContent: 'center', mt: 3 }}>
          <Pagination
            count={totalPages}
            page={page}
            onChange={(_, p) => setPage(p)}
            color="primary"
          />
        </Box>
      )}
    </Box>
  )
}
