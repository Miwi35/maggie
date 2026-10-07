import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Box from '@mui/material/Box'
import ClickAwayListener from '@mui/material/ClickAwayListener'
import InputAdornment from '@mui/material/InputAdornment'
import Paper from '@mui/material/Paper'
import Popper from '@mui/material/Popper'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import List from '@mui/material/List'
import ListItemButton from '@mui/material/ListItemButton'
import ListItemIcon from '@mui/material/ListItemIcon'
import ListItemText from '@mui/material/ListItemText'
import ListSubheader from '@mui/material/ListSubheader'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import SearchIcon from '@mui/icons-material/Search'
import CloseIcon from '@mui/icons-material/Close'
import EventIcon from '@mui/icons-material/Event'
import CheckCircleIcon from '@mui/icons-material/CheckCircle'
import RestaurantIcon from '@mui/icons-material/Restaurant'
import ShoppingCartIcon from '@mui/icons-material/ShoppingCart'
import CalendarMonthIcon from '@mui/icons-material/CalendarMonth'
import ShoppingBagIcon from '@mui/icons-material/ShoppingBag'
import DinnerDiningIcon from '@mui/icons-material/DinnerDining'
import RepeatIcon from '@mui/icons-material/Repeat'
import NotificationsIcon from '@mui/icons-material/Notifications'
import PersonIcon from '@mui/icons-material/Person'
import { useNarrowScreen } from '../../hooks/useNarrowScreen'
import { useSearch, SEARCH_INDEX_CONFIG, getResultLabel, getResultHighlight, getResultPath } from './searchConfig'
import type { SearchResult } from './searchConfig'

const ICON_MAP: Record<string, React.ReactElement> = {
  Event: <EventIcon fontSize="small" />,
  CheckCircle: <CheckCircleIcon fontSize="small" />,
  Restaurant: <RestaurantIcon fontSize="small" />,
  ShoppingCart: <ShoppingCartIcon fontSize="small" />,
  CalendarMonth: <CalendarMonthIcon fontSize="small" />,
  ShoppingBag: <ShoppingBagIcon fontSize="small" />,
  DinnerDining: <DinnerDiningIcon fontSize="small" />,
  Repeat: <RepeatIcon fontSize="small" />,
  Notifications: <NotificationsIcon fontSize="small" />,
  Person: <PersonIcon fontSize="small" />,
}

const MAX_PER_TYPE = 3

function getIcon(indexName: string): React.ReactElement {
  const config = SEARCH_INDEX_CONFIG[indexName]
  return config ? (ICON_MAP[config.icon] ?? <SearchIcon fontSize="small" />) : <SearchIcon fontSize="small" />
}

export function SearchBar() {
  const navigate = useNavigate()
  const inputRef = useRef<HTMLInputElement>(null)
  const anchorRef = useRef<HTMLDivElement>(null)
  const [anchorEl, setAnchorEl] = useState<HTMLDivElement | null>(null)
  const [open, setOpen] = useState(false)
  const isNarrow = useNarrowScreen()
  // The field is a magnifier until asked for, below `md` (MAG-38): the app bar
  // already carries a burger, a title, the dictation, the bell, the chat and
  // the avatar, and a 400px search box on top of that pushed half of them off
  // a 393px screen.
  const [expanded, setExpanded] = useState(false)
  const collapsed = isNarrow && !expanded

  // Sync ref to state so Popper reads it without accessing ref during render
  const anchorCallbackRef = useCallback((node: HTMLDivElement | null) => {
    anchorRef.current = node
    setAnchorEl(node)
  }, [])
  const { query, setQuery, data, loading } = useSearch(300, MAX_PER_TYPE)

  // Ctrl+K shortcut
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
        e.preventDefault()
        inputRef.current?.focus()
      }
      if (e.key === 'Escape') {
        setOpen(false)
        inputRef.current?.blur()
      }
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [])

  // Open dropdown when there are results
  useEffect(() => {
    if (data && data.results.length > 0) {
      setOpen(true)
    } else if (!query.trim()) {
      setOpen(false)
    }
  }, [data, query])

  const collapse = () => {
    setExpanded(false)
    setOpen(false)
    setQuery('')
  }

  const handleResultClick = (result: SearchResult) => {
    navigate(getResultPath(result))
    setOpen(false)
    setQuery('')
    setExpanded(false)
  }

  const handleViewAll = () => {
    navigate(`/search?q=${encodeURIComponent(query)}`)
    setOpen(false)
    setQuery('')
    setExpanded(false)
  }

  // Group results by index
  const grouped = (data?.results ?? []).reduce<Record<string, SearchResult[]>>((acc, r) => {
    if (!acc[r.index]) acc[r.index] = []
    if (acc[r.index].length < MAX_PER_TYPE) acc[r.index].push(r)
    return acc
  }, {})

  if (collapsed) {
    return (
      <Box sx={{ flex: 1, display: 'flex', justifyContent: 'flex-end' }}>
        <IconButton color="inherit" aria-label="Rechercher" onClick={() => setExpanded(true)}>
          <SearchIcon />
        </IconButton>
      </Box>
    )
  }

  return (
    <ClickAwayListener onClickAway={() => setOpen(false)}>
      <Box
        ref={anchorCallbackRef}
        sx={{ flex: 1, maxWidth: { xs: 'none', md: 400 }, mx: 1, display: 'flex', alignItems: 'center' }}
      >
        <TextField
          autoFocus={isNarrow && expanded}
          inputRef={inputRef}
          size="small"
          placeholder="Rechercher…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onFocus={() => {
            if (data && data.results.length > 0) setOpen(true)
          }}
          fullWidth
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  {loading ? <CircularProgress size={18} color="inherit" /> : <SearchIcon sx={{ color: 'inherit', opacity: 0.7 }} />}
                </InputAdornment>
              ),
              // No keyboard, no shortcut to advertise — and 50px of hint is a
              // tenth of a phone's app bar.
              endAdornment: isNarrow ? undefined : (
                <InputAdornment position="end">
                  <Typography
                    variant="caption"
                    sx={{
                      border: 1,
                      borderColor: 'divider',
                      borderRadius: 0.5,
                      px: 0.5,
                      py: 0.1,
                      fontSize: '0.65rem',
                      opacity: 0.6,
                      lineHeight: 1.4,
                    }}
                  >
                    Ctrl+K
                  </Typography>
                </InputAdornment>
              ),
            },
          }}
          sx={(theme) => ({
            '& .MuiOutlinedInput-root': {
              backgroundColor: theme.palette.veilleuse.raised,
              color: 'inherit',
              '& fieldset': { borderColor: theme.palette.divider },
              '&:hover fieldset': { borderColor: theme.palette.text.secondary },
              '&.Mui-focused fieldset': { borderColor: theme.palette.primary.main },
            },
            '& .MuiInputAdornment-root': { color: 'inherit' },
          })}
        />
        {isNarrow && expanded && (
          <IconButton color="inherit" aria-label="Fermer la recherche" onClick={collapse}>
            <CloseIcon />
          </IconButton>
        )}
        <Popper
          open={open}
          anchorEl={anchorEl}
          placement="bottom-start"
          style={{ zIndex: 1300, width: anchorEl?.offsetWidth ?? 400 }}
        >
          <Paper sx={{ mt: 0.5, maxHeight: 400, overflow: 'auto' }} elevation={8}>
            <List dense disablePadding>
              {Object.entries(grouped).map(([index, results]) => {
                const config = SEARCH_INDEX_CONFIG[index]
                return (
                  <Box key={index}>
                    <ListSubheader sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                      {getIcon(index)}
                      {config?.label ?? index}
                    </ListSubheader>
                    {results.map((result) => (
                      <ListItemButton key={result.id} onClick={() => handleResultClick(result)}>
                        <ListItemIcon sx={{ minWidth: 32 }}>{getIcon(index)}</ListItemIcon>
                        <ListItemText
                          primary={getResultLabel(result)}
                          secondary={
                            <span dangerouslySetInnerHTML={{ __html: getResultHighlight(result) }} />
                          }
                        />
                      </ListItemButton>
                    ))}
                  </Box>
                )
              })}
            </List>
            {data && data.total > 0 && (
              <Button fullWidth onClick={handleViewAll} sx={{ py: 1, borderTop: 1, borderColor: 'divider' }}>
                Voir tous les résultats ({data.total})
              </Button>
            )}
            {data && data.results.length === 0 && query.trim() && (
              <Typography sx={{ p: 2, textAlign: 'center', color: 'text.secondary' }}>
                Aucun résultat
              </Typography>
            )}
          </Paper>
        </Popper>
      </Box>
    </ClickAwayListener>
  )
}
